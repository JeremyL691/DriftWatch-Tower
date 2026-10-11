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
  document.getElementById("applyAlertFilters").addEventListener("click", () => {
    alertPaging.page = 0;
    loadAlerts();
  });
  document.getElementById("alertsPrev").addEventListener("click", () => {
    if (alertPaging.page > 0) {
      alertPaging.page -= 1;
      loadAlerts();
    }
  });
  document.getElementById("alertsNext").addEventListener("click", () => {
    alertPaging.page += 1;
    loadAlerts();
  });
  document.getElementById("refreshCollector").addEventListener("click", () => loadCollectorOps());
  document.getElementById("refreshRetention").addEventListener("click", () => loadRetentionStatus());
  document.getElementById("retentionRun").addEventListener("click", () => toggleRetentionConfirm(true));
  document.getElementById("retentionCancel").addEventListener("click", () => toggleRetentionConfirm(false));
  document.getElementById("retentionConfirm").addEventListener("click", () => runRetention());
  document.getElementById("recentEvents").addEventListener("click", (event) => {
    const button = event.target.closest("[data-event-detail]");
    if (button) showEventDetail(button.getAttribute("data-event-detail"));
  });
  document.getElementById("schemasTable").addEventListener("click", (event) => {
    const button = event.target.closest("[data-schema-manage]");
    if (button) openSchemaPanel(button.getAttribute("data-schema-manage"));
  });
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
  const tasks = [
    loadSummary(),
    loadAlerts(),
    loadSourceHealth(),
    loadCollectorOps(),
    loadSchemas(),
    loadRecentEvents(),
    loadCoverage(),
    loadIncidents(),
    loadMetricWindows(),
    loadDeadLetters(),
    loadRetentionStatus(),
  ];
  // The configuration editor keeps unsaved edits across every refresh cycle.
  if (window.DwtConfig && typeof window.DwtConfig.refresh === "function") {
    tasks.push(window.DwtConfig.refresh({ keepEdits: true }));
  }
  await Promise.all(tasks);
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
        <td>${escapeHtml(incident.id)}</td>
        <td>${escapeHtml(incident.title || "")}</td>
        <td>${escapeHtml(incident.source || "")}</td>
        <td><span class="status-chip">${escapeHtml(incident.status || "")}</span></td>
        <td>${formatDate(incident.created_at)}</td>
        <td>${incident.status === "RESOLVED"
          ? '<span class="muted">resolved</span>'
          : `<button class="ghost-button" data-resolve-incident="${escapeHtml(incident.id)}">Resolve</button>`}</td>
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
    setStatus("incidentStatus", `Incident ${id} resolved. Re-querying incidents and alerts.`);
    await Promise.all([loadIncidents(), loadAlerts()]);
  } catch (error) {
    button.disabled = false;
    button.textContent = "Retry resolve";
    setStatus("incidentStatus", `Incident ${id} resolve failed: ${error.message}`, true);
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
        <td>${escapeHtml(row.metric_value)}</td>
      </tr>`)
    .join("");
}

// Retention: policy and counters first; the manual run is an explicit two-step action and
// reports the real response instead of assuming completion.
async function loadRetentionStatus() {
  const container = document.getElementById("retentionSummary");
  try {
    const status = await fetchJson(`${API}/operations/retention`);
    container.innerHTML = kvGrid([
      ["Raw events retention", `${status.raw_retention_days} day(s)`],
      ["Metric retention", `${status.metric_retention_days} day(s)`],
      ["Resolved alerts retention", `${status.resolved_alert_retention_days} day(s)`],
      ["Completed dead letters retention", `${status.completed_dead_letter_retention_days} day(s)`],
      ["Inbox identity retention", `${status.inbox_identity_retention_days} day(s)`],
      ["Delete batch size", status.batch_size],
      ["Unresolved alerts", status.unresolved_alerts],
      ["Open incidents", status.open_incidents],
      ["Open dead letters", status.open_dead_letters],
      ["Pending source outbox", status.pending_source_outbox],
      ["Pending baseline outbox", status.pending_baseline_outbox],
      ["Collector states", status.collector_states],
      ["Schema versions", status.schema_versions],
    ]);
  } catch (error) {
    container.innerHTML = `<p class="action-error">Retention status failed: ${escapeHtml(error.message)}</p>`;
  }
}

function toggleRetentionConfirm(show) {
  document.getElementById("retentionRun").hidden = show;
  document.getElementById("retentionConfirm").hidden = !show;
  document.getElementById("retentionCancel").hidden = !show;
  if (show) document.getElementById("retentionConfirm").focus();
}

async function runRetention() {
  const confirm = document.getElementById("retentionConfirm");
  confirm.disabled = true;
  setStatus("retentionStatus", "Running retention...");
  try {
    const response = await requestJson(`${API}/operations/retention/run`, {
      headers: { "Content-Type": "application/json" },
      body: JSON.stringify({}),
    });
    if (!response.ok) throw new Error(describeHttpError(response));
    const completed = response.body && response.body.status === "completed";
    setStatus("retentionStatus", window.DwtConsole.retentionRunLabel(response.body), !completed);
    await loadRetentionStatus();
  } catch (error) {
    setStatus("retentionStatus", `Retention run failed: ${error.message}. Nothing was reported as completed.`, true);
  } finally {
    confirm.disabled = false;
    toggleRetentionConfirm(false);
  }
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
        <td>${escapeHtml(row.attempts)}</td>
        <td><span class="status-chip">${escapeHtml(row.status || "")}</span></td>
        <td><button class="ghost-button" data-dead-letter="${escapeHtml(row.id)}">Detail</button></td>
      </tr>`)
    .join("");
  body.querySelectorAll("[data-dead-letter]").forEach((button) => {
    button.addEventListener("click", () => showDeadLetter(button.getAttribute("data-dead-letter")));
  });
}

async function showDeadLetter(id) {
  const panel = document.getElementById("deadLetterDetail");
  panel.hidden = false;
  panel.innerHTML = `<p class="muted">Loading dead letter ${escapeHtml(id)}...</p>`;
  try {
    const detail = await fetchJson(`${API}/dead-letters/${encodeURIComponent(id)}`);
    const kafka = detail.kafka || {};
    const history = Array.isArray(detail.replay_history) ? detail.replay_history : [];
    panel.innerHTML = `
      <div class="subpanel-head">
        <h3>${escapeHtml(detail.diagnostic_id || `Dead letter ${id}`)} <span class="status-chip">${escapeHtml(detail.status || "")}</span></h3>
        <button class="ghost-button" type="button" id="replayDeadLetter">Replay</button>
        <button class="ghost-button" type="button" id="replayConfirm" hidden>Confirm replay</button>
        <button class="ghost-button" type="button" id="replayCancel" hidden>Cancel</button>
      </div>
      <p class="muted">${escapeHtml(detail.reason || "")}</p>
      ${kvGrid([
        ["Stage", detail.stage],
        ["Ingestion id", detail.ingestion_id],
        ["Source", detail.source],
        ["Event type", detail.event_type],
        ["Attempts", detail.attempts],
        ["Topic", kafka.topic],
        ["Partition", kafka.partition],
        ["Offset", kafka.offset],
        ["Created", formatDate(detail.created_at)],
        ["Recovered at", formatDate(detail.recovered_at)],
      ])}
      <p class="muted">An accepted replay or a REPLAYED state proves the record was published again, not that sink processing succeeded. Re-query the outcome, the replay history and the event rows.</p>
      <h4>Replay history</h4>
      ${renderReplayHistory(history)}
      <details><summary>Payload</summary>
        <pre tabindex="0" role="region" aria-label="Dead letter payload">${escapeHtml(JSON.stringify(detail.payload, null, 2))}</pre>
      </details>`;
    const replayButton = document.getElementById("replayDeadLetter");
    const confirmButton = document.getElementById("replayConfirm");
    const cancelButton = document.getElementById("replayCancel");
    replayButton.addEventListener("click", () => {
      replayButton.hidden = true;
      confirmButton.hidden = false;
      cancelButton.hidden = false;
      confirmButton.focus();
    });
    cancelButton.addEventListener("click", () => {
      replayButton.hidden = false;
      confirmButton.hidden = true;
      cancelButton.hidden = true;
      replayButton.focus();
    });
    confirmButton.addEventListener("click", async () => {
      confirmButton.disabled = true;
      setStatus("deadLetterStatus", `Replaying dead letter ${id}...`);
      try {
        const response = await requestJson(`${API}/dead-letters/${encodeURIComponent(id)}/replay`, {
          headers: { "Content-Type": "application/json" },
          body: JSON.stringify({}),
        });
        if (!response.ok) throw new Error(describeHttpError(response));
        const label = window.DwtConsole.replayOutcomeLabel(response.body);
        const outcome = String((response.body && response.body.outcome) || "UNKNOWN").toUpperCase();
        const attempt = response.body && response.body.replay_attempt_id ? response.body.replay_attempt_id : "";
        setStatus("deadLetterStatus", `Replay ${attempt}: ${label}`, outcome === "FAILED" || outcome === "UNKNOWN");
        await loadDeadLetters();
        await showDeadLetter(id);
      } catch (error) {
        setStatus("deadLetterStatus", `Replay failed: ${error.message}`, true);
        confirmButton.disabled = false;
      }
    });
  } catch (error) {
    panel.innerHTML = `<p class="action-error">Failed to load dead letter ${escapeHtml(id)}: ${escapeHtml(error.message)}</p>`;
  }
}

function renderReplayHistory(history) {
  if (!history.length) {
    return `<p class="muted">No replay attempts recorded.</p>`;
  }
  return `<div class="table-shell" tabindex="0" role="region" aria-label="Replay history">
    <table class="data-table">
      <thead><tr><th>Attempt</th><th>Stage</th><th>Outcome</th><th>Detail</th><th>Requested</th></tr></thead>
      <tbody>${history.map((entry) => `<tr>
        <td>${escapeHtml(entry.replay_attempt_id)}</td>
        <td>${escapeHtml(entry.stage)}</td>
        <td><span class="status-chip">${escapeHtml(entry.outcome)}</span></td>
        <td>${escapeHtml(entry.detail)}</td>
        <td>${formatDate(entry.requested_at)}</td>
      </tr>`).join("")}</tbody>
    </table>
  </div>`;
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

const alertPaging = { page: 0, size: 30 };

async function loadAlerts() {
  const source = document.getElementById("alertSourceFilter").value.trim();
  const type = document.getElementById("alertTypeFilter").value;
  const status = document.getElementById("alertStatusFilter").value;
  const params = window.DwtConsole.composeAlertParams({
    source, type, status, page: alertPaging.page, size: alertPaging.size,
  });
  const alerts = await fetchJson(`${API}/alerts?${params}`);
  renderAlertsTable(alerts);
}

async function loadSourceHealth() {
  const rows = await fetchJson(`${API}/sources/health`);
  renderSourceHealthTable(rows);
}

// Collector operations: effective enablement first, stored state second. A disabled collector's
// historical LOST/QUIET row must never be read as a running poller.
async function loadCollectorOps() {
  const headline = document.getElementById("collectorHeadline");
  const summary = document.getElementById("collectorSummary");
  const gaps = document.getElementById("collectorGaps");
  try {
    const collectors = await fetchJson(`${API}/sources/collectors`);
    const server = window.DwtConfig && typeof window.DwtConfig.state === "function"
      ? window.DwtConfig.state() : null;
    const effective = server && server.effective ? server.effective : null;
    const collector = Array.isArray(collectors) && collectors.length ? collectors[0] : null;
    const fallback = collector ? {
      github_enabled: collector.collector_enabled,
      github_repository: null,
      github_poll_interval_ms: collector.poll_interval_ms,
    } : null;
    const view = window.DwtConsole.collectorViewModel(collector, effective || fallback || {});
    headline.textContent = view.headline;
    headline.className = `collector-headline ${view.enabled ? "enabled" : "disabled"}`;
    if (!collector) {
      summary.innerHTML = kvGrid([
        ["Effective enabled", view.enabled ? "yes" : "no"],
        ["Effective repository", view.repository],
        ["Effective poll interval", formatDurationMs(view.interval)],
      ]);
      gaps.innerHTML = "";
      return;
    }
    summary.innerHTML = kvGrid([
      ["Effective enabled", view.enabled ? "yes" : "no"],
      ["Effective repository", view.repository],
      ["Effective poll interval", formatDurationMs(view.interval)],
      ["Stored status", `${collector.status}${view.enabled ? "" : " (historical)"}`],
      ["Last poll", formatDate(collector.last_poll_at)],
      ["Last success", formatDate(collector.last_success_at)],
      ["Next poll", formatDate(collector.next_poll_at)],
      ["Last event", formatDate(collector.last_event_at)],
      ["Upstream lag", collector.upstream_lag_seconds === null || collector.upstream_lag_seconds === undefined
        ? "-" : `${collector.upstream_lag_seconds}s`],
      ["Lost (stored)", collector.lost ? `yes, after ${collector.lost_after_seconds}s` : "no"],
      ["Backoff until", formatDate(collector.backoff_until)],
      ["ETag applied", collector.etag_applied ? "yes" : "no"],
      ["ETag candidate", collector.etag_candidate ? "yes" : "no"],
      ["Pending poll run", collector.pending_poll_run_id],
      ["Observed from", formatDate(collector.observed_from)],
      ["State updated", formatDate(collector.updated_at)],
      ["Pending outbox", collector.pending_outbox],
      ["Open gaps", window.DwtConsole.gapsLabel(collector)],
      ["Last error", collector.last_error],
    ]);
    const recent = Array.isArray(collector.recent_gaps) ? collector.recent_gaps : [];
    gaps.innerHTML = recent.length
      ? `<div class="table-shell" tabindex="0" role="region" aria-label="Recent collector gaps">
          <table class="data-table">
            <thead><tr><th>Poll run</th><th>Reason</th><th>Visible from</th><th>Visible to</th><th>Missing</th><th>Recovery</th><th>Created</th><th>Recovered</th></tr></thead>
            <tbody>${recent.map((gap) => `<tr>
              <td>${escapeHtml(gap.poll_run_id)}</td>
              <td>${escapeHtml(gap.reason)}</td>
              <td>${formatDate(gap.visible_from)}</td>
              <td>${formatDate(gap.visible_to)}</td>
              <td>${escapeHtml(gap.missing_count)}</td>
              <td><span class="status-chip">${escapeHtml(gap.recovery_state)}</span></td>
              <td>${formatDate(gap.created_at)}</td>
              <td>${formatDate(gap.recovered_at)}</td>
            </tr>`).join("")}</tbody>
          </table>
        </div>`
      : `<p class="muted">No recent gaps recorded for this source.</p>`;
  } catch (error) {
    headline.textContent = "Collector status unavailable.";
    headline.className = "collector-headline disabled";
    summary.innerHTML = `<p class="action-error">Collector status failed: ${escapeHtml(error.message)}</p>`;
    gaps.innerHTML = "";
  }
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
        ${event.ingestion_id
          ? `<p><button class="ghost-button small-button" type="button" data-event-detail="${escapeHtml(event.ingestion_id)}">Detail</button></p>`
          : `<p class="muted">No ingestion identity reported for this event.</p>`}
      </article>
    `).join("")
    : `<div class="empty-state">No events ingested yet.</div>`;
}

async function showEventDetail(ingestionId) {
  const panel = document.getElementById("eventDetailPanel");
  panel.hidden = false;
  if (!ingestionId || ingestionId === "undefined") {
    panel.innerHTML = `<div class="subpanel-head"><h3>Event detail</h3>`
      + `<button class="ghost-button" type="button" id="closeEventDetail">Close</button></div>`
      + `<p class="action-error">This summary did not include an ingestion identity, so no detail request was made.</p>`;
    document.getElementById("closeEventDetail").addEventListener("click", () => {
      panel.hidden = true;
      panel.innerHTML = "";
    });
    return;
  }
  panel.innerHTML = `<p class="muted">Loading event ${escapeHtml(ingestionId)}...</p>`;
  try {
    const detail = await fetchJson(`${API}/events/${encodeURIComponent(ingestionId)}`);
    const evaluation = detail.window_evaluation || null;
    const outcome = evaluation && evaluation.outcome ? evaluation.outcome : "NO EVALUATION";
    const alerts = Array.isArray(detail.alerts) ? detail.alerts : [];
    panel.innerHTML = `
      <div class="subpanel-head">
        <h3>Event ${escapeHtml(detail.ingestion_id)}</h3>
        <button class="ghost-button" type="button" id="closeEventDetail">Close</button>
      </div>
      ${kvGrid([
        ["Event id", detail.event_id],
        ["Source", detail.source],
        ["Event type", detail.event_type],
        ["Origin", detail.origin],
        ["Mode", detail.mode],
        ["Quality status", detail.quality_status],
        ["Baseline status", detail.baseline_status],
        ["Event timestamp", formatDate(detail.event_timestamp)],
        ["Received at", formatDate(detail.received_at)],
        ["Window outcome", outcome],
      ])}
      <p class="muted">An OK outcome records that no rule fired in that window; it is not by itself proof that the event fully participated. Baseline-dependent checks are skipped while no active baseline exists.</p>
      <details><summary>Window evaluation</summary><pre tabindex="0" role="region" aria-label="Window evaluation">${escapeHtml(JSON.stringify(evaluation, null, 2))}</pre></details>
      <details><summary>Payload</summary><pre tabindex="0" role="region" aria-label="Event payload">${escapeHtml(JSON.stringify(detail.payload, null, 2))}</pre></details>
      <div class="subpanel-head"><h3>Linked alerts</h3><span class="muted">Matched by ingestion identity, not business event id</span></div>
      ${renderLinkedAlerts(alerts)}`;
    document.getElementById("closeEventDetail").addEventListener("click", () => {
      panel.hidden = true;
      panel.innerHTML = "";
    });
  } catch (error) {
    panel.innerHTML = `<div class="subpanel-head"><h3>Event detail</h3>`
      + `<button class="ghost-button" type="button" id="closeEventDetail">Close</button></div>`
      + `<p class="action-error">Failed to load ${escapeHtml(ingestionId)}: ${escapeHtml(error.message)}</p>`;
    document.getElementById("closeEventDetail").addEventListener("click", () => {
      panel.hidden = true;
      panel.innerHTML = "";
    });
  }
}

function renderLinkedAlerts(alerts) {
  if (!alerts.length) {
    return `<p class="muted">No alerts linked to this ingestion.</p>`;
  }
  return `<div class="table-shell" tabindex="0" role="region" aria-label="Linked alerts">
    <table class="data-table">
      <thead><tr><th>Type</th><th>Severity</th><th>Status</th><th>Message</th><th>Created</th></tr></thead>
      <tbody>${alerts.map((alert) => `<tr>
        <td>${renderPill(alert.alert_type, "flagged")}</td>
        <td>${renderSeverity(alert.severity)}</td>
        <td><span class="status-chip">${escapeHtml(alert.status)}</span></td>
        <td>${escapeHtml(alert.message)}</td>
        <td>${formatDate(alert.created_at)}</td>
      </tr>`).join("")}</tbody>
    </table>
  </div>`;
}

function renderAlertsTable(alerts) {
  const shell = document.getElementById("alertsTable");
  document.getElementById("alertsPageLabel").textContent =
    `Page ${alertPaging.page + 1}; ${alertPaging.size} per page; filters compose with AND`;
  document.getElementById("alertsPrev").disabled = alertPaging.page === 0;
  document.getElementById("alertsNext").disabled = alerts.length < alertPaging.size;
  if (!alerts.length) {
    shell.innerHTML = `<div class="empty-state">No alerts matched the current filters on this page.</div>`;
    return;
  }
  shell.innerHTML = `
    <table class="data-table">
      <thead>
        <tr>
          <th>Type</th>
          <th>Severity</th>
          <th>Status</th>
          <th>Source</th>
          <th>Event Type</th>
          <th>Message</th>
          <th>Created</th>
          <th>Evidence</th>
          <th>Actions</th>
        </tr>
      </thead>
      <tbody>
        ${alerts.map((alert) => `
          <tr>
            <td>${renderPill(alert.alert_type, "flagged")}</td>
            <td>${renderSeverity(alert.severity)}</td>
            <td>${renderAlertStatus(alert)}</td>
            <td>${escapeHtml(alert.source)}</td>
            <td>${escapeHtml(alert.event_type)}</td>
            <td>${escapeHtml(alert.message)}${alert.root_cause ? `<div class="muted">Root cause: ${escapeHtml(alert.root_cause)}</div>` : ""}</td>
            <td>${formatDate(alert.created_at)}</td>
            <td><details><summary>View</summary><pre tabindex="0" role="region" aria-label="Alert evidence">${escapeHtml(JSON.stringify(alert.evidence, null, 2))}</pre></details></td>
            <td>${renderAlertActions(alert)}</td>
          </tr>
        `).join("")}
      </tbody>
    </table>
  `;
  shell.querySelectorAll("[data-alert-action]").forEach((button) => {
    button.addEventListener("click", () => alertAction(button));
  });
  crossfadePills(shell);
}

function renderAlertStatus(alert) {
  const tone = alert.status === "RESOLVED" ? "healthy" : alert.status === "ACKNOWLEDGED" ? "info" : "warn";
  const lifecycle = [];
  if (alert.acknowledged_by || alert.acknowledged_at) {
    lifecycle.push(`ack: ${escapeHtml(alert.acknowledged_by || "operator")} ${formatDate(alert.acknowledged_at)}`);
  }
  if (alert.resolved_at) {
    lifecycle.push(`resolved ${formatDate(alert.resolved_at)}`);
  }
  return `${renderPill(alert.status, tone)}`
    + (lifecycle.length ? `<div class="muted lifecycle">${lifecycle.join("<br>")}</div>` : "");
}

function renderAlertActions(alert) {
  const id = escapeHtml(alert.id);
  if (alert.status === "RESOLVED") {
    return `<span class="muted">No action</span>`;
  }
  const acknowledge = alert.status === "OPEN"
    ? `<button class="ghost-button small-button" type="button" data-alert-action="acknowledge" data-alert-id="${id}">Acknowledge</button>`
    : "";
  return `${acknowledge}<button class="ghost-button small-button" type="button" data-alert-action="resolve" data-alert-id="${id}">Resolve</button>`;
}

// Loading, disabled repeat submission and explicit success/failure feedback (guide 7.5).
// A repeated action is idempotent server-side; 404/409 stay visible and retryable.
async function alertAction(button) {
  const id = button.getAttribute("data-alert-id");
  const action = button.getAttribute("data-alert-action");
  button.disabled = true;
  setStatus("alertsStatus", `Submitting ${action} for alert ${id}...`);
  try {
    const response = await requestJson(`${API}/alerts/${encodeURIComponent(id)}/${action}`, {
      headers: { "Content-Type": "application/json" },
      body: JSON.stringify({}),
    });
    if (response.status === 404) throw new Error("alert no longer exists; refresh the list");
    if (response.status === 409) throw new Error("illegal transition for this alert; refresh the list and retry");
    if (!response.ok) throw new Error(describeHttpError(response));
    const status = response.body && response.body.status ? response.body.status : `${action} applied`;
    setStatus("alertsStatus", `Alert ${id}: ${status}. Re-querying alerts, incidents and summary.`);
    await Promise.all([loadAlerts(), loadIncidents(), loadSummary()]);
  } catch (error) {
    setStatus("alertsStatus", `Alert ${id} ${action} failed: ${error.message}`, true);
  } finally {
    button.disabled = false;
  }
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
    <table class="data-table">
      <thead>
        <tr>
          <th>Event Type</th>
          <th>Current Schema Hash</th>
          <th>Status</th>
          <th>First Seen</th>
          <th>Last Seen</th>
          <th>Drift History</th>
          <th>Manage</th>
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
                <pre tabindex="0" role="region" aria-label="Schema versions for ${escapeHtml(item.eventType)}">${escapeHtml(JSON.stringify(item.versions, null, 2))}</pre>
              </details>
            </td>
            <td><button class="ghost-button small-button" type="button" data-schema-manage="${escapeHtml(item.eventType)}">Manage</button></td>
          </tr>
        `).join("")}
      </tbody>
    </table>
  `;
}

// Version comparison and baseline activation over the real stored versions.
// Publication (PUBLISHED) never implies that Streams applied the baseline.
async function openSchemaPanel(eventType, notice) {
  const panel = document.getElementById("schemaPanel");
  panel.hidden = false;
  panel.innerHTML = `<p class="muted">Loading schema versions for ${escapeHtml(eventType)}...</p>`;
  try {
    const encoded = encodeURIComponent(eventType);
    const versions = await fetchJson(`${API}/schemas/${encoded}`);
    const baselineResponse = await fetch(`${API}/schemas/${encoded}/baseline`);
    let baseline = null;
    if (baselineResponse.ok) {
      baseline = await baselineResponse.json();
    } else if (baselineResponse.status !== 404) {
      throw new Error(`baseline status request failed with ${baselineResponse.status}`);
    }
    renderSchemaPanel(eventType, versions, baseline, notice);
  } catch (error) {
    panel.innerHTML = `<div class="subpanel-head"><h3>Schema: ${escapeHtml(eventType)}</h3>`
      + `<button class="ghost-button" type="button" id="closeSchemaPanel">Close</button></div>`
      + `<p class="action-error">Failed to load schema data: ${escapeHtml(error.message)}</p>`;
    bindSchemaPanelClose();
  }
}

function bindSchemaPanelClose() {
  const close = document.getElementById("closeSchemaPanel");
  if (close) {
    close.addEventListener("click", () => {
      const panel = document.getElementById("schemaPanel");
      panel.hidden = true;
      panel.innerHTML = "";
    });
  }
}

function renderSchemaPanel(eventType, versions, baseline, notice) {
  const panel = document.getElementById("schemaPanel");
  if (!versions.length) {
    panel.innerHTML = `<div class="subpanel-head"><h3>Schema: ${escapeHtml(eventType)}</h3>`
      + `<button class="ghost-button" type="button" id="closeSchemaPanel">Close</button></div>`
      + `<p class="muted">No stored versions for this event type.</p>`;
    bindSchemaPanelClose();
    return;
  }
  const active = versions.find((version) => version.status === "ACTIVE") || versions[0];
  const sync = baseline && baseline.baseline_sync
    ? window.DwtConsole.schemaSyncLabel(baseline.baseline_sync) : null;
  const options = versions.map((version) =>
    `<option value="${escapeHtml(version.id)}">#${escapeHtml(version.id)} ${escapeHtml(shortHash(version.schema_hash))} ${escapeHtml(version.status)}</option>`).join("");
  panel.innerHTML = `
    <div class="subpanel-head">
      <h3>Schema: ${escapeHtml(eventType)}</h3>
      <button class="ghost-button" type="button" id="closeSchemaPanel">Close</button>
    </div>
    ${kvGrid([
      ["Active version", `#${active.id}`],
      ["Active hash", shortHash(active.schema_hash)],
      ["First seen", formatDate(active.first_seen_at)],
      ["Last seen", formatDate(active.last_seen_at)],
      ["Publication state", sync ? sync.state : "no active baseline"],
    ])}
    ${sync
      ? `<p class="muted">${escapeHtml(sync.publication)} ${escapeHtml(sync.application)}</p>`
      : `<p class="muted">Publication is not proof of consumer application; no active baseline is recorded yet.</p>`}
    <div class="compare-row">
      <label><span>Baseline version</span><select id="schemaCompareBase">${options}</select></label>
      <label><span>Candidate version</span><select id="schemaCompareCandidate">${options}</select></label>
      <button id="schemaCompare" class="ghost-button" type="button">Compare structure</button>
      <button id="schemaActivate" class="ghost-button" type="button">Activate as baseline</button>
      <button id="schemaConfirmActivate" class="ghost-button" type="button" hidden>Confirm activation</button>
      <button id="schemaCancelActivate" class="ghost-button" type="button" hidden>Cancel</button>
    </div>
    <p id="schemaPanelStatus" class="action-status" role="status" aria-live="polite"></p>
    <div id="schemaDiff"></div>`;
  const base = document.getElementById("schemaCompareBase");
  const candidate = document.getElementById("schemaCompareCandidate");
  base.value = String(active.id);
  candidate.value = String(versions[versions.length - 1].id);
  bindSchemaPanelClose();
  document.getElementById("schemaCompare").addEventListener("click", () => {
    const baseVersion = versions.find((version) => String(version.id) === base.value);
    const candidateVersion = versions.find((version) => String(version.id) === candidate.value);
    renderSchemaDiff(baseVersion, candidateVersion);
  });
  const activate = document.getElementById("schemaActivate");
  const confirmActivate = document.getElementById("schemaConfirmActivate");
  const cancelActivate = document.getElementById("schemaCancelActivate");
  activate.addEventListener("click", () => {
    activate.hidden = true;
    confirmActivate.hidden = false;
    cancelActivate.hidden = false;
    confirmActivate.focus();
  });
  cancelActivate.addEventListener("click", () => {
    activate.hidden = false;
    confirmActivate.hidden = true;
    cancelActivate.hidden = true;
    activate.focus();
  });
  confirmActivate.addEventListener("click", async () => {
    confirmActivate.disabled = true;
    setStatus("schemaPanelStatus", "Activating baseline...");
    try {
      const response = await requestJson(`${API}/schemas/${encodeURIComponent(eventType)}/baseline`, {
        method: "PUT",
        headers: { "Content-Type": "application/json" },
        body: JSON.stringify({ version_id: Number(candidate.value) }),
      });
      if (!response.ok) throw new Error(describeHttpError(response));
      const label = window.DwtConsole.schemaSyncLabel(response.body && response.body.baseline_sync);
      await loadSchemas();
      await openSchemaPanel(eventType, `${label.publication} ${label.application}`);
    } catch (error) {
      setStatus("schemaPanelStatus", `Activation failed: ${error.message}`, true);
      confirmActivate.disabled = false;
    }
  });
  if (notice) setStatus("schemaPanelStatus", notice);
}

function renderSchemaDiff(baseVersion, candidateVersion) {
  const container = document.getElementById("schemaDiff");
  const base = baseVersion.schema || {};
  const candidate = candidateVersion.schema || {};
  const added = Object.keys(candidate).filter((key) => !Object.hasOwn(base, key));
  const missing = Object.keys(base).filter((key) => !Object.hasOwn(candidate, key));
  const changed = Object.keys(base).filter((key) => Object.hasOwn(candidate, key) && String(base[key]) !== String(candidate[key]));
  const block = (title, keys, describe) => `
    <div class="diff-block"><h4>${escapeHtml(title)} (${keys.length})</h4>
      ${keys.length
        ? `<ul>${keys.map((key) => `<li><span class="code-chip">${escapeHtml(key)}</span> ${escapeHtml(describe(key))}</li>`).join("")}</ul>`
        : `<p class="muted">None.</p>`}
    </div>`;
  container.innerHTML = block(`Added in #${candidateVersion.id}`, added, (key) => `type ${String(candidate[key])}`)
    + block(`Missing from #${candidateVersion.id}`, missing, (key) => `baseline type ${String(base[key])}`)
    + block("Type changed", changed, (key) => `${String(base[key])} to ${String(candidate[key])}`);
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

/** POST/PUT with the CSRF header that also exposes HTTP status and parsed body for retry logic. */
async function requestJson(url, options = {}) {
  const response = await mutate(url, Object.assign({}, options, { method: options.method || "POST" }));
  let body = null;
  try {
    body = await response.json();
  } catch (error) {
    body = null;
  }
  return { ok: response.ok, status: response.status, body };
}

function describeHttpError(response) {
  const body = response.body;
  if (body && body.message) {
    return `${body.error || `HTTP ${response.status}`}: ${body.message}`;
  }
  if (body && body.error) {
    return String(body.error);
  }
  return `HTTP ${response.status}`;
}

function setStatus(id, message, isError) {
  const el = document.getElementById(id);
  if (!el) return;
  el.textContent = message;
  el.classList.toggle("error", Boolean(isError));
}

function kvGrid(rows) {
  return `<div class="kv-grid">${rows.map(([label, value]) => {
    const text = value === null || value === undefined || value === "" ? "-" : String(value);
    return `<div class="kv"><span>${escapeHtml(label)}</span><strong>${escapeHtml(text)}</strong></div>`;
  }).join("")}</div>`;
}

function formatDurationMs(value) {
  if (value === null || value === undefined || value === "") return "-";
  const seconds = Math.round(Number(value) / 1000);
  if (!Number.isFinite(seconds)) return "-";
  if (seconds % 3600 === 0) return `${seconds / 3600} h (${value} ms)`;
  if (seconds % 60 === 0) return `${seconds / 60} min (${value} ms)`;
  return `${seconds} s (${value} ms)`;
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
