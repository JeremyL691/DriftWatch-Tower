#!/usr/bin/env node
// Dashboard capture and probe (execution guide, section 7.5).
//
// Drives the cached Playwright Chromium headless shell against a running stack:
//   node scripts/p53-capture.mjs --out DIR --label before|after [--base URL] [--user U --password P]
// For each viewport (320/768/1024/1440) it captures a screenshot, records console errors,
// page-level horizontal overflow and the focused-element outline, and (with --theme) can force
// the light theme through the dashboard's theme switch when one exists.

import { chromium } from 'playwright-core';
import { existsSync, mkdirSync, readdirSync, writeFileSync } from 'node:fs';
import { join } from 'node:path';
import { homedir } from 'node:os';
import { waitForUnhealthySource } from './lib/browser-health.mjs';

const args = process.argv.slice(2);
const get = (name, fallback = null) => {
  const index = args.indexOf(`--${name}`);
  return index >= 0 && args[index + 1] ? args[index + 1] : fallback;
};

const outDir = get('out');
const label = get('label', 'before');
const base = get('base', 'http://127.0.0.1:18080');
const user = get('user') || process.env.DWT_CAPTURE_USER;
const password = get('password') || process.env.DWT_CAPTURE_PASSWORD;
const themes = (get('themes', 'dark')).split(',');
const viewports = [
  { name: '320', width: 320, height: 720 },
  { name: '768', width: 768, height: 900 },
  { name: '1024', width: 1024, height: 900 },
  { name: '1440', width: 1440, height: 1000 },
];

async function scanAccessibility(page) {
  return page.evaluate(async () => {
    if (typeof window.axe === 'undefined') return { ran: false };
    const result = await window.axe.run(document, {
      runOnly: { type: 'tag', values: ['wcag2a', 'wcag2aa'] },
    });
    return {
      ran: true,
      violations: result.violations.map((v) => ({
        id: v.id, impact: v.impact, help: v.help, nodes: v.nodes.length,
        nodeDetails: v.nodes.map((n) => ({
          target: n.target, html: n.html, failureSummary: n.failureSummary,
          checks: [...n.any, ...n.all, ...n.none].map((c) => ({ id: c.id, data: c.data, message: c.message })),
        })),
      })),
      passes: result.passes.length,
    };
  }).catch((error) => ({ ran: false, error: String(error) }));
}

// Resolve the browser portably: an explicit override, then the Playwright cache on macOS or
// Linux, then whatever Playwright itself would find. Nothing is downloaded at capture time.
function resolveChromium() {
  const override = get('browser') || process.env.PLAYWRIGHT_CHROMIUM_PATH;
  if (override) return override;
  const candidates = [
    join(homedir(), 'Library/Caches/ms-playwright'),
    join(homedir(), '.cache/ms-playwright'),
    process.env.PLAYWRIGHT_BROWSERS_PATH || '',
  ].filter(Boolean);
  for (const root of candidates) {
    if (!existsSync(root)) continue;
    for (const entry of readdirSync(root).filter((name) => name.startsWith('chromium')).sort().reverse()) {
      for (const relative of [
        'chrome-headless-shell-mac-arm64/chrome-headless-shell',
        'chrome-headless-shell-mac-x64/chrome-headless-shell',
        'chrome-headless-shell-linux64/chrome-headless-shell',
        'chrome-linux/chrome',
        'chrome-mac/Chromium.app/Contents/MacOS/Chromium',
      ]) {
        const candidate = join(root, entry, relative);
        if (existsSync(candidate)) return candidate;
      }
    }
  }
  return undefined; // let playwright-core use its own resolution
}

const executablePath = resolveChromium();

if (!outDir) {
  console.error('--out DIR is required');
  process.exit(2);
}
mkdirSync(outDir, { recursive: true });

let browser = await chromium.launch({ executablePath });
const report = { label, base, capturedAt: new Date().toISOString(), pages: [] };

for (const theme of themes) {
  for (const viewport of viewports) {
    const context = await browser.newContext({
      viewport: { width: viewport.width, height: viewport.height },
      // Send Basic explicitly: the challenge round-trip is unreliable in the headless shell.
      extraHTTPHeaders: user
        ? { Authorization: 'Basic ' + Buffer.from(`${user}:${password}`).toString('base64') }
        : {},
      colorScheme: theme === 'light' ? 'light' : 'dark',
    });
    const page = await context.newPage();
    page.setDefaultNavigationTimeout(30000);
    const mutationTrace = [];
    page.on('response', async response => {
      const request = response.request();
      if (request.method() !== 'POST' || !response.url().includes('/api/v1/')) return;
      const headers = await request.allHeaders();
      const cookie = (headers.cookie || '').match(/(?:^|;\s*)XSRF-TOKEN=([^;]+)/);
      mutationTrace.push({url:response.url(), status:response.status(), csrfHeaderPresent:!!headers['x-xsrf-token'], csrfCookiePresent:!!cookie, csrfMatchesCookie:!!cookie && headers['x-xsrf-token'] === decodeURIComponent(cookie[1]), body:response.status() >= 400 ? await response.text() : undefined});
      writeFileSync(join(outDir, `${label}-mutation-trace.json`), JSON.stringify(mutationTrace, null, 2));
    });
    const consoleErrors = [];
    page.on('console', message => {
      if (message.type() === 'error') consoleErrors.push(message.text());
    });
    page.on('pageerror', error => consoleErrors.push(String(error)));
    // A denied subresource is a defect, and its URL is the only thing that makes it diagnosable.
    const failedResponses = [];
    page.on('response', response => {
      if (response.status() >= 400) failedResponses.push(`${response.status()} ${response.url()}`);
    });

    const response = await page.goto(`${base}/dashboard`, { waitUntil: 'domcontentloaded', timeout: 30000 });
    const status = response ? response.status() : 0;

    // Readiness is a named condition, not a heuristic: the page has rendered its shell and the
    // dashboard has finished its initial load (the loading placeholders are gone). A quiet-network
    // heuristic would either race a slow first paint or hang on a long-poll fallback transport.
    await page.waitForSelector('#wsStatus', { timeout: 20000 }).catch(() => {});
    await page
      .waitForFunction(() => !document.querySelector('.is-loading, [data-loading="true"]'), { timeout: 20000 })
      .catch(() => {});
    await page.waitForTimeout(1200);

    // Select the theme explicitly rather than clicking the switch. One click is not equivalent:
    // on a page whose system preference is already light, the switch flips it to dark, so a
    // "light" capture would silently measure dark and pass. Setting the attribute is exactly what
    // the dashboard's own stored preference does.
    await page.evaluate((value) => { document.documentElement.dataset.theme = value; }, theme);
    await page.waitForTimeout(300);
    const appliedTheme = await page.evaluate(() => document.documentElement.dataset.theme);
    if (appliedTheme !== theme) {
      throw new Error(`theme ${theme} did not apply (page reports ${appliedTheme})`);
    }

    // Automated accessibility scan (guide 7.5): axe runs in the page against the vendored copy,
    // and the report carries the violations so the gate can fail on them.
    const accessibility = await scanAccessibility(page);

    const file = join(outDir, `${label}-${viewport.name}-${theme}.png`);
    await page.screenshot({ path: file, fullPage: true });

    // The live-update path must actually connect (ticket handshake), not stay disconnected.
    // The element's class is the semantic signal; the label is only what a human reads.
    await page.waitForTimeout(1500);
    const wsStatus = await page.evaluate(() => {
      const el = document.getElementById('wsStatus');
      if (!el) return { label: null, state: 'missing' };
      const label = el.querySelector('.ws-label');
      return {
        label: label ? label.textContent.trim() : null,
        state: el.classList.contains('connected') ? 'connected'
          : el.classList.contains('disconnected') ? 'disconnected' : 'unknown',
      };
    });

    const overflow = await page.evaluate(() => ({
      scrollWidth: document.documentElement.scrollWidth,
      clientWidth: document.documentElement.clientWidth,
      bodyScrollWidth: document.body.scrollWidth,
    }));

    // Keyboard probe: focus the first interactive control and read its outline.
    const keyboard = await page.evaluate(() => {
      const focusable = document.querySelector('button, a[href], input, select, [tabindex]:not([tabindex="-1"])');
      if (!focusable) return { focusable: false };
      focusable.focus();
      const style = window.getComputedStyle(focusable);
      return {
        focusable: true,
        tag: focusable.tagName,
        outlineWidth: style.outlineWidth,
        outlineStyle: style.outlineStyle,
        activeElement: document.activeElement ? document.activeElement.tagName : null,
      };
    });

    if (get('exercise-actions') === 'true' && viewport.name === '1440' && theme === 'dark') {
      // Actual demo production and incident operation, scoped to demo data only.
      await page.locator('[data-target="demos"]').click();
      const scenarioResponse = page.waitForResponse(r => r.url().includes('/demo/run-scenario/mixed-incident') && r.request().method() === 'POST');
      await page.locator('[data-scenario="mixed-incident"]').click();
      const produced = await scenarioResponse;
      await page.waitForTimeout(200);
      if (produced.status() !== 202) {
        const body = await produced.text();
        writeFileSync(join(outDir, `${label}-demo-failure.json`), JSON.stringify({status:produced.status(), body}, null, 2));
        throw new Error(`dashboard demo did not accept: ${produced.status()} ${body}`);
      }
      const scenario = await produced.json();
      try {
        const deadline = Date.now() + 60000;
        while (true) {
          const ready = await page.evaluate(async () => {
            const rows = await fetch('/api/v1/alerts?status=OPEN').then(r => r.json());
            return rows.some(a => a.source.includes('demo') && a.severity !== 'INFO' && a.alert_type !== 'STALE_SOURCE');
          });
          if (ready) break;
          if (Date.now() >= deadline) throw new Error('demo alert did not persist within 60 seconds');
          await page.waitForTimeout(250);
        }
      } finally {
        const observed = await page.evaluate(async () => ({
          alerts: await fetch('/api/v1/alerts').then(r => r.json()),
          incidents: await fetch('/api/v1/incidents').then(r => r.json()),
        }));
        writeFileSync(join(outDir, `${label}-action-input.json`), JSON.stringify({scenario, observed}, null, 2));
      }
      const action = await page.evaluate(async () => {
        const rows = await fetch('/api/v1/alerts').then(r => r.json());
        const alert = rows.find(a => a.status === 'OPEN' && a.source.includes('demo') && a.severity !== 'INFO' && a.alert_type !== 'STALE_SOURCE');
        if (!alert) throw new Error('no demo alert available for action');
        const token = document.cookie.split('; ').find(c => c.startsWith('XSRF-TOKEN='));
        const headers = { 'Content-Type': 'application/json' };
        if (token) headers['X-XSRF-TOKEN'] = decodeURIComponent(token.slice('XSRF-TOKEN='.length));
        const response = await fetch(`/api/v1/alerts/${alert.id}/acknowledge`, {method:'POST', headers, body:JSON.stringify({acknowledgedBy:'release acceptance'})});
        const acknowledged = await response.json();
        return {alert, acknowledged, acknowledgeStatus:response.status, csrfTokenPresent:!!token};
      });
      writeFileSync(join(outDir, `${label}-acknowledge-result.json`), JSON.stringify(action, null, 2));
      await page.waitForTimeout(200);
      if (action.acknowledgeStatus !== 200 || action.acknowledged.status !== 'ACKNOWLEDGED') throw new Error(`acknowledge failed: ${action.acknowledgeStatus}`);
      console.log('action: acknowledged, reload');
      await page.reload({waitUntil:'domcontentloaded'});
      await page.locator('[data-target="incidents"]').click();
      const rows = page.locator('#incidentRows tr').filter({hasText:action.alert.source});
      const resolve = rows.locator('[data-resolve-incident]').first();
      await resolve.waitFor({state:'visible',timeout:20000});
      const incidentId = await resolve.getAttribute('data-resolve-incident');
      console.log('action: resolving incident', incidentId);
      const resolveResponse = page.waitForResponse(r => r.url().includes(`/incidents/${incidentId}/resolve`) && r.request().method() === 'POST');
      await resolve.click();
      const resolvedResponse = await resolveResponse;
      const resolveBody = await resolvedResponse.text();
      writeFileSync(join(outDir, `${label}-action-result.json`), JSON.stringify({action, incidentId, resolveStatus: resolvedResponse.status(), resolveBody}, null, 2));
      await page.waitForTimeout(200);
      if (resolvedResponse.status() !== 200) throw new Error(`dashboard resolve failed: ${resolvedResponse.status()} ${resolveBody}`);
      const resolveDeadline = Date.now() + 20000;
      while (true) {
        const resolved = await page.evaluate(async id => {
          const rows = await fetch('/api/v1/incidents').then(r => r.json());
          return rows.some(row => String(row.id) === id && row.status === 'RESOLVED');
        }, incidentId);
        if (resolved) break;
        if (Date.now() >= resolveDeadline) throw new Error('incident resolution did not persist');
        await page.waitForTimeout(250);
      }
      report.actions = {scenario, ...action, incidentId, resolved:true};
    }

    report.pages.push({
      viewport: viewport.name, theme, file, status, wsStatus,
      horizontalOverflow: overflow.scrollWidth > overflow.clientWidth,
      overflow, consoleErrors, failedResponses, keyboard, accessibility,
    });
    await context.close();
  }
}

// Healthy initial data can hide a contrast defect on a status variant that appears only after
// source-health calculations settle. Verify the real API state and scan its rendered badge at every
// required viewport and theme after the actual demo incident has been produced.
if (get('exercise-actions') === 'true' && report.actions?.resolved) {
  const unhealthyPages = [];
  // Release the initial page contexts and their polling sockets before the status-variant pass.
  // A fresh browser process keeps long full-page captures from starving the live health table.
  await browser.close();
  browser = await chromium.launch({ executablePath });
  for (const theme of themes) {
    const context = await browser.newContext({
      viewport: { width: 1440, height: 1000 },
      extraHTTPHeaders: user
        ? { Authorization: 'Basic ' + Buffer.from(`${user}:${password}`).toString('base64') }
        : {},
      colorScheme: theme === 'light' ? 'light' : 'dark',
    });
    await context.addCookies([{ name: 'dwt-theme', value: theme, url: base }]);

    for (const viewport of viewports) {
      const page = await context.newPage();
      page.setDefaultNavigationTimeout(30000);
      const consoleErrors = [];
      const failedResponses = [];
      page.on('console', message => {
        if (message.type() === 'error') consoleErrors.push(message.text());
      });
      page.on('pageerror', error => consoleErrors.push(String(error)));
      page.on('response', response => {
        if (response.status() >= 400) failedResponses.push(`${response.status()} ${response.url()}`);
      });

      await page.setViewportSize({ width: viewport.width, height: viewport.height });
      const response = await page.goto(`${base}/dashboard`, { waitUntil: 'domcontentloaded', timeout: 30000 });
      await page.waitForSelector('#wsStatus', { timeout: 20000 });
      await page.waitForFunction(expected => document.documentElement.dataset.theme === expected, theme, { timeout: 10000 });
      let unhealthyObservation;
      try {
        await page.waitForFunction(
          () => document.getElementById('wsStatus')?.classList.contains('connected'),
          null,
          { timeout: 20000 },
        );
        await page.locator('[data-target="health"]').click({ timeout: 10000 });
        unhealthyObservation = await waitForUnhealthySource(page, {
          healthUrl: new URL('/api/v1/sources/health', base).href,
          headers: user
            ? { Authorization: 'Basic ' + Buffer.from(`${user}:${password}`).toString('base64') }
            : {},
          refreshPage: async remainingMs => {
            const refreshDeadline = Date.now() + remainingMs;
            const remainingTimeout = () => Math.max(1, refreshDeadline - Date.now());
            await page.reload({ waitUntil: 'domcontentloaded', timeout: remainingTimeout() });
            await page.waitForSelector('#wsStatus', { timeout: remainingTimeout() });
            await page.waitForFunction(
              () => document.getElementById('wsStatus')?.classList.contains('connected'),
              null,
              { timeout: remainingTimeout() },
            );
            await page.locator('[data-target="health"]').click({ timeout: remainingTimeout() });
          },
          timeoutMs: 120000,
          pollIntervalMs: 250,
        });
      } catch (error) {
        const redact = value => {
          let text = String(value);
          for (const secret of [user, password]) {
            if (secret) text = text.split(secret).join('[REDACTED]');
          }
          return text;
        };
        const healthSnapshot = error.healthSnapshot
          ? {
              ...error.healthSnapshot,
              error: error.healthSnapshot.error ? redact(error.healthSnapshot.error) : null,
              lastUiError: error.healthSnapshot.lastUiError ? redact(error.healthSnapshot.lastUiError) : null,
              unhealthySources: (error.healthSnapshot.unhealthySources || []).map(source => ({
                source: redact(source.source),
                status: redact(source.status),
              })),
          }
          : null;
        const sourceTable = await page.locator('#sourceHealthTable').innerText({ timeout: 1000 })
          .then(text => redact(text.slice(0, 2000)))
          .catch(() => null);
        writeFileSync(join(outDir, `${label}-unhealthy-state-failure-${viewport.name}-${theme}.json`),
          JSON.stringify({
            error: redact(error),
            healthSnapshot,
            sourceTable,
            consoleErrors: consoleErrors.map(redact),
            failedResponses: failedResponses.map(redact),
          }, null, 2));
        await page.screenshot({
          path: join(outDir, `${label}-unhealthy-state-failure-${viewport.name}-${theme}.png`), fullPage: true,
        }).catch(() => {});
        throw new Error(`real unhealthy source state was not observed at ${viewport.name}-${theme}: ${error}`);
      }
      const { sources: unhealthySources, selected: unhealthySource } = unhealthyObservation;
      const sourceRow = page.locator('#sourceHealthTable tbody tr').filter({ hasText: unhealthySource.source });
      const unhealthyBadge = sourceRow.locator('.pill.unhealthy');
      await unhealthyBadge.waitFor({ state: 'visible', timeout: 20000 });
      const badges = await unhealthyBadge.allTextContents();
      await page.waitForFunction(() => document.getElementById('wsStatus')?.classList.contains('connected'), null, { timeout: 20000 });
      const wsStatus = await page.evaluate(() => ({
        state: document.getElementById('wsStatus')?.classList.contains('connected') ? 'connected' : 'disconnected',
        label: document.querySelector('#wsStatus .ws-label')?.textContent.trim() || null,
      }));
      const overflow = await page.evaluate(() => ({
        scrollWidth: document.documentElement.scrollWidth,
        clientWidth: document.documentElement.clientWidth,
      }));
      const keyboard = await page.evaluate(() => {
        const focusable = document.querySelector('button, a[href], input, select, [tabindex]:not([tabindex="-1"])');
        if (!focusable) return { focusable: false };
        focusable.focus();
        const style = window.getComputedStyle(focusable);
        return {
          focusable: true,
          tag: focusable.tagName,
          outlineWidth: style.outlineWidth,
          outlineStyle: style.outlineStyle,
          activeElement: document.activeElement ? document.activeElement.tagName : null,
        };
      });
      const accessibility = await scanAccessibility(page);
      const file = join(outDir, `${label}-unhealthy-${viewport.name}-${theme}.png`);
      await page.screenshot({ path: file, fullPage: true });
      const entry = {
        viewport: viewport.name,
        theme,
        state: 'UNHEALTHY_SOURCE',
        status: response?.status() || 0,
        consoleErrors,
        failedResponses,
        wsStatus,
        horizontalOverflow: overflow.scrollWidth > overflow.clientWidth,
        overflow,
        keyboard,
        unhealthySources,
        unhealthyBadge: { present: badges.length > 0, source: unhealthySource.source, labels: badges.map(value => value.trim()) },
        accessibility,
        file,
      };
      unhealthyPages.push(entry);
      report.pages.push(entry);
      await page.close();
    }
    await context.close();
  }
  report.unhealthyStateCoverage = {
    scenario: report.actions.scenario?.name || 'mixed-incident',
    observedApiState: 'UNHEALTHY',
    captures: unhealthyPages.map(({ viewport, theme }) => `${viewport}-${theme}`),
    sources: [...new Set(unhealthyPages.flatMap(page => page.unhealthySources.map(source => source.source)))],
  };
}

await browser.close();
writeFileSync(join(outDir, `${label}-report.json`), JSON.stringify(report, null, 2));
const failures = report.pages.filter(p => p.status !== 200 || p.consoleErrors.length > 0 || p.horizontalOverflow);
console.log(JSON.stringify({ label, pages: report.pages.length, problems: failures.map(f => ({
  page: `${f.viewport}-${f.theme}`, status: f.status, consoleErrors: f.consoleErrors.length,
  overflow: f.horizontalOverflow })) }, null, 2));
process.exit(failures.length || (get('exercise-actions') === 'true' && !report.actions?.resolved) ? 1 : 0);
