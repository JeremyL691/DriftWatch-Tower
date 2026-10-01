const state = {
  scenarioStartedAt: null,
  lastScenarioName: null,
  stompClient: null,
  wsConnected: false,
};

// ponytail: respects user OS-level reduce-motion; single source of truth for the whole file.
const REDUCED_MOTION = window.matchMedia("(prefers-reduced-motion: reduce)").matches;
// Motion is CSS-only: GSAP is not loaded and every animation call becomes a safe no-op.
const fx = typeof gsap !== "undefined" ? gsap : {
  from: () => {}, to: () => {}, fromTo: () => {}, set: () => {}, killTweensOf: () => {},
  registerPlugin: () => {}, utils: { toArray: () => [] },
};

// All REST controllers live under /api/v1 (WebSocket /ws is registered separately, unversioned).
const API = "/api/v1";

document.addEventListener("DOMContentLoaded", () => {
  renderIcons();
  initTheme();
  document.getElementById("refreshAll").addEventListener("click", () => refreshDashboard());
  document.getElementById("applyAlertFilters").addEventListener("click", () => loadAlerts());
  document.querySelectorAll(".scenario-button").forEach((button) => {
    button.addEventListener("click", () => runScenario(button));
  });
  refreshDashboard();
  connectWebSocket();
  initMotion();
});

// Backoff reconnect: the socket re-authenticates with a fresh ticket and the dashboard
// re-queries the data, so a reconnect never leaves a stale LIVE label on screen.
let wsAttempt = 0;

async function wsTicket() {
  const response = await fetch(`${API}/dashboard/api/ws-ticket`);
  if (!response.ok) {
    throw new Error(`ticket request failed with ${response.status}`);
  }
  return (await response.json()).ticket;
}

async function connectWebSocket() {
  let ticket;
  try {
    ticket = await wsTicket();
  } catch (error) {
    updateWsStatus(false);
    scheduleReconnect();
    return;
  }
  const socket = new SockJS(`/ws?ticket=${encodeURIComponent(ticket)}`);
  state.stompClient = Stomp.over(socket);
  state.stompClient.debug = null;

  state.stompClient.connect({}, () => {
    wsAttempt = 0;
    updateWsStatus(true);
    refreshDashboard();
    state.stompClient.subscribe("/topic/alerts", (message) => {
      const alert = JSON.parse(message.body);
      prependLiveAlert(alert);
      pulseWsDot();
      refreshDashboard();
    });
    state.stompClient.subscribe("/topic/events", (message) => {
      const event = JSON.parse(message.body);
      prependLiveEvent(event);
      pulseWsDot();
    });
  }, () => {
    updateWsStatus(false);
    scheduleReconnect();
  });
}

function scheduleReconnect() {
  wsAttempt = Math.min(wsAttempt + 1, 6);
  const delay = Math.min(1000 * Math.pow(2, wsAttempt - 1), 30000);
  setTimeout(() => {
    connectWebSocket();
    // Re-query after reconnecting instead of trusting whatever is on screen.
    refreshDashboard();
  }, delay);
}

// Locally bundled Lucide icon set; no external icon CDN.
function renderIcons() {
  if (window.lucide && typeof window.lucide.createIcons === "function") {
    window.lucide.createIcons();
  }
}

// Dark stays the brand default; the toggle stores only the preference, never a credential.
function initTheme() {
  const stored = readCookie("dwt-theme");
  if (stored === "light" || stored === "dark") {
    document.documentElement.dataset.theme = stored;
  }
  updateThemeToggle();
  const toggle = document.getElementById("themeToggle");
  if (toggle) {
    toggle.addEventListener("click", () => {
      const current = document.documentElement.dataset.theme
        || (window.matchMedia("(prefers-color-scheme: light)").matches ? "light" : "dark");
      const next = current === "light" ? "dark" : "light";
      document.documentElement.dataset.theme = next;
      document.cookie = `dwt-theme=${next};path=/;max-age=31536000;samesite=lax`;
      updateThemeToggle();
    });
  }
}

function updateThemeToggle() {
  const label = document.getElementById("themeToggleLabel");
  const toggle = document.getElementById("themeToggle");
  if (!label || !toggle) return;
  const current = document.documentElement.dataset.theme
    || (window.matchMedia("(prefers-color-scheme: light)").matches ? "light" : "dark");
  label.textContent = current === "light" ? "Dark" : "Light";
  toggle.setAttribute("aria-pressed", current === "light" ? "true" : "false");
}

function readCookie(name) {
  const match = document.cookie.match(new RegExp(`(?:^|;\\s*)${name}=([^;]+)`));
  return match ? decodeURIComponent(match[1]) : null;
}

function updateWsStatus(connected) {
  state.wsConnected = connected;
  const el = document.getElementById("wsStatus");
  el.className = `ws-status ${connected ? "connected" : "disconnected"}`;
  el.querySelector(".ws-label").textContent = connected ? "Live" : "Disconnected";
}

function prependLiveAlert(alert) {
  const container = document.getElementById("latestAlerts");
  const item = document.createElement("article");
  item.className = "timeline-item new";
  item.innerHTML = `
    <strong>${escapeHtml(alert.alert_type)} · ${escapeHtml(alert.source)}</strong>
    <p>${escapeHtml(alert.message)}</p>
    <p><span class="code-chip">${escapeHtml(alert.event_type)}</span> ${formatDate(alert.created_at)}</p>
  `;
  container.prepend(item);
  slideInTimeline(item);
}

function prependLiveEvent(event) {
  const container = document.getElementById("recentEvents");
  const item = document.createElement("article");
  item.className = "timeline-item new";
  item.innerHTML = `
    <strong>${escapeHtml(event.event_type)} · ${escapeHtml(event.source)}</strong>
    <p>${escapeHtml(event.event_id)} <span class="code-chip">${escapeHtml(event.quality_status)}</span></p>
    <p>${formatDate(event.event_timestamp)}</p>
  `;
  container.prepend(item);
  slideInTimeline(item);
}

async function refreshDashboard() {
  await Promise.all([
    loadSummary(),
    loadAlerts(),
    loadSourceHealth(),
    loadSchemas(),
    loadRecentEvents(),
    loadCoverage(),
    loadIncidents(),
    loadMetricWindows(),
    loadDeadLetters(),
  ]);
}

async function loadIncidents() {
  const rows = await fetchJson(`${API}/incidents`);
  const body = document.getElementById("incidentRows");
  if (!rows.length) {
    body.innerHTML = '<tr><td colspan="6" class="muted">No incidents</td></tr>';
    return;
  }
  body.innerHTML = rows
    .map((incident) => `<tr>
        <td>${incident.id}</td>
        <td>${escapeHtml(incident.title || "")}</td>
        <td>${escapeHtml(incident.source || "")}</td>
        <td><span class="status-chip">${escapeHtml(incident.status || "")}</span></td>
        <td>${formatDate(incident.created_at)}</td>
        <td>${incident.status === "RESOLVED"
          ? '<span class="muted">resolved</span>'
          : `<button class="ghost-button" data-resolve-incident="${incident.id}">Resolve</button>`}</td>
      </tr>`)
    .join("");
  body.querySelectorAll("[data-resolve-incident]").forEach((button) => {
    button.addEventListener("click", () => resolveIncident(button));
  });
}

// Loading, disabled repeat submission and explicit success/failure feedback (guide 7.5).
async function resolveIncident(button) {
  const id = button.getAttribute("data-resolve-incident");
  button.disabled = true;
  button.textContent = "Resolving…";
  try {
    await postJson(`${API}/incidents/${id}/resolve`, { rootCause: "resolved from dashboard" });
    button.textContent = "Resolved";
    await loadIncidents();
  } catch (error) {
    button.disabled = false;
    button.textContent = "Retry resolve";
    showActionError(`Resolve failed: ${error.message}`);
  }
}

async function loadMetricWindows() {
  const rows = await fetchJson(`${API}/metrics/windows?size=20`);
  const body = document.getElementById("metricRows");
  if (!rows.length) {
    body.innerHTML = '<tr><td colspan="5" class="muted">No metric windows yet</td></tr>';
    return;
  }
  body.innerHTML = rows
    .map((row) => `<tr>
        <td>${escapeHtml(row.source)}</td>
        <td>${escapeHtml(row.event_type)}</td>
        <td>${escapeHtml(row.metric_name)}</td>
        <td>${formatDate(row.window_start)}</td>
        <td>${row.metric_value}</td>
      </tr>`)
    .join("");
}

async function loadDeadLetters() {
  const rows = await fetchJson(`${API}/dead-letters?size=20`);
  const body = document.getElementById("deadLetterRows");
  if (!rows.length) {
    body.innerHTML = '<tr><td colspan="6" class="muted">No dead letters</td></tr>';
    return;
  }
  body.innerHTML = rows
    .map((row) => `<tr>
        <td class="code-chip">${escapeHtml(row.diagnostic_id || "")}</td>
        <td>${escapeHtml(row.stage || "")}</td>
        <td>${escapeHtml(row.source || "")}</td>
        <td>${row.attempts}</td>
        <td><span class="status-chip">${escapeHtml(row.status || "")}</span></td>
        <td><button class="ghost-button" data-dead-letter="${row.id}">Detail</button></td>
      </tr>`)
    .join("");
  body.querySelectorAll("[data-dead-letter]").forEach((button) => {
    button.addEventListener("click", () => showDeadLetter(button.getAttribute("data-dead-letter")));
  });
}

async function showDeadLetter(id) {
  const detail = await fetchJson(`${API}/dead-letters/${id}`);
  const panel = document.getElementById("deadLetterDetail");
  panel.hidden = false;
  panel.innerHTML = `
    <div class="subpanel-head">
      <h3>${escapeHtml(detail.diagnostic_id || `Dead letter ${id}`)}</h3>
      <button class="ghost-button" id="replayDeadLetter">Replay</button>
    </div>
    <p class="muted">${escapeHtml(detail.reason || "")}</p>
    <p><span class="code-chip">${escapeHtml(detail.kafka_topic || "")}</span>
       partition ${detail.kafka_partition ?? "-"} offset ${detail.kafka_offset ?? "-"}</p>
    <details><summary>Payload and recovery history</summary>
      <pre>${escapeHtml(JSON.stringify({ payload: detail.payload, replays: detail.replays }, null, 2))}</pre>
    </details>`;
  document.getElementById("replayDeadLetter").addEventListener("click", async (event) => {
    const button = event.currentTarget;
    button.disabled = true;
    button.textContent = "Replaying…";
    try {
      await postJson(`${API}/dead-letters/${id}/replay`, {});
      button.textContent = "Replayed";
      await loadDeadLetters();
      await showDeadLetter(id);
    } catch (error) {
      button.disabled = false;
      button.textContent = "Retry replay";
      showActionError(`Replay failed: ${error.message}`);
    }
  });
}

function showActionError(message) {
  const panel = document.getElementById("deadLetterDetail");
  panel.hidden = false;
  panel.innerHTML = `<p class="action-error">${escapeHtml(message)}</p>`;
}

// Evaluation coverage (guide 5.3): an OK status must not be read as "the window evaluated this".
async function loadCoverage() {
  const body = await fetchJson(`${API}/events/coverage?hours=24`);
  const total = body.total || 0;
  const rows = [
    ["Included in a window", body.included, true],
    ["Expired (past grace)", body.excluded.expired, false],
    ["Future (beyond tolerance)", body.excluded.future, false],
    ["Skipped by mode (bootstrap/replay)", body.excluded.skipped_mode, false],
    ["No window evaluation recorded", body.excluded.no_window_evaluation, false],
    ["Baseline active", body.baseline.applied, true],
    ["Baseline missing (checks skipped)", body.baseline.pending, false],
  ];
  document.getElementById("coverageWindow").textContent = `last ${body.window_hours}h`;
  document.getElementById("coverageRows").innerHTML = rows
    .map(([label, value, good]) => {
      const share = total ? `${((value / total) * 100).toFixed(1)}%` : "–";
      return `<tr class="${good ? "" : "coverage-excluded"}"><td>${escapeHtml(label)}</td>`
        + `<td>${value}</td><td>${share}</td></tr>`;
    })
    .join("");
}

async function loadSummary() {
  const summary = await fetchJson(`${API}/dashboard/api/summary`);
  animateCount(document.getElementById("statTotalEvents"), summary.totalEvents);
  animateCount(document.getElementById("statActiveSources"), summary.activeSources);
  animateCount(document.getElementById("statAlerts24h"), summary.alertsLast24h);
  animateCount(document.getElementById("statUnhealthySources"), summary.unhealthySources);

  const alerts = await fetchJson(`${API}/alerts?size=8`);
  renderLatestAlerts(alerts);
}

async function loadAlerts() {
  const source = document.getElementById("alertSourceFilter").value.trim();
  const type = document.getElementById("alertTypeFilter").value;
  const params = new URLSearchParams({ size: "30" });
  if (source) params.set("source", source);
  if (type) params.set("type", type);
  const alerts = await fetchJson(`${API}/alerts?${params.toString()}`);
  renderAlertsTable(alerts);
}

async function loadSourceHealth() {
  const rows = await fetchJson(`${API}/sources/health`);
  renderSourceHealthTable(rows);
}

async function loadSchemas() {
  const rows = await fetchJson(`${API}/schemas`);
  renderSchemasTable(rows);
}

async function loadRecentEvents() {
  const rows = await fetchJson(`${API}/events/recent?size=8`);
  renderRecentEvents(rows);
}

async function runScenario(button) {
  const scenario = button.dataset.scenario;
  state.scenarioStartedAt = Date.now();
  state.lastScenarioName = scenario;
  setScenarioStatus(`Running ${scenario}...`, "Publishing demo events and waiting for detectors to react.");
  disableScenarioButtons(true);
  try {
    const response = await mutate(`${API}/demo/run-scenario/${scenario}`);
    if (!response.ok) {
        throw new Error(`Scenario request failed with ${response.status}`);
    }
    const result = await response.json();
    setScenarioStatus(
      `Scenario ${result.scenario} accepted`,
      `${result.description} Event ids: ${result.eventIds.join(", ")}`
    );
    await pollAfterScenario();
  } catch (error) {
    setScenarioStatus("Scenario failed", error.message);
  } finally {
    disableScenarioButtons(false);
  }
}

async function pollAfterScenario() {
  for (let attempt = 0; attempt < 8; attempt++) {
    await wait(1200);
    await refreshDashboard();
  }
}

function renderLatestAlerts(alerts) {
  setText("latestAlertsLabel", state.lastScenarioName ? `Recent alerts after ${state.lastScenarioName}` : "Recent alerts");
  const container = document.getElementById("latestAlerts");
  container.innerHTML = alerts.length
    ? alerts.map((alert) => `
      <article class="timeline-item ${isNewAlert(alert) ? "new" : ""}">
        <strong>${escapeHtml(alert.alert_type)} · ${escapeHtml(alert.source)}</strong>
        <p>${escapeHtml(alert.message)}</p>
        <p><span class="code-chip">${escapeHtml(alert.event_type)}</span> ${formatDate(alert.created_at)}</p>
      </article>
    `).join("")
    : `<div class="empty-state">No alerts yet.</div>`;
}

function renderRecentEvents(events) {
  const container = document.getElementById("recentEvents");
  container.innerHTML = events.length
    ? events.map((event) => `
      <article class="timeline-item">
        <strong>${escapeHtml(event.event_type)} · ${escapeHtml(event.source)}</strong>
        <p>${escapeHtml(event.event_id)} <span class="code-chip">${escapeHtml(event.quality_status)}</span></p>
        <p>${formatDate(event.event_timestamp)}</p>
      </article>
    `).join("")
    : `<div class="empty-state">No events ingested yet.</div>`;
}

function renderAlertsTable(alerts) {
  const shell = document.getElementById("alertsTable");
  if (!alerts.length) {
    shell.innerHTML = `<div class="empty-state">No alerts matched the current filters.</div>`;
    return;
  }
  shell.innerHTML = `
    <table>
      <thead>
        <tr>
          <th>Type</th>
          <th>Severity</th>
          <th>Source</th>
          <th>Event Type</th>
          <th>Field</th>
          <th>Message</th>
          <th>Created</th>
          <th>Evidence</th>
        </tr>
      </thead>
      <tbody>
        ${alerts.map((alert) => `
          <tr>
            <td>${renderPill(alert.alert_type, "flagged")}</td>
            <td>${renderSeverity(alert.severity)}</td>
            <td>${escapeHtml(alert.source)}</td>
            <td>${escapeHtml(alert.event_type)}</td>
            <td>${escapeHtml(alert.field_path ?? "-")}</td>
            <td>${escapeHtml(alert.message)}</td>
            <td>${formatDate(alert.created_at)}</td>
            <td><details><summary>View</summary><pre>${escapeHtml(JSON.stringify(alert.evidence, null, 2))}</pre></details></td>
          </tr>
        `).join("")}
      </tbody>
    </table>
  `;
  crossfadePills(shell);
}

function renderSourceHealthTable(rows) {
  const shell = document.getElementById("sourceHealthTable");
  if (!rows.length) {
    shell.innerHTML = `<div class="empty-state">Source health will appear after events are processed.</div>`;
    return;
  }
  shell.innerHTML = `
    <table>
      <thead>
        <tr>
          <th>Source</th>
          <th>Status</th>
          <th>Last Seen</th>
          <th>Events 5m</th>
          <th>Events 1h</th>
          <th>Duplicate Rate</th>
          <th>Late Rate</th>
          <th>Null Rate</th>
          <th>Health Score</th>
        </tr>
      </thead>
      <tbody>
        ${rows.map((row) => `
          <tr>
            <td>${escapeHtml(row.source)}</td>
            <td>${renderStatus(row.status)}</td>
            <td>${formatDate(row.last_seen_at)}</td>
            <td>${formatNumber(row.events_last_5m)}</td>
            <td>${formatNumber(row.events_last_1h)}</td>
            <td>${formatPercent(row.duplicate_rate)}</td>
            <td>${formatPercent(row.late_event_rate)}</td>
            <td>${formatPercent(row.null_rate)}</td>
            <td>${row.health_score.toFixed(1)}</td>
          </tr>
        `).join("")}
      </tbody>
    </table>
  `;
  crossfadePills(shell);
}

function renderSchemasTable(rows) {
  const grouped = new Map();
  rows.forEach((row) => {
    const list = grouped.get(row.event_type) ?? [];
    list.push(row);
    grouped.set(row.event_type, list);
  });

  const items = Array.from(grouped.entries()).map(([eventType, versions]) => {
    const active = versions.find((row) => row.status === "ACTIVE") ?? versions[0];
    const drifting = versions.filter((row) => row.status === "DRIFTING");
    return { eventType, active, versions, drifting };
  });

  const shell = document.getElementById("schemasTable");
  if (!items.length) {
    shell.innerHTML = `<div class="empty-state">Schema baselines appear after the first events for each event type.</div>`;
    return;
  }

  shell.innerHTML = `
    <table>
      <thead>
        <tr>
          <th>Event Type</th>
          <th>Current Schema Hash</th>
          <th>Status</th>
          <th>First Seen</th>
          <th>Last Seen</th>
          <th>Drift History</th>
        </tr>
      </thead>
      <tbody>
        ${items.map((item) => `
          <tr>
            <td>${escapeHtml(item.eventType)}</td>
            <td><span class="code-chip">${escapeHtml(shortHash(item.active.schema_hash))}</span></td>
            <td>${renderPill(item.active.status, item.active.status === "ACTIVE" ? "info" : "warn")}</td>
            <td>${formatDate(item.active.first_seen_at)}</td>
            <td>${formatDate(item.active.last_seen_at)}</td>
            <td>
              <details>
                <summary>${item.versions.length} version(s), ${item.drifting.length} drifting</summary>
                <pre>${escapeHtml(JSON.stringify(item.versions, null, 2))}</pre>
              </details>
            </td>
          </tr>
        `).join("")}
      </tbody>
    </table>
  `;
}

function renderStatus(status) {
  const tone = status === "HEALTHY" ? "healthy" : status === "STALE" ? "stale" : "unhealthy";
  return renderPill(status, tone);
}

function renderSeverity(severity) {
  const tone = severity === "INFO" ? "info" : severity === "WARN" ? "warn" : "error";
  return renderPill(severity, tone);
}

function renderPill(label, tone) {
  return `<span class="pill ${tone}">${escapeHtml(label)}</span>`;
}

function isNewAlert(alert) {
  if (!state.scenarioStartedAt) return false;
  return Date.parse(alert.created_at) >= state.scenarioStartedAt - 1000;
}

function setScenarioStatus(title, detail) {
  const card = document.getElementById("scenarioStatus");
  card.innerHTML = `
    <strong>${escapeHtml(title)}</strong>
    <p>${escapeHtml(detail)}</p>
  `;
  crossfadeIn(card);
}

function disableScenarioButtons(disabled) {
  document.querySelectorAll(".scenario-button").forEach((button) => {
    button.disabled = disabled;
  });
}

function setText(id, value) {
  document.getElementById(id).textContent = value;
}

async function fetchJson(url) {
  const response = await fetch(url);
  if (!response.ok) {
    throw new Error(`Request failed for ${url}: ${response.status}`);
  }
  return response.json();
}

/** POST with the CSRF header; throws on a non-2xx so the caller can offer a retry. */
async function postJson(url, body) {
  const response = await mutate(url, {
    headers: { "Content-Type": "application/json" },
    body: JSON.stringify(body || {}),
  });
  if (!response.ok) {
    throw new Error(`Request failed for ${url}: ${response.status}`);
  }
  return response.status === 204 ? null : response.json();
}

// Browser mutations must carry the CSRF token from the readable XSRF-TOKEN cookie.
// Tokens are never stored in localStorage.
function csrfToken() {
  const match = document.cookie.match(/(?:^|;\s*)XSRF-TOKEN=([^;]+)/);
  return match ? decodeURIComponent(match[1]) : null;
}

async function mutate(url, options = {}) {
  const headers = Object.assign({}, options.headers || {});
  const token = csrfToken();
  if (token) {
    headers["X-XSRF-TOKEN"] = token;
  }
  return fetch(url, Object.assign({}, options, { method: options.method || "POST", headers }));
}

function formatDate(value) {
  if (!value) return "-";
  return new Date(value).toLocaleString();
}

function formatPercent(value) {
  return `${(value * 100).toFixed(1)}%`;
}

function formatNumber(value) {
  return new Intl.NumberFormat().format(value);
}

function shortHash(value) {
  return value ? `${value.slice(0, 12)}...` : "-";
}

function escapeHtml(value) {
  return String(value)
    .replaceAll("&", "&amp;")
    .replaceAll("<", "&lt;")
    .replaceAll(">", "&gt;")
    .replaceAll('"', "&quot;")
    .replaceAll("'", "&#39;");
}

function wait(ms) {
  return new Promise((resolve) => setTimeout(resolve, ms));
}

// ---------------------------------------------------------------------------
// Phase 4: Motion (GSAP) — all 12 animations honor REDUCED_MOTION.
// No parallax, no 3D, no particles (design doc risk section).
// ---------------------------------------------------------------------------

function initMotion() {
  if (typeof gsap !== "undefined") fx.registerPlugin(ScrollTrigger);

  // #1 Entrance stagger — panels fade up as they scroll into view.
  if (!REDUCED_MOTION && typeof gsap !== "undefined") {
    fx.utils.toArray(".panel").forEach((panel) => {
      fx.from(panel, {
        opacity: 0,
        y: 24,
        duration: 0.55,
        ease: "power2.out",
        scrollTrigger: { trigger: panel, start: "top 88%", toggleActions: "play none none none" },
      });
    });
  }

  // #3 Gold scan line on .stat-card hover.
  // ponytail: per-card overlay element, swept via gsap x; uses existing --gold token (no color edit).
  if (!REDUCED_MOTION && typeof gsap !== "undefined") {
    document.querySelectorAll(".stat-card").forEach((card) => {
      const overlay = document.createElement("div");
      overlay.style.cssText =
        "position:absolute;top:0;left:0;height:100%;width:55%;" +
        "background:linear-gradient(90deg,transparent,var(--gold),transparent);" +
        "opacity:0.55;pointer-events:none;transform:translateX(-120%);will-change:transform;";
      card.style.position = "relative";
      card.style.overflow = "hidden";
      card.appendChild(overlay);
      card.addEventListener("mouseenter", () => {
        fx.killTweensOf(overlay);
        fx.fromTo(overlay, { xPercent: -120 }, { xPercent: 220, duration: 0.85, ease: "power2.inOut" });
      });
    });
  }

  // #8 Real-time clock.
  function tickClock() {
    const el = document.getElementById("clock");
    if (el) el.textContent = new Date().toLocaleTimeString([], { hour12: false });
  }
  tickClock();
  setInterval(tickClock, 1000);

  // #9 Scroll-spy nav highlight.
  const railLinks = new Map();
  document.querySelectorAll(".rail-link[data-target]").forEach((link) => {
    railLinks.set(link.dataset.target, link);
  });
  const sectionEls = Array.from(document.querySelectorAll("main.content section[id]"));
  let activeId = null;
  function setActive(id) {
    if (id === activeId) return;
    if (activeId && railLinks.get(activeId)) railLinks.get(activeId).classList.remove("active");
    if (id && railLinks.get(id)) railLinks.get(id).classList.add("active");
    activeId = id;
  }
  if ("IntersectionObserver" in window && sectionEls.length) {
    const ratios = new Map();
    const spy = new IntersectionObserver(
      (entries) => {
        entries.forEach((entry) => {
          ratios.set(entry.target.id, entry.isIntersecting ? entry.intersectionRatio : 0);
        });
        let bestId = null;
        let bestRatio = 0;
        ratios.forEach((ratio, id) => {
          if (ratio > bestRatio) { bestRatio = ratio; bestId = id; }
        });
        setActive(bestId);
      },
      { rootMargin: "-30% 0px -50% 0px", threshold: [0, 0.25, 0.5, 0.75, 1] }
    );
    sectionEls.forEach((s) => spy.observe(s));
  }

  // #10 Scroll progress bar — fixed top strip, gold, width = scroll%.
  const bar = document.createElement("div");
  bar.style.cssText =
    "position:fixed;top:0;left:0;height:2px;width:0;background:var(--gold);z-index:1000;" +
    "pointer-events:none;" + (REDUCED_MOTION ? "" : "transition:width 80ms linear;");
  bar.setAttribute("aria-hidden", "true");
  document.body.appendChild(bar);
  const updateProgress = () => {
    const max = document.documentElement.scrollHeight - window.innerHeight;
    bar.style.width = (max > 0 ? (window.scrollY / max) * 100 : 0) + "%";
  };
  updateProgress();
  window.addEventListener("scroll", updateProgress, { passive: true });
}

// #2 Number count-up.
function animateCount(el, target) {
  if (!el) return;
  const value = Number(target) || 0;
  if (REDUCED_MOTION || typeof gsap === "undefined") {
    el.textContent = formatNumber(value);
    return;
  }
  if (el._countTween) el._countTween.kill();
  const start = parseInt(String(el.textContent || "0").replace(/[^\d-]/g, ""), 10) || 0;
  const state = { v: start };
  el._countTween = fx.to(state, {
    v: value,
    duration: 0.9,
    ease: "power2.out",
    onUpdate: () => { el.textContent = formatNumber(Math.round(state.v)); },
    onComplete: () => { el.textContent = formatNumber(value); },
  });
}

// #4 Scenario status card crossfade.
function crossfadeIn(el) {
  if (!el || REDUCED_MOTION || typeof gsap === "undefined") return;
  fx.from(el, { opacity: 0, y: -6, duration: 0.3, ease: "power2.out" });
}

// #5 Status badge (pill) color crossfade — pills in freshly-rendered tables fade in.
function crossfadePills(container) {
  if (!container || REDUCED_MOTION || typeof gsap === "undefined") return;
  const pills = container.querySelectorAll(".pill");
  if (!pills.length) return;
  fx.from(pills, { opacity: 0, scale: 0.85, duration: 0.35, ease: "power2.out", stagger: 0.015 });
}

// #6 Alert/event slide-in from left + bounce.
function slideInTimeline(item) {
  if (!item || REDUCED_MOTION || typeof gsap === "undefined") return;
  fx.from(item, { x: -40, opacity: 0, duration: 0.5, ease: "back.out(1.4)" });
}

// #7 WebSocket data update pulse — ws-dot scale yoyo on incoming message.
function pulseWsDot() {
  if (REDUCED_MOTION || typeof gsap === "undefined") return;
  const dot = document.querySelector("#wsStatus .ws-dot");
  if (!dot) return;
  fx.fromTo(dot, { scale: 1 }, { scale: 1.6, duration: 0.18, ease: "power2.out", yoyo: true, repeat: 1 });
}
