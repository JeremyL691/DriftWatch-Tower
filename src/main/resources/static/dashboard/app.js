const state = {
  scenarioStartedAt: null,
  lastScenarioName: null,
};

document.addEventListener("DOMContentLoaded", () => {
  document.getElementById("refreshAll").addEventListener("click", () => refreshDashboard());
  document.getElementById("applyAlertFilters").addEventListener("click", () => loadAlerts());
  document.querySelectorAll(".scenario-button").forEach((button) => {
    button.addEventListener("click", () => runScenario(button));
  });
  refreshDashboard();
});

async function refreshDashboard() {
  await Promise.all([
    loadSummary(),
    loadAlerts(),
    loadSourceHealth(),
    loadSchemas(),
    loadRecentEvents(),
  ]);
}

async function loadSummary() {
  const summary = await fetchJson("/dashboard/api/summary");
  setText("statTotalEvents", formatNumber(summary.totalEvents));
  setText("statActiveSources", formatNumber(summary.activeSources));
  setText("statAlerts24h", formatNumber(summary.alertsLast24h));
  setText("statUnhealthySources", formatNumber(summary.unhealthySources));

  const alerts = await fetchJson("/alerts?size=8");
  renderLatestAlerts(alerts);
}

async function loadAlerts() {
  const source = document.getElementById("alertSourceFilter").value.trim();
  const type = document.getElementById("alertTypeFilter").value;
  const params = new URLSearchParams({ size: "30" });
  if (source) params.set("source", source);
  if (type) params.set("type", type);
  const alerts = await fetchJson(`/alerts?${params.toString()}`);
  renderAlertsTable(alerts);
}

async function loadSourceHealth() {
  const rows = await fetchJson("/sources/health");
  renderSourceHealthTable(rows);
}

async function loadSchemas() {
  const rows = await fetchJson("/schemas");
  renderSchemasTable(rows);
}

async function loadRecentEvents() {
  const rows = await fetchJson("/events/recent?size=8");
  renderRecentEvents(rows);
}

async function runScenario(button) {
  const scenario = button.dataset.scenario;
  state.scenarioStartedAt = Date.now();
  state.lastScenarioName = scenario;
  setScenarioStatus(`Running ${scenario}...`, "Publishing demo events and waiting for detectors to react.");
  disableScenarioButtons(true);
  try {
    const response = await fetch(`/demo/run-scenario/${scenario}`, { method: "POST" });
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
  document.getElementById("scenarioStatus").innerHTML = `
    <strong>${escapeHtml(title)}</strong>
    <p>${escapeHtml(detail)}</p>
  `;
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
