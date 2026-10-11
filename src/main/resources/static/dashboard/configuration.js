/*
 * Management configuration editor.
 *
 * Loaded as a classic script after console-lib.js and before app.js. It owns the
 * #configuration section only: state display, revision-aware save, unsaved-edit
 * preservation and operation history. The server remains the validation authority.
 */
(function () {
  "use strict";

  const API = "/api/v1";
  const lib = window.DwtConsole;

  const state = {
    server: null,
    values: {},
    ranges: [],
    dirty: new Set(),
    errors: {},
    loaded: false,
    historyPage: 0,
    historySize: 20,
    historyDone: false,
    busy: false,
    lastKnownSaved: null,
  };

  document.addEventListener("DOMContentLoaded", () => {
    buildForm();
    bindControls();
    refresh();
  });

  /* ------------------------------------------------------------------ rendering: form */

  function buildForm() {
    const form = document.getElementById("configForm");
    if (!form) return;
    lib.FIELD_GROUPS.forEach((group) => {
      const fieldset = document.createElement("fieldset");
      fieldset.className = "field-group";
      const legend = document.createElement("legend");
      legend.textContent = group.legend;
      fieldset.appendChild(legend);
      const note = document.createElement("p");
      note.className = "muted field-group-note";
      note.textContent = group.note;
      fieldset.appendChild(note);

      const grid = document.createElement("div");
      grid.className = "field-grid";
      lib.CONFIG_FIELDS.filter((field) => field.group === group.id).forEach((field) => {
        if (field.type === "ranges") {
          grid.appendChild(buildRangesField(field));
        } else {
          grid.appendChild(buildField(field));
        }
      });
      fieldset.appendChild(grid);
      form.insertBefore(fieldset, form.querySelector(".form-actions"));
    });
  }

  function buildField(field) {
    const wrapper = document.createElement("div");
    wrapper.className = "field";
    wrapper.dataset.field = field.key;

    const label = document.createElement("label");
    label.setAttribute("for", `cfg-${field.key}`);
    label.innerHTML = `<span class="field-label-text"></span> <span class="field-scope"></span>`;
    label.querySelector(".field-label-text").textContent = field.label;
    const scope = label.querySelector(".field-scope");
    scope.className = `scope-chip ${field.live ? "scope-live" : "scope-restart"}`;
    scope.textContent = field.live ? "live" : "restart";
    wrapper.appendChild(label);

    let input;
    if (field.type === "textarea") {
      input = document.createElement("textarea");
      input.rows = 3;
    } else if (field.type === "boolean") {
      input = document.createElement("input");
      input.type = "checkbox";
      input.className = "checkbox-input";
    } else {
      input = document.createElement("input");
      input.type = field.type === "text" ? "text" : "number";
      if (field.type === "number") {
        input.step = "any";
      }
    }
    input.id = `cfg-${field.key}`;
    input.dataset.key = field.key;
    input.setAttribute("aria-describedby", `cfg-${field.key}-hint cfg-${field.key}-error`);
    if (field.readOnly) {
      input.readOnly = true;
      input.setAttribute("aria-readonly", "true");
    }
    wrapper.appendChild(input);

    const hint = document.createElement("p");
    hint.id = `cfg-${field.key}-hint`;
    hint.className = "field-hint muted";
    hint.textContent = field.hint;
    wrapper.appendChild(hint);

    const dirty = document.createElement("span");
    dirty.className = "dirty-chip";
    dirty.id = `cfg-${field.key}-dirty`;
    dirty.textContent = "unsaved";
    dirty.hidden = true;
    wrapper.appendChild(dirty);

    const effective = document.createElement("p");
    effective.className = "field-effective muted";
    effective.id = `cfg-${field.key}-effective`;
    effective.hidden = true;
    wrapper.appendChild(effective);

    const error = document.createElement("p");
    error.className = "field-error";
    error.id = `cfg-${field.key}-error`;
    error.setAttribute("role", "alert");
    wrapper.appendChild(error);
    return wrapper;
  }

  function buildRangesField(field) {
    const wrapper = document.createElement("div");
    wrapper.className = "field field-wide";
    wrapper.dataset.field = "ranges";
    wrapper.innerHTML = `
      <span class="field-label-text">${field.label} <span class="scope-chip scope-live">live</span></span>
      <div id="configRanges" class="range-rows"></div>
      <button type="button" id="addRangeRow" class="ghost-button small-button">Add field</button>
      <p class="field-hint muted" id="cfg-ranges-hint"></p>
      <p class="field-error" id="cfg-ranges-error" role="alert"></p>`;
    wrapper.querySelector("#cfg-ranges-hint").textContent = field.hint;
    wrapper.querySelector("#addRangeRow").addEventListener("click", () => {
      state.ranges.push({ field: "", min: "", max: "" });
      renderRangeRows();
      markDirty("ranges");
    });
    return wrapper;
  }

  function renderRangeRows() {
    const container = document.getElementById("configRanges");
    container.innerHTML = state.ranges.map((row, index) => `
      <div class="range-row" data-range-index="${index}">
        <label class="range-cell"><span>Field</span>
          <input type="text" data-range="${index}" data-part="field" value="${escapeAttr(row.field)}"
                 aria-label="Range field ${index + 1} name" aria-describedby="cfg-ranges-error"></label>
        <label class="range-cell"><span>Min</span>
          <input type="number" step="any" data-range="${index}" data-part="min" value="${escapeAttr(row.min)}"
                 aria-label="Range field ${index + 1} minimum" aria-describedby="cfg-ranges-error"></label>
        <label class="range-cell"><span>Max</span>
          <input type="number" step="any" data-range="${index}" data-part="max" value="${escapeAttr(row.max)}"
                 aria-label="Range field ${index + 1} maximum" aria-describedby="cfg-ranges-error"></label>
        <button type="button" class="ghost-button small-button" data-remove-range="${index}"
                aria-label="Remove range field ${index + 1}">Remove</button>
        <p class="field-error" data-error-for="ranges.${index}.field" role="alert"></p>
        <p class="field-error" data-error-for="ranges.${index}.min" role="alert"></p>
        <p class="field-error" data-error-for="ranges.${index}.max" role="alert"></p>
      </div>
    `).join("");
    container.querySelectorAll("[data-remove-range]").forEach((button) => {
      button.addEventListener("click", () => {
        const index = Number(button.dataset.removeRange);
        state.ranges.splice(index, 1);
        renderRangeRows();
        markDirty("ranges");
      });
    });
    container.querySelectorAll("input[data-range]").forEach((input) => {
      input.addEventListener("input", () => {
        state.ranges[Number(input.dataset.range)][input.dataset.part] = input.value;
        markDirty("ranges");
      });
    });
  }

  function bindControls() {
    const form = document.getElementById("configForm");
    if (!form) return;
    form.addEventListener("input", (event) => {
      const key = event.target.dataset.key;
      if (key) {
        markDirty(key);
      }
      if (event.target.id === "cfg-anomaly_baseline_windows") {
        syncCoupledHistory();
      }
    });
    form.addEventListener("change", (event) => {
      const key = event.target.dataset.key;
      if (key) {
        markDirty(key);
      }
    });
    form.addEventListener("submit", (event) => {
      event.preventDefault();
      save();
    });
    document.getElementById("configReload").addEventListener("click", () => refresh({ keepEdits: true, manual: true }));
    document.getElementById("configRevert").addEventListener("click", () => {
      if (!state.server) return;
      applyServerValues(state.server.saved);
      state.dirty.clear();
      state.errors = {};
      renderDirtyAndErrors();
      setBanner("info", "Form reset to the saved revision.");
    });
    document.getElementById("configHistoryMore").addEventListener("click", () => loadHistory(false));
  }

  // The minimum is documented as coupled/redundant: the field always mirrors the baseline span.
  function syncCoupledHistory() {
    const baseline = document.getElementById("cfg-anomaly_baseline_windows");
    const history = document.getElementById("cfg-anomaly_min_history_windows");
    if (baseline && history) {
      history.value = baseline.value;
    }
  }

  /* ------------------------------------------------------------------ rendering: state */

  function applyServerValues(saved) {
    state.values = valuesToForm(saved);
    state.ranges = rangesToRows(saved.ranges);
    renderValues();
  }

  function valuesToForm(doc) {
    const values = {};
    if (!doc) return values;
    lib.CONFIG_FIELDS.forEach((field) => {
      if (field.type === "ranges") return;
      values[field.key] = doc[field.key];
    });
    return values;
  }

  function rangesToRows(ranges) {
    if (!ranges) return [];
    return Object.keys(ranges).sort().map((field) => ({
      field,
      min: ranges[field].min === null || ranges[field].min === undefined ? "" : String(ranges[field].min),
      max: ranges[field].max === null || ranges[field].max === undefined ? "" : String(ranges[field].max),
    }));
  }

  function renderValues() {
    lib.CONFIG_FIELDS.forEach((field) => {
      if (field.type === "ranges") return;
      const input = document.getElementById(`cfg-${field.key}`);
      if (!input) return;
      const value = state.values[field.key];
      if (field.type === "boolean") {
        input.checked = Boolean(value);
      } else {
        input.value = value === null || value === undefined ? "" : String(value);
      }
    });
    syncCoupledHistory();
    renderRangeRows();
    renderDirtyAndErrors();
  }

  // Refresh keeps unsaved edits: only clean fields take the server's value.
  function mergeServerState(server) {
    const serverValues = valuesToForm(server.saved);
    const merged = lib.mergeLoadedState(serverValues, state.dirty, currentFormValues());
    state.values = merged;
    if (!state.dirty.has("ranges")) {
      state.ranges = rangesToRows(server.saved.ranges);
    }
    renderValues();
  }

  function currentFormValues() {
    const values = {};
    lib.CONFIG_FIELDS.forEach((field) => {
      if (field.type === "ranges") return;
      const input = document.getElementById(`cfg-${field.key}`);
      if (!input) return;
      values[field.key] = field.type === "boolean" ? input.checked : input.value;
    });
    return values;
  }

  function readForm() {
    state.values = currentFormValues();
    // The coupled field is always submitted equal to the baseline span.
    state.values.anomaly_min_history_windows = state.values.anomaly_baseline_windows;
  }

  function markDirty(key) {
    state.dirty.add(key);
    renderDirtyAndErrors();
  }

  function renderDirtyAndErrors() {
    state.dirty.forEach((key) => {
      const chip = document.getElementById(`cfg-${key}-dirty`);
      const field = document.querySelector(`[data-field="${key}"]`);
      if (chip) chip.hidden = false;
      if (field) field.classList.add("dirty");
    });
    document.querySelectorAll(".field").forEach((field) => {
      const key = field.dataset.field;
      const chip = document.getElementById(`cfg-${key}-dirty`);
      const dirty = state.dirty.has(key);
      if (chip) chip.hidden = !dirty;
      field.classList.toggle("dirty", dirty);
    });

    document.querySelectorAll("[data-error-for]").forEach((el) => { el.textContent = ""; });
    document.querySelectorAll(".field-error[id$='-error']").forEach((el) => { el.textContent = ""; });
    Object.keys(state.errors).forEach((key) => {
      const target = document.querySelector(`[data-error-for="${key}"]`)
        || document.getElementById(`cfg-${key}-error`);
      if (target) target.textContent = state.errors[key];
    });
    document.querySelectorAll("input, textarea").forEach((input) => input.removeAttribute("aria-invalid"));
    Object.keys(state.errors).forEach((key) => {
      const input = document.getElementById(`cfg-${key}`)
        || document.querySelector(`[data-key="${key}"]`);
      if (input) input.setAttribute("aria-invalid", "true");
    });

    const save = document.getElementById("configSave");
    const dirtyCount = state.dirty.size;
    save.disabled = state.busy || !state.server || dirtyCount === 0;
    document.getElementById("configDirtyCount").textContent = dirtyCount
      ? `${dirtyCount} field(s) edited, not yet saved`
      : "No unsaved edits";
  }

  function renderState(server) {
    const summary = lib.revisionSummary(server);
    document.getElementById("configRevision").textContent = summary.label;
    const effective = document.getElementById("configEffective");
    effective.textContent = summary.effectiveLabel;
    effective.className = summary.hybrid ? "muted state-hybrid" : "muted";

    const pending = lib.pendingFields(server);
    const container = document.getElementById("configPending");
    container.innerHTML = pending.length
      ? `<span class="pending-label">Pending restart:</span> ${pending
          .map((key) => `<span class="code-chip">${escapeHtml(key)}</span>`).join(" ")}`
      : `<span class="muted">No restart-pending differences; effective matches the saved revision.</span>`;

    lib.CONFIG_FIELDS.forEach((field) => {
      const note = document.getElementById(`cfg-${field.key}-effective`);
      if (!note) return;
      const text = lib.pendingFieldNote(field.key, server);
      note.hidden = !text;
      note.textContent = text || "";
      const input = document.getElementById(`cfg-${field.key}`);
      if (input) {
        input.classList.toggle("pending-restart", Boolean(text));
      }
    });

    // Legacy compatibility: a saved lesser minimum is shown verbatim, and saving writes the
    // coupled full span. Tell the operator before they save.
    const notice = document.getElementById("configHistoryNotice");
    if (server.saved.anomaly_min_history_windows !== server.saved.anomaly_baseline_windows) {
      notice.hidden = false;
      notice.textContent = `Saved legacy compatibility value: anomaly_min_history_windows is `
        + `${server.saved.anomaly_min_history_windows} while the baseline span is `
        + `${server.saved.anomaly_baseline_windows}. Full baseline history is already required, so a save `
        + `writes the coupled value ${server.saved.anomaly_baseline_windows}.`;
    } else {
      notice.hidden = true;
      notice.textContent = "";
    }
  }

  function setBanner(kind, message) {
    const banner = document.getElementById("configBanner");
    banner.className = `state-banner ${kind}`;
    banner.hidden = false;
    banner.textContent = message;
  }

  /* ------------------------------------------------------------------ loading + saving */

  async function requestJson(method, url, body) {
    const headers = {};
    const token = csrfToken();
    if (token) headers["X-XSRF-TOKEN"] = token;
    if (body !== undefined) headers["Content-Type"] = "application/json";
    const response = await fetch(url, {
      method,
      headers,
      body: body === undefined ? undefined : JSON.stringify(body),
    });
    let parsed = null;
    try {
      parsed = await response.json();
    } catch (error) {
      parsed = null;
    }
    return { ok: response.ok, status: response.status, body: parsed };
  }

  async function refresh(options = {}) {
    if (state.busy) return;
    try {
      const response = await requestJson("GET", `${API}/configuration`);
      if (!response.ok) {
        setBanner("error", `Configuration load failed with ${response.status}. The form keeps its current values.`);
        return;
      }
      state.server = response.body;
      state.loaded = true;
      renderState(state.server);
      const previousSaved = state.lastKnownSaved;
      if (previousSaved && options.keepEdits && state.dirty.size) {
        const changed = diffFields(previousSaved, state.server.saved);
        if (changed.length) {
          setBanner("warn", `Server revision ${state.server.saved_revision} changed (${changed.join(", ")}). `
            + "Your unsaved edits were kept; review the changed fields before saving.");
        }
      } else if (options.manual) {
        setBanner("info", "Loaded the latest saved revision.");
      }
      if (state.dirty.size) {
        mergeServerState(state.server);
      } else {
        applyServerValues(state.server.saved);
      }
      // A reload always re-bases the form on the fetched revision; dirty fields keep their edit.
      state.lastKnownSaved = state.server.saved;
      await loadHistory(true);
      renderDirtyAndErrors();
    } catch (error) {
      setBanner("error", `Configuration load failed: ${error.message}`);
    }
  }

  function diffFields(before, after) {
    const changed = [];
    lib.CONFIG_FIELDS.forEach((field) => {
      const a = JSON.stringify(before ? before[field.key] : undefined);
      const b = JSON.stringify(after ? after[field.key] : undefined);
      if (a !== b) changed.push(field.key);
    });
    return changed;
  }

  async function save() {
    if (state.busy || !state.server) return;
    readForm();
    const parsed = lib.parseConfigForm({ values: state.values, ranges: state.ranges });
    state.errors = parsed.errors;
    renderDirtyAndErrors();
    if (!parsed.ok) {
      const count = Object.keys(parsed.errors).length;
      setBanner("error", `Fix ${count} field(s) before saving. The server was not called.`);
      const first = document.querySelector("[aria-invalid='true']");
      if (first) first.focus();
      return;
    }

    state.busy = true;
    renderDirtyAndErrors();
    const button = document.getElementById("configSave");
    button.textContent = "Saving...";
    try {
      const response = await requestJson("PUT", `${API}/configuration`,
        lib.buildSaveRequest(state.server.saved_revision, parsed.config));
      if (response.ok) {
        state.server = response.body;
        state.dirty.clear();
        state.errors = {};
        state.lastKnownSaved = state.server.saved;
        renderState(state.server);
        applyServerValues(state.server.saved);
        setBanner("info", `Saved revision ${state.server.saved_revision}. Live-safe fields apply now; `
          + "restart-pending fields stay effective at their startup values until a real restart.");
        await loadHistory(true);
      } else {
        const info = lib.saveErrorMessage(response.status, response.body);
        if (info.kind === "conflict") {
          setBanner("warn", `${info.title}: ${info.message} `
            + "Use \"Load latest\" to re-base the revision; your unsaved edits are preserved.");
          document.getElementById("configReload").classList.add("attention");
        } else {
          setBanner("error", `${info.title}: ${info.message}`);
        }
      }
    } catch (error) {
      setBanner("error", `Save failed before the server answered: ${error.message}. Nothing was reported as saved.`);
    } finally {
      state.busy = false;
      button.textContent = "Save configuration";
      renderDirtyAndErrors();
    }
  }

  /* ------------------------------------------------------------------ history */

  async function loadHistory(reset) {
    if (reset) {
      state.historyPage = 0;
      state.historyDone = false;
      document.getElementById("configHistoryList").innerHTML = "";
    }
    if (state.historyDone) return;
    try {
      const response = await requestJson("GET",
        `${API}/configuration/history?page=${state.historyPage}&size=${state.historySize}`);
      if (!response.ok || !Array.isArray(response.body)) {
        document.getElementById("configHistoryList").innerHTML = `<li class="muted">History unavailable (${response.status}).</li>`;
        state.historyDone = true;
        return;
      }
      const rows = response.body;
      const list = document.getElementById("configHistoryList");
      rows.forEach((entry) => {
        const item = document.createElement("li");
        const fields = Array.isArray(entry.changed_fields) && entry.changed_fields.length
          ? entry.changed_fields.join(", ")
          : "no field changes (no-op save)";
        item.innerHTML = `<strong>r${entry.revision}</strong> `
          + `<span class="muted">${escapeHtml(entry.actor || "admin")}</span> `
          + `<span class="muted">${formatDate(entry.created_at)}</span> `
          + `<span class="muted">${escapeHtml(entry.result || "SAVED")}</span>`
          + `<br><span class="muted">${escapeHtml(fields)}</span>`;
        list.appendChild(item);
      });
      state.historyPage += 1;
      state.historyDone = rows.length < state.historySize;
      document.getElementById("configHistoryMore").hidden = state.historyDone;
      if (!list.children.length) {
        list.innerHTML = `<li class="muted">No saved revisions yet.</li>`;
      }
    } catch (error) {
      document.getElementById("configHistoryList").innerHTML = `<li class="muted">History failed: ${escapeHtml(error.message)}</li>`;
      state.historyDone = true;
    }
  }

  /* ------------------------------------------------------------------ utils */

  function csrfToken() {
    const match = document.cookie.match(/(?:^|;\s*)XSRF-TOKEN=([^;]+)/);
    return match ? decodeURIComponent(match[1]) : null;
  }

  function escapeHtml(value) {
    return String(value)
      .replaceAll("&", "&amp;")
      .replaceAll("<", "&lt;")
      .replaceAll(">", "&gt;")
      .replaceAll('"', "&quot;")
      .replaceAll("'", "&#39;");
  }

  function escapeAttr(value) {
    return escapeHtml(value === null || value === undefined ? "" : value);
  }

  function formatDate(value) {
    if (!value) return "-";
    return new Date(value).toLocaleString();
  }

  // Exposed for app.js refresh cycles; keepEdits is always true for background refreshes.
  window.DwtConfig = {
    refresh: (options = { keepEdits: true }) => refresh(options),
    state: () => state.server,
  };
})();
