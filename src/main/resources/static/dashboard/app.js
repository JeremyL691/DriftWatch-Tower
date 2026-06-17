const state = {
  scenarioStartedAt: null,
  lastScenarioName: null,
  stompClient: null,
  wsConnected: false,
};

// ponytail: respects user OS-level reduce-motion; single source of truth for the whole file.
const REDUCED_MOTION = window.matchMedia("(prefers-reduced-motion: reduce)").matches;
const GSAP_OK = typeof gsap !== "undefined";

document.addEventListener("DOMContentLoaded", () => {
  document.getElementById("refreshAll").addEventListener("click", () => refreshDashboard());
  document.getElementById("applyAlertFilters").addEventListener("click", () => loadAlerts());
  document.querySelectorAll(".scenario-button").forEach((button) => {
    button.addEventListener("click", () => runScenario(button));
  });
  refreshDashboard();
  connectWebSocket();
  initMotion();
});

function connectWebSocket() {
  const socket = new SockJS("/ws");
  state.stompClient = Stomp.over(socket);
  state.stompClient.debug = null;

  state.stompClient.connect({}, () => {
    updateWsStatus(true);
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
    setTimeout(connectWebSocket, 3000);
  });
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
  ]);
}

async function loadSummary() {
  const summary = await fetchJson("/dashboard/api/summary");
  animateCount(document.getElementById("statTotalEvents"), summary.totalEvents);
  animateCount(document.getElementById("statActiveSources"), summary.activeSources);
  animateCount(document.getElementById("statAlerts24h"), summary.alertsLast24h);
  animateCount(document.getElementById("statUnhealthySources"), summary.unhealthySources);

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
  if (GSAP_OK) gsap.registerPlugin(ScrollTrigger);

  // #1 Entrance stagger — panels fade up as they scroll into view.
  if (!REDUCED_MOTION && GSAP_OK) {
    gsap.utils.toArray(".panel").forEach((panel) => {
      gsap.from(panel, {
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
  if (!REDUCED_MOTION && GSAP_OK) {
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
        gsap.killTweensOf(overlay);
        gsap.fromTo(overlay, { xPercent: -120 }, { xPercent: 220, duration: 0.85, ease: "power2.inOut" });
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
  if (REDUCED_MOTION || !GSAP_OK) {
    el.textContent = formatNumber(value);
    return;
  }
  if (el._countTween) el._countTween.kill();
  const start = parseInt(String(el.textContent || "0").replace(/[^\d-]/g, ""), 10) || 0;
  const state = { v: start };
  el._countTween = gsap.to(state, {
    v: value,
    duration: 0.9,
    ease: "power2.out",
    onUpdate: () => { el.textContent = formatNumber(Math.round(state.v)); },
    onComplete: () => { el.textContent = formatNumber(value); },
  });
}

// #4 Scenario status card crossfade.
function crossfadeIn(el) {
  if (!el || REDUCED_MOTION || !GSAP_OK) return;
  gsap.from(el, { opacity: 0, y: -6, duration: 0.3, ease: "power2.out" });
}

// #5 Status badge (pill) color crossfade — pills in freshly-rendered tables fade in.
function crossfadePills(container) {
  if (!container || REDUCED_MOTION || !GSAP_OK) return;
  const pills = container.querySelectorAll(".pill");
  if (!pills.length) return;
  gsap.from(pills, { opacity: 0, scale: 0.85, duration: 0.35, ease: "power2.out", stagger: 0.015 });
}

// #6 Alert/event slide-in from left + bounce.
function slideInTimeline(item) {
  if (!item || REDUCED_MOTION || !GSAP_OK) return;
  gsap.from(item, { x: -40, opacity: 0, duration: 0.5, ease: "back.out(1.4)" });
}

// #7 WebSocket data update pulse — ws-dot scale yoyo on incoming message.
function pulseWsDot() {
  if (REDUCED_MOTION || !GSAP_OK) return;
  const dot = document.querySelector("#wsStatus .ws-dot");
  if (!dot) return;
  gsap.fromTo(dot, { scale: 1 }, { scale: 1.6, duration: 0.18, ease: "power2.out", yoyo: true, repeat: 1 });
}
