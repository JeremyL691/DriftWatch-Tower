/*
 * Management console pure helpers. This file is intentionally DOM-free so the same logic can be
 * exercised by Node tests (CommonJS export) and by the browser dashboard (window.DwtConsole).
 * It performs client-side feedback only: the server remains the authority for every value.
 */
(function (root, factory) {
  const api = factory();
  if (typeof module === "object" && module.exports) {
    module.exports = api;
  }
  if (root) {
    root.DwtConsole = api;
  }
})(typeof globalThis !== "undefined" ? globalThis : this, function () {
  "use strict";

  /* ------------------------------------------------------------------ field metadata */

  // key/model field names are the exact API document keys (snake_case). `live` fields apply
  // after a committed save; every other field is applied at restart and never promised live.
  const CONFIG_FIELDS = [
    { key: "late_threshold_ms", label: "Late threshold (ms)", group: "detection", type: "integer", live: true,
      hint: "Events older than this are flagged late. Applies to REST sources immediately." },
    { key: "github_late_threshold_ms", label: "GitHub late threshold (ms)", group: "detection", type: "integer", live: true,
      hint: "GitHub history backfill is expected to be older; must be at least the general late threshold." },
    { key: "null_threshold", label: "Null spike threshold (0-1]", group: "detection", type: "number", live: true,
      hint: "Share of null values in a window that triggers a null spike." },
    { key: "null_min_samples", label: "Null minimum samples", group: "detection", type: "integer", live: true,
      hint: "Windows with fewer samples never fire a null spike." },
    { key: "anomaly_ratio", label: "Anomaly ratio", group: "detection", type: "number", live: true,
      hint: "Current window count divided by the baseline mean." },
    { key: "anomaly_min_count", label: "Anomaly minimum count", group: "detection", type: "integer", live: true,
      hint: "Current window must reach this count before an anomaly can fire." },

    { key: "anomaly_baseline_windows", label: "Anomaly baseline windows", group: "anomaly", type: "integer", live: false,
      hint: "Complete preceding windows averaged as the baseline. Restart required." },
    { key: "anomaly_min_history_windows", label: "Anomaly minimum history (coupled)", group: "anomaly", type: "integer", live: false,
      readOnly: true,
      hint: "Coupled to the baseline span. Full baseline history is required before evaluation; this is not an independent threshold." },

    { key: "duplicate_window_ms", label: "Duplicate window (ms)", group: "windows", type: "integer", live: false,
      hint: "Payload window for duplicate detection. Restart required." },
    { key: "window_size_ms", label: "Metric window size (ms)", group: "windows", type: "integer", live: false,
      hint: "Whole seconds only. Restart required." },
    { key: "grace_ms", label: "Grace period (ms)", group: "windows", type: "integer", live: false,
      hint: "How long a window waits for late arrivals. Restart required." },
    { key: "future_tolerance_ms", label: "Future tolerance (ms)", group: "windows", type: "integer", live: false,
      hint: "How far ahead an event timestamp may be. Restart required." },
    { key: "state_retention_ms", label: "State retention (ms)", group: "windows", type: "integer", live: false,
      hint: "Window state lifetime. Restart required." },

    { key: "ranges", label: "Numeric range rules", group: "rules", type: "ranges", live: true,
      hint: "Literal top-level payload keys only. A missing key is skipped, not failed." },
    { key: "format_patterns", label: "Format rules", group: "rules", type: "textarea", live: true,
      hint: "Comma separated field=regex. The parser splits on every comma, so regex must not contain one." },

    { key: "github_enabled", label: "GitHub collection enabled", group: "collector", type: "boolean", live: false,
      hint: "Restart required. Disabling is not an immediate stop: pending work keeps polling until restart." },
    { key: "github_repository", label: "GitHub repository (owner/name)", group: "collector", type: "text", live: false,
      hint: "The single existing collector target. Restart required; drain the old source before switching." },
    { key: "github_poll_interval_ms", label: "GitHub poll interval (ms)", group: "collector", type: "integer", live: false,
      hint: "5 minutes to 24 hours. Restart required." },
    { key: "github_max_pages", label: "GitHub max pages", group: "collector", type: "integer", live: false,
      hint: "1 to 10 pages per poll. Restart required." },
    { key: "github_page_size", label: "GitHub page size", group: "collector", type: "integer", live: false,
      hint: "1 to 100 per page. Restart required." }
  ];

  const FIELD_GROUPS = [
    { id: "detection", legend: "Live detector thresholds", note: "Applied after the saving transaction commits. Already processed events and already fired windows are never re-evaluated." },
    { id: "anomaly", legend: "Anomaly baseline (restart required)", note: "Baseline and history spans are captured when the topology starts." },
    { id: "windows", legend: "Window policy (restart required)", note: "Window, grace, tolerance and retention change the persisted state namespace; pending values stay ineffective until a real restart." },
    { id: "rules", legend: "Field rules", note: "Range and format rules apply live, but they address literal top-level payload keys only." },
    { id: "collector", legend: "GitHub collector (restart required)", note: "Exactly one poller exists. Settings are adopted at restart; credentials and destination stay environment-owned." }
  ];

  /* ------------------------------------------------------------------ alerts */

  function composeAlertParams(filters) {
    const params = new URLSearchParams();
    const source = ((filters && filters.source) || "").trim();
    const type = ((filters && filters.type) || "").trim();
    const status = ((filters && filters.status) || "").trim();
    const page = Number.isInteger(filters && filters.page) ? filters.page : 0;
    const size = Number.isInteger(filters && filters.size) ? filters.size : 30;
    if (source) params.set("source", source);
    if (type) params.set("type", type);
    if (status) params.set("status", status);
    params.set("page", String(page));
    params.set("size", String(size));
    return params.toString();
  }

  /* ------------------------------------------------------------------ configuration parsing */

  const DURATION_MS_MIN = 1;
  const DURATION_MS_MAX = 2592000000;
  const FIELD_NAME = /^[A-Za-z_][A-Za-z0-9_.-]{0,127}$/;
  const REPOSITORY = /^[A-Za-z0-9][A-Za-z0-9._-]{0,38}\/[A-Za-z0-9._-]{1,100}$/;

  function parseConfigForm(form) {
    const values = (form && form.values) || {};
    const rangeRows = (form && form.ranges) || [];
    const errors = {};
    const config = {};

    const integer = (key, min, max) => {
      const raw = values[key];
      if (raw === undefined || raw === null || String(raw).trim() === "") {
        errors[key] = "A value is required.";
        return null;
      }
      const value = Number(String(raw).trim());
      if (!Number.isInteger(value)) {
        errors[key] = "Must be a whole number.";
        return null;
      }
      if (value < min || value > max) {
        errors[key] = `Must be between ${min} and ${max}.`;
        return null;
      }
      return value;
    };

    const finite = (key, min, max, exclusiveMin) => {
      const raw = values[key];
      if (raw === undefined || raw === null || String(raw).trim() === "") {
        errors[key] = "A value is required.";
        return null;
      }
      const value = Number(String(raw).trim());
      if (!Number.isFinite(value)) {
        errors[key] = "Must be a finite number.";
        return null;
      }
      const lowOk = exclusiveMin ? value > min : value >= min;
      if (!lowOk || value > max) {
        errors[key] = `Must be ${exclusiveMin ? "greater than" : "at least"} ${min} and at most ${max}.`;
        return null;
      }
      return value;
    };

    const duration = (key) => integer(key, DURATION_MS_MIN, DURATION_MS_MAX);

    config.duplicate_window_ms = duration("duplicate_window_ms");
    config.late_threshold_ms = duration("late_threshold_ms");
    config.github_late_threshold_ms = duration("github_late_threshold_ms");
    config.null_threshold = finite("null_threshold", 0, 1, true);
    config.null_min_samples = integer("null_min_samples", 1, 1000000);
    config.anomaly_baseline_windows = integer("anomaly_baseline_windows", 1, 1000);
    config.anomaly_min_history_windows = config.anomaly_baseline_windows;
    config.anomaly_ratio = finite("anomaly_ratio", 1, 1000000, true);
    config.anomaly_min_count = integer("anomaly_min_count", 1, 1000000);
    config.window_size_ms = duration("window_size_ms");
    if (config.window_size_ms !== null && (config.window_size_ms < 1000 || config.window_size_ms % 1000 !== 0)) {
      errors.window_size_ms = "Must be a whole number of seconds (multiples of 1000).";
      config.window_size_ms = null;
    }
    config.grace_ms = duration("grace_ms");
    config.future_tolerance_ms = duration("future_tolerance_ms");
    config.state_retention_ms = duration("state_retention_ms");

    config.ranges = {};
    rangeRows.forEach((row, index) => {
      const name = String(row.field || "").trim();
      const minRaw = row.min === undefined || row.min === null ? "" : String(row.min).trim();
      const maxRaw = row.max === undefined || row.max === null ? "" : String(row.max).trim();
      if (!name && !minRaw && !maxRaw) return; // blank row is ignored
      if (!name) {
        errors[`ranges.${index}.field`] = "Field name is required.";
        return;
      }
      if (!FIELD_NAME.test(name)) {
        errors[`ranges.${index}.field`] = "Field names use letters, digits, _ . - only.";
        return;
      }
      if (Object.prototype.hasOwnProperty.call(config.ranges, name)) {
        errors[`ranges.${index}.field`] = "Duplicate field name.";
        return;
      }
      if (!minRaw && !maxRaw) {
        errors[`ranges.${index}.min`] = "Provide at least one bound.";
        return;
      }
      const min = minRaw ? Number(minRaw) : null;
      const max = maxRaw ? Number(maxRaw) : null;
      if (min !== null && !Number.isFinite(min)) {
        errors[`ranges.${index}.min`] = "Must be a finite number.";
        return;
      }
      if (max !== null && !Number.isFinite(max)) {
        errors[`ranges.${index}.max`] = "Must be a finite number.";
        return;
      }
      if (min !== null && max !== null && min > max) {
        errors[`ranges.${index}.max`] = "Maximum must be at least the minimum.";
        return;
      }
      // defineProperty keeps names such as __proto__ as ordinary own keys.
      Object.defineProperty(config.ranges, name, { value: { min, max }, enumerable: true, writable: true, configurable: true });
    });
    if (Object.keys(config.ranges).length > 50) {
      errors.ranges = "At most 50 range fields are allowed.";
    }

    const patterns = String(values.format_patterns ?? "");
    config.format_patterns = patterns;
    if (patterns.length > 8192) {
      errors.format_patterns = "At most 8192 characters.";
    } else if (patterns.trim()) {
      // Mirrors FieldFormatPatterns.parse: entries are trimmed and blank ones skipped. Regex syntax is
      // Java's, so it is left to the server (the authority) rather than checked with JavaScript RegExp.
      const entries = patterns.split(",").map((entry) => entry.trim()).filter((entry) => entry.length > 0);
      if (entries.length > 50) {
        errors.format_patterns = "At most 50 format fields.";
      }
      for (const entry of entries) {
        const separator = entry.indexOf("=");
        if (separator <= 0) {
          errors.format_patterns = `"${entry}" is not a field=regex pair.`;
          break;
        }
        const field = entry.slice(0, separator).trim();
        const regex = entry.slice(separator + 1).trim();
        if (!FIELD_NAME.test(field)) {
          errors.format_patterns = `"${field}" is not a valid field name.`;
          break;
        }
        if (!regex) {
          errors.format_patterns = `The pattern for ${field} is empty.`;
          break;
        }
        if (regex.length > 512) {
          errors.format_patterns = `The pattern for ${field} is longer than 512 characters.`;
          break;
        }
      }
    }

    config.github_enabled = Boolean(values.github_enabled);
    const repository = String(values.github_repository ?? "").trim();
    config.github_repository = repository;
    if (!REPOSITORY.test(repository)) {
      errors.github_repository = "Use owner/name with letters, digits, _ . - only.";
    }
    config.github_poll_interval_ms = integer("github_poll_interval_ms", 300000, 86400000);
    config.github_max_pages = integer("github_max_pages", 1, 10);
    config.github_page_size = integer("github_page_size", 1, 100);

    // Cross-field relations mirror the server contract so the user gets immediate feedback.
    if (config.duplicate_window_ms !== null && config.window_size_ms !== null
        && config.duplicate_window_ms < config.window_size_ms) {
      errors.duplicate_window_ms = "Must be at least the metric window size.";
    }
    if (config.github_late_threshold_ms !== null && config.late_threshold_ms !== null
        && config.github_late_threshold_ms < config.late_threshold_ms) {
      errors.github_late_threshold_ms = "Must be at least the general late threshold.";
    }
    if (config.state_retention_ms !== null && config.grace_ms !== null
        && config.anomaly_baseline_windows !== null && config.window_size_ms !== null) {
      const required = config.grace_ms + (config.anomaly_baseline_windows + 1) * config.window_size_ms;
      if (config.state_retention_ms < required) {
        errors.state_retention_ms = `Must be at least grace + (baseline + 1) * window = ${required} ms.`;
      }
    }

    const failed = Object.keys(errors).length > 0;
    return { ok: !failed, config: failed ? null : config, errors };
  }

  function buildSaveRequest(expectedRevision, config) {
    return { expected_revision: Number(expectedRevision) || 0, config };
  }

  /* ------------------------------------------------------------------ error + state helpers */

  function saveErrorMessage(status, body) {
    const message = body && typeof body.message === "string" ? body.message : null;
    if (status === 409) {
      return {
        kind: "conflict",
        title: "Revision conflict",
        message: message || "Configuration revision changed. Load the latest revision without losing your edits."
      };
    }
    if (status === 400) {
      return { kind: "invalid", title: "Configuration rejected", message: message || "The server rejected one or more values." };
    }
    if (status === 401) {
      return { kind: "auth", title: "Authentication required", message: message || "Sign in again before saving." };
    }
    if (status === 403) {
      return { kind: "forbidden", title: "Not allowed", message: message || "Admin role and a fresh CSRF token are required." };
    }
    return {
      kind: "error",
      title: "Save failed",
      message: message || `The server answered ${status}. Nothing was reported as saved; re-query before retrying.`
    };
  }

  // Refresh must never erase an unsaved edit: only clean fields take server values.
  function mergeLoadedState(serverValues, dirtyFields, currentValues) {
    const merged = {};
    const dirty = new Set(dirtyFields || []);
    Object.keys(serverValues).forEach((key) => {
      merged[key] = dirty.has(key) && currentValues && key in currentValues
        ? currentValues[key]
        : serverValues[key];
    });
    return merged;
  }

  function pendingFields(state) {
    return Array.isArray(state && state.pending_restart_fields) ? state.pending_restart_fields : [];
  }

  function pendingFieldNote(fieldKey, state) {
    const pending = pendingFields(state).includes(fieldKey);
    if (!pending || !state || !state.effective) return null;
    const effectiveValue = state.effective[fieldKey];
    return `Effective now: ${JSON.stringify(effectiveValue)}. Adopted at restart.`;
  }

  function revisionSummary(state) {
    if (!state) return { label: "not loaded", effectiveLabel: "unknown", hybrid: false };
    const saved = Number(state.saved_revision || 0);
    const live = Number(state.live_applied_revision || 0);
    const effective = state.effective_revision;
    return {
      label: `Saved revision ${saved}`,
      effectiveLabel: effective === null || effective === undefined
        ? `Hybrid effective state; process applied revision ${live}`
        : `Effective revision ${effective}; process applied revision ${live}`,
      hybrid: effective === null || effective === undefined
    };
  }

  /* ------------------------------------------------------------------ schema publication */

  function schemaSyncLabel(sync) {
    const state = sync && sync.state ? sync.state : "UNKNOWN";
    const publication = state === "PUBLISHED"
      ? "Published to the schema baseline topic."
      : state === "PENDING"
        ? "Publication is pending; the baseline is not confirmed on the topic yet."
        : state === "FAILED"
          ? "Publication failed."
          : "Publication state is unknown; re-query.";
    return {
      state,
      publication,
      application: "Consumer application is unconfirmed: Streams may use the previous baseline until it applies the new one."
    };
  }

  /* ------------------------------------------------------------------ collector view model */

  // Effective enablement is the operator's current truth. A stored LOST/QUIET row is history.
  function collectorViewModel(collector, effectiveConfig) {
    const enabled = Boolean(effectiveConfig && effectiveConfig.github_enabled);
    const repository = effectiveConfig ? effectiveConfig.github_repository : null;
    const interval = effectiveConfig ? effectiveConfig.github_poll_interval_ms : null;
    if (!collector) {
      return {
        enabled,
        repository,
        interval,
        hasState: false,
        headline: enabled
          ? "No persisted poll state for the configured source yet."
          : "Collector is disabled in the effective configuration; no polling happens regardless of stored history.",
        historicalStatus: null
      };
    }
    return {
      enabled,
      repository,
      interval,
      hasState: true,
      headline: enabled
        ? `Effective status ${collector.status}; polling the configured source.`
        : "Collector is disabled in the effective configuration. Stored status below is historical, not a running poller.",
      historicalStatus: collector.status
    };
  }

  function gapsLabel(collector) {
    if (!collector) return "No gap data.";
    const open = Number(collector.open_gaps || 0);
    const recent = Array.isArray(collector.recent_gaps) ? collector.recent_gaps.length : 0;
    return `${open} open gap(s); showing the newest ${recent} of at most 20.`;
  }

  function replayOutcomeLabel(body) {
    if (!body) return "Replay response missing.";
    const outcome = body.outcome || "UNKNOWN";
    return `${outcome}. Accepted replay is not proof that processing recovered; re-query the dead letter and its replay history.`;
  }

  function retentionRunLabel(body) {
    if (!body || body.status !== "completed") {
      return "Retention run did not report completion; nothing was pruned by this request.";
    }
    const pruned = body.pruned && typeof body.pruned === "object" ? body.pruned : {};
    const names = Object.keys(pruned);
    if (!names.length) {
      return "Completed; nothing needed pruning. Zero rows is a valid result for recent data.";
    }
    return `Completed; pruned ${names.map((name) => `${name}: ${pruned[name]}`).join(", ")}.`;
  }

  return {
    CONFIG_FIELDS,
    FIELD_GROUPS,
    composeAlertParams,
    parseConfigForm,
    buildSaveRequest,
    saveErrorMessage,
    mergeLoadedState,
    pendingFields,
    pendingFieldNote,
    revisionSummary,
    schemaSyncLabel,
    collectorViewModel,
    gapsLabel,
    replayOutcomeLabel,
    retentionRunLabel
  };
});
