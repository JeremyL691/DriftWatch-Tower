import assert from 'node:assert/strict';
import test from 'node:test';
import lib from '../../src/main/resources/static/dashboard/console-lib.js';

const VALID_FORM = {
  values: {
    duplicate_window_ms: '300000',
    late_threshold_ms: '300000',
    github_late_threshold_ms: '28800000',
    null_threshold: '0.6',
    null_min_samples: '3',
    anomaly_baseline_windows: '2',
    anomaly_min_history_windows: '1',
    anomaly_ratio: '3.0',
    anomaly_min_count: '5',
    window_size_ms: '60000',
    grace_ms: '600000',
    future_tolerance_ms: '120000',
    state_retention_ms: '900000',
    format_patterns: 'sku=^SKU-[0-9]{6}$',
    github_enabled: false,
    github_repository: 'apache/kafka',
    github_poll_interval_ms: '300000',
    github_max_pages: '3',
    github_page_size: '100',
  },
  ranges: [{ field: 'price', min: '0', max: '1000000' }],
};

test('composeAlertParams composes filters and pagination without blank values', () => {
  const params = new URLSearchParams(lib.composeAlertParams({
    source: 'github:apache/kafka', type: 'LATE_EVENT', status: 'OPEN', page: 2, size: 30,
  }));
  assert.equal(params.get('source'), 'github:apache/kafka');
  assert.equal(params.get('type'), 'LATE_EVENT');
  assert.equal(params.get('status'), 'OPEN');
  assert.equal(params.get('page'), '2');
  assert.equal(params.get('size'), '30');

  const bare = new URLSearchParams(lib.composeAlertParams({ source: '  ', page: 0, size: 30 }));
  assert.equal(bare.has('source'), false);
  assert.equal(bare.has('status'), false);
  assert.equal(bare.get('page'), '0');
});

test('parseConfigForm accepts a valid document and couples min history to baseline', () => {
  const result = lib.parseConfigForm(VALID_FORM);
  assert.equal(result.ok, true, JSON.stringify(result.errors));
  assert.equal(result.config.duplicate_window_ms, 300000);
  assert.equal(result.config.null_threshold, 0.6);
  assert.equal(result.config.anomaly_baseline_windows, 2);
  assert.equal(result.config.anomaly_min_history_windows, 2);
  assert.deepEqual(result.config.ranges, { price: { min: 0, max: 1000000 } });
  assert.equal(result.config.github_enabled, false);
});

test('parseConfigForm rejects a legacy lesser min history by coupling, never sending it', () => {
  const form = structuredClone(VALID_FORM);
  form.values.anomaly_min_history_windows = '1';
  const result = lib.parseConfigForm(form);
  assert.equal(result.ok, true);
  assert.equal(result.config.anomaly_min_history_windows, 2);
});

test('parseConfigForm rejects fractional integers and non-finite numbers', () => {
  const fractional = structuredClone(VALID_FORM);
  fractional.values.null_min_samples = '1.5';
  assert.match(lib.parseConfigForm(fractional).errors.null_min_samples, /whole number/);

  const infinite = structuredClone(VALID_FORM);
  infinite.values.anomaly_ratio = 'Infinity';
  assert.match(lib.parseConfigForm(infinite).errors.anomaly_ratio, /finite/);
});

test('parseConfigForm enforces bounds and cross-field relations', () => {
  const pollTooFast = structuredClone(VALID_FORM);
  pollTooFast.values.github_poll_interval_ms = '1000';
  assert.match(lib.parseConfigForm(pollTooFast).errors.github_poll_interval_ms, /between 300000 and 86400000/);

  const duplicateSmall = structuredClone(VALID_FORM);
  duplicateSmall.values.window_size_ms = '400000';
  assert.match(lib.parseConfigForm(duplicateSmall).errors.duplicate_window_ms, /metric window/);

  const retentionSmall = structuredClone(VALID_FORM);
  retentionSmall.values.state_retention_ms = '600000';
  assert.match(lib.parseConfigForm(retentionSmall).errors.state_retention_ms, /at least/);

  const githubLateSmall = structuredClone(VALID_FORM);
  githubLateSmall.values.github_late_threshold_ms = '1000';
  assert.match(lib.parseConfigForm(githubLateSmall).errors.github_late_threshold_ms, /general late threshold/);
});

test('parseConfigForm validates ranges, regex grammar and repository', () => {
  const badRange = structuredClone(VALID_FORM);
  badRange.ranges = [{ field: 'price.amount', min: '10', max: '1' }];
  assert.match(lib.parseConfigForm(badRange).errors['ranges.0.max'], /at least the minimum/);

  const emptyRange = structuredClone(VALID_FORM);
  emptyRange.ranges = [{ field: 'price', min: '', max: '' }];
  assert.match(lib.parseConfigForm(emptyRange).errors['ranges.0.min'], /at least one bound/);

  // Regex syntax is Java's and is judged by the server (400), so the client does not compile it.
  const javaRegexLeftToServer = structuredClone(VALID_FORM);
  javaRegexLeftToServer.values.format_patterns = 'sku=([';
  assert.equal(lib.parseConfigForm(javaRegexLeftToServer).ok, true);

  const badPair = structuredClone(VALID_FORM);
  badPair.values.format_patterns = 'sku';
  assert.match(lib.parseConfigForm(badPair).errors.format_patterns, /field=regex/);

  const badRepo = structuredClone(VALID_FORM);
  badRepo.values.github_repository = 'https://github.com/apache/kafka';
  assert.match(lib.parseConfigForm(badRepo).errors.github_repository, /owner\/name/);
  badRepo.values.github_repository = 'my.org/repo';
  assert.match(lib.parseConfigForm(badRepo).errors.github_repository, /owner\/name/);
  badRepo.values.github_repository = 'owner/' + 'r'.repeat(52);
  assert.match(lib.parseConfigForm(badRepo).errors.github_repository, /at most 57/);
  badRepo.values.github_repository = 'owner/' + 'r'.repeat(51);
  assert.equal(lib.parseConfigForm(badRepo).errors.github_repository, undefined);
});

test('parseConfigForm mirrors the server format grammar for Java regex and blank entries', () => {
  const inlineFlag = structuredClone(VALID_FORM);
  inlineFlag.values.format_patterns = 'sku=(?i)^abc';
  assert.equal(lib.parseConfigForm(inlineFlag).ok, true, JSON.stringify(lib.parseConfigForm(inlineFlag).errors));

  const trailingComma = structuredClone(VALID_FORM);
  trailingComma.values.format_patterns = 'a=x,';
  assert.equal(lib.parseConfigForm(trailingComma).ok, true);

  const emptyRegex = structuredClone(VALID_FORM);
  emptyRegex.values.format_patterns = 'sku=';
  assert.match(lib.parseConfigForm(emptyRegex).errors.format_patterns, /empty/);

  const fiftyWithBlanks = structuredClone(VALID_FORM);
  fiftyWithBlanks.values.format_patterns = `${Array.from({ length: 50 }, (_, i) => `f${i}=x`).join(',')},,`;
  assert.equal(lib.parseConfigForm(fiftyWithBlanks).ok, true);

  const fiftyOne = structuredClone(VALID_FORM);
  fiftyOne.values.format_patterns = Array.from({ length: 51 }, (_, i) => `f${i}=x`).join(',');
  assert.match(lib.parseConfigForm(fiftyOne).errors.format_patterns, /At most 50/);
});

test('parseConfigForm treats prototype-like range names as ordinary fields', () => {
  const constructorRange = structuredClone(VALID_FORM);
  constructorRange.ranges = [{ field: 'constructor', min: '1', max: '' }];
  const result = lib.parseConfigForm(constructorRange);
  assert.equal(result.ok, true, JSON.stringify(result.errors));
  assert.deepEqual(result.config.ranges.constructor, { min: 1, max: null });

  const protoRange = structuredClone(VALID_FORM);
  protoRange.ranges = [{ field: '__proto__', min: '1', max: '' }];
  const protoResult = lib.parseConfigForm(protoRange);
  assert.equal(protoResult.ok, true, JSON.stringify(protoResult.errors));
  assert.deepEqual(JSON.parse(JSON.stringify(protoResult.config.ranges)), JSON.parse('{"__proto__":{"min":1,"max":null}}'));

  const duplicate = structuredClone(VALID_FORM);
  duplicate.ranges = [{ field: 'price', min: '1', max: '' }, { field: 'price', min: '2', max: '' }];
  assert.match(lib.parseConfigForm(duplicate).errors['ranges.1.field'], /Duplicate/);
});

test('saveErrorMessage classifies conflict, validation, auth and unknown failures', () => {
  assert.equal(lib.saveErrorMessage(409, { message: 'stale' }).kind, 'conflict');
  assert.equal(lib.saveErrorMessage(400, {}).kind, 'invalid');
  assert.equal(lib.saveErrorMessage(401, {}).kind, 'auth');
  assert.equal(lib.saveErrorMessage(403, {}).kind, 'forbidden');
  assert.equal(lib.saveErrorMessage(503, {}).kind, 'error');
  assert.match(lib.saveErrorMessage(500, {}).message, /503|500/);
});

test('mergeLoadedState keeps dirty edits and refreshes clean fields', () => {
  const merged = lib.mergeLoadedState(
    { null_threshold: 0.5, late_threshold_ms: 1000, github_repository: 'apache/kafka' },
    ['late_threshold_ms'],
    { null_threshold: 0.6, late_threshold_ms: 4242, github_repository: 'old/repo' },
  );
  assert.equal(merged.null_threshold, 0.5);
  assert.equal(merged.late_threshold_ms, 4242);
  assert.equal(merged.github_repository, 'apache/kafka');
});

test('pending restart fields surface the effective value without claiming live application', () => {
  const state = {
    saved_revision: 4,
    effective_revision: 3,
    live_applied_revision: 3,
    pending_restart_fields: ['github_repository'],
    effective: { github_repository: 'apache/kafka' },
  };
  assert.deepEqual(lib.pendingFields(state), ['github_repository']);
  assert.match(lib.pendingFieldNote('github_repository', state), /Effective now/);
  assert.equal(lib.pendingFieldNote('null_threshold', state), null);
  assert.equal(lib.revisionSummary(state).hybrid, false);
  assert.match(lib.revisionSummary(state).effectiveLabel, /process applied revision 3/);
  assert.equal(lib.revisionSummary({ saved_revision: 1, effective_revision: null, live_applied_revision: 1 }).hybrid, true);
});

test('schemaSyncLabel never equates publication with consumer application', () => {
  const published = lib.schemaSyncLabel({ state: 'PUBLISHED', consumer_application: 'UNCONFIRMED' });
  assert.equal(published.state, 'PUBLISHED');
  assert.match(published.publication, /Published/);
  assert.match(published.application, /unconfirmed/);
  assert.match(lib.schemaSyncLabel({ state: 'UNKNOWN' }).publication, /unknown/);
});

test('collectorViewModel prefers effective disabled over historical status', () => {
  const disabled = lib.collectorViewModel({ status: 'LOST', open_gaps: 22, recent_gaps: new Array(20).fill({}) },
    { github_enabled: false, github_repository: 'apache/kafka', github_poll_interval_ms: 300000 });
  assert.equal(disabled.enabled, false);
  assert.equal(disabled.historicalStatus, 'LOST');
  assert.match(disabled.headline, /disabled in the effective configuration/);
  assert.match(lib.gapsLabel({ open_gaps: 22, recent_gaps: new Array(20).fill({}) }), /22 open gap\(s\); showing the newest 20/);

  const missing = lib.collectorViewModel(null, { github_enabled: true, github_repository: 'apache/kafka' });
  assert.equal(missing.hasState, false);
  assert.equal(missing.enabled, true);
  assert.match(missing.headline, /No persisted poll state/);
});

test('replay and retention labels are truthful about partial evidence', () => {
  assert.match(lib.replayOutcomeLabel({ outcome: 'FAILED' }), /^FAILED/);
  assert.match(lib.replayOutcomeLabel({ outcome: 'PUBLISHED' }), /not proof that processing recovered/);
  assert.match(lib.retentionRunLabel({ status: 'completed', pruned: {} }), /nothing needed pruning/);
  assert.match(lib.retentionRunLabel({ status: 'completed', pruned: { raw_events: 4 } }), /raw_events: 4/);
  assert.match(lib.retentionRunLabel(null), /did not report completion/);
});
