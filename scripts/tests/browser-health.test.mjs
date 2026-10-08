import assert from 'node:assert/strict';
import test from 'node:test';
import { waitForUnhealthySource } from '../lib/browser-health.mjs';

function jsonResponse(status, value) {
  return new Response(JSON.stringify(value), {
    status,
    headers: { 'Content-Type': 'application/json' },
  });
}

function fakePage(responses, visible = []) {
  let poll = 0;
  let badge = 0;
  return {
    get pollCount() { return poll; },
    async fetchImpl() {
      const result = responses[Math.min(poll, responses.length - 1)];
      poll += 1;
      return result instanceof Response ? result.clone() : result;
    },
    locator() {
      return {
        filter() { return this; },
        locator() { return this; },
        async isVisible() {
          const result = visible[Math.min(badge, visible.length - 1)] ?? true;
          badge += 1;
          return result;
        },
      };
    },
  };
}

const source = { source: 'demo-ci-source', status: 'UNHEALTHY' };
const healthUrl = 'http://127.0.0.1/api/v1/sources/health';
const snapshot = unhealthySources => jsonResponse(200, unhealthySources);
const wait = (page, options = {}) => waitForUnhealthySource(page, {
  healthUrl,
  fetchImpl: page.fetchImpl.bind(page),
  ...options,
});

test('waits for a delayed real unhealthy source and its rendered badge', async () => {
  const page = fakePage([snapshot([]), snapshot([source])], [true]);
  const result = await wait(page, { timeoutMs: 250, pollIntervalMs: 1 });
  assert.deepEqual(result, { sources: [source], selected: source });
  assert.equal(page.pollCount, 2);
});

test('rechecks source health when its rendered badge has not appeared yet', async () => {
  const page = fakePage([snapshot([source]), snapshot([source])], [false, true]);
  const result = await wait(page, { timeoutMs: 250, pollIntervalMs: 1 });
  assert.equal(result.selected.source, source.source);
  assert.equal(page.pollCount, 2);
});

test('refreshes one stale dashboard page before accepting its real badge', async () => {
  const page = fakePage([snapshot([source]), snapshot([source])], [false, true]);
  let refreshes = 0;
  const result = await wait(page, {
    timeoutMs: 250,
    pollIntervalMs: 1,
    refreshPage: async remainingMs => {
      assert.ok(remainingMs > 0);
      refreshes += 1;
    },
  });
  assert.equal(result.selected.source, source.source);
  assert.equal(refreshes, 1);
});

test('fails with the last valid response when no unhealthy source appears', async () => {
  const page = fakePage([snapshot([])]);
  await assert.rejects(
    wait(page, { timeoutMs: 15, pollIntervalMs: 1 }),
    error => {
      assert.deepEqual(error.healthSnapshot, {
        httpStatus: 200, error: null, unhealthySources: [], lastUiError: null,
      });
      return true;
    },
  );
});

test('fails closed on malformed health data', async () => {
  const page = fakePage([jsonResponse(200, { items: [] })]);
  await assert.rejects(
    wait(page, { timeoutMs: 10, pollIntervalMs: 1 }),
    error => error.healthSnapshot.error === 'health endpoint returned an invalid source list',
  );
});

test('fails closed on invalid JSON', async () => {
  const page = fakePage([new Response('{invalid', { status: 200 })]);
  await assert.rejects(
    wait(page, { timeoutMs: 10, pollIntervalMs: 1 }),
    error => error.healthSnapshot.error === 'health endpoint returned invalid JSON',
  );
});

test('fails closed on unsuccessful health responses', async () => {
  const page = fakePage([jsonResponse(503, { error: 'unavailable' })]);
  await assert.rejects(
    wait(page, { timeoutMs: 10, pollIntervalMs: 1 }),
    error => error.healthSnapshot.httpStatus === 503,
  );
});

test('does not index a source when its badge never becomes visible', async () => {
  const page = fakePage([snapshot([source])], [false]);
  await assert.rejects(
    wait(page, { timeoutMs: 10, pollIntervalMs: 1 }),
    error => error.healthSnapshot.unhealthySources[0].source === source.source,
  );
});

test('bounds a slow API request by the remaining observation deadline', async () => {
  const page = fakePage([]);
  page.fetchImpl = async (_url, { signal }) => new Promise((resolve, reject) => {
    const abort = () => reject(signal.reason);
    if (signal.aborted) abort();
    else signal.addEventListener('abort', abort, { once: true });
  });
  const keepAlive = setTimeout(() => {}, 100);
  try {
    await assert.rejects(
      wait(page, { timeoutMs: 20, pollIntervalMs: 1 }),
      error => error.healthSnapshot.error === 'health request timed out',
    );
  } finally {
    clearTimeout(keepAlive);
  }
});
