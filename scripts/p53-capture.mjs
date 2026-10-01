#!/usr/bin/env node
// Dashboard capture and probe (execution guide, section 7.5).
//
// Drives the cached Playwright Chromium headless shell against a running stack:
//   node scripts/p53-capture.mjs --out DIR --label before|after [--base URL] [--user U --password P]
// For each viewport (320/768/1024/1440) it captures a screenshot, records console errors,
// page-level horizontal overflow and the focused-element outline, and (with --theme) can force
// the light theme through the dashboard's theme switch when one exists.

import { chromium } from 'playwright-core';
import { mkdirSync, writeFileSync } from 'node:fs';
import { join } from 'node:path';
import { homedir } from 'node:os';

const args = process.argv.slice(2);
const get = (name, fallback = null) => {
  const index = args.indexOf(`--${name}`);
  return index >= 0 && args[index + 1] ? args[index + 1] : fallback;
};

const outDir = get('out');
const label = get('label', 'before');
const base = get('base', 'http://127.0.0.1:18080');
const user = get('user');
const password = get('password');
const themes = (get('themes', 'dark')).split(',');
const viewports = [
  { name: '320', width: 320, height: 720 },
  { name: '768', width: 768, height: 900 },
  { name: '1024', width: 1024, height: 900 },
  { name: '1440', width: 1440, height: 1000 },
];
const executablePath = join(homedir(),
  'Library/Caches/ms-playwright/chromium_headless_shell-1223/chrome-headless-shell-mac-arm64/chrome-headless-shell');

if (!outDir) {
  console.error('--out DIR is required');
  process.exit(2);
}
mkdirSync(outDir, { recursive: true });

const browser = await chromium.launch({ executablePath });
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
    const consoleErrors = [];
    page.on('console', message => {
      if (message.type() === 'error') consoleErrors.push(message.text());
    });
    page.on('pageerror', error => consoleErrors.push(String(error)));

    const response = await page.goto(`${base}/dashboard`, { waitUntil: 'networkidle', timeout: 30000 });
    const status = response ? response.status() : 0;

    if (theme === 'light') {
      // Use the dashboard's own switch when it exists; the CSS token set is what matters here.
      const toggle = page.locator('[data-theme-toggle], #themeToggle, button:has-text("Light")').first();
      if (await toggle.count() > 0) {
        await toggle.click({ timeout: 3000 }).catch(() => {});
        await page.waitForTimeout(300);
      } else {
        await page.emulateMedia({ colorScheme: 'light' });
      }
    }

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

    report.pages.push({
      viewport: viewport.name, theme, file, status, wsStatus,
      horizontalOverflow: overflow.scrollWidth > overflow.clientWidth,
      overflow, consoleErrors, keyboard,
    });
    await context.close();
  }
}

await browser.close();
writeFileSync(join(outDir, `${label}-report.json`), JSON.stringify(report, null, 2));
const failures = report.pages.filter(p => p.status !== 200 || p.consoleErrors.length > 0 || p.horizontalOverflow);
console.log(JSON.stringify({ label, pages: report.pages.length, problems: failures.map(f => ({
  page: `${f.viewport}-${f.theme}`, status: f.status, consoleErrors: f.consoleErrors.length,
  overflow: f.horizontalOverflow })) }, null, 2));
process.exit(0);
