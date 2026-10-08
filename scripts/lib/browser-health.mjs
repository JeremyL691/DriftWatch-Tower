/** Poll the health API from Node and the rendered table without async Playwright predicates. */
export async function waitForUnhealthySource(page, {
  healthUrl,
  headers = {},
  fetchImpl = globalThis.fetch,
  refreshPage,
  timeoutMs = 120000,
  pollIntervalMs = 250,
} = {}) {
  if (!Number.isFinite(timeoutMs) || timeoutMs <= 0) throw new TypeError('timeoutMs must be positive');
  if (!Number.isFinite(pollIntervalMs) || pollIntervalMs <= 0) throw new TypeError('pollIntervalMs must be positive');
  if (typeof healthUrl !== 'string' || healthUrl.length === 0) throw new TypeError('healthUrl is required');
  if (typeof fetchImpl !== 'function') throw new TypeError('fetchImpl must be a function');

  const deadline = Date.now() + timeoutMs;
  let healthSnapshot = { httpStatus: null, error: 'health endpoint has not been read', unhealthySources: [] };
  let lastUiError = null;
  let refreshedStalePage = false;

  while (Date.now() < deadline) {
    const remainingMs = Math.max(1, deadline - Date.now());
    try {
      const response = await fetchImpl(healthUrl, {
        cache: 'no-store',
        headers,
        signal: AbortSignal.timeout(remainingMs),
      });
      if (!response.ok) {
        healthSnapshot = {
          httpStatus: response.status,
          error: 'health endpoint returned a non-success status',
          unhealthySources: [],
        };
      } else {
        let sources;
        let validJson = true;
        try {
          sources = await response.json();
        } catch {
          validJson = false;
          healthSnapshot = {
            httpStatus: response.status,
            error: 'health endpoint returned invalid JSON',
            unhealthySources: [],
          };
        }
        if (validJson && (!Array.isArray(sources) || sources.some(source => !source || typeof source !== 'object'
          || typeof source.source !== 'string' || typeof source.status !== 'string'))) {
          healthSnapshot = {
            httpStatus: response.status,
            error: 'health endpoint returned an invalid source list',
            unhealthySources: [],
          };
        } else if (validJson) {
          healthSnapshot = {
            httpStatus: response.status,
            error: null,
            unhealthySources: sources
              .filter(source => source.source.startsWith('demo-') && source.status === 'UNHEALTHY')
              .map(source => ({ source: source.source, status: source.status })),
          };
        }
      }
    } catch (error) {
      healthSnapshot = {
        httpStatus: null,
        error: error?.name === 'TimeoutError' || error?.name === 'AbortError'
          ? 'health request timed out' : 'health request failed',
        unhealthySources: [],
      };
    }

    let badgeVisible = false;
    if (healthSnapshot.httpStatus === 200 && healthSnapshot.unhealthySources.length > 0) {
      for (const source of healthSnapshot.unhealthySources) {
        try {
          const badge = page.locator('#sourceHealthTable tbody tr')
            .filter({ hasText: source.source })
            .locator('.pill.unhealthy');
          if (await badge.isVisible()) {
            badgeVisible = true;
            return { sources: healthSnapshot.unhealthySources, selected: source };
          }
        } catch (error) {
          lastUiError = error instanceof Error ? error.message : String(error);
        }
      }
    }

    if (!badgeVisible && !refreshedStalePage && typeof refreshPage === 'function'
      && healthSnapshot.httpStatus === 200 && healthSnapshot.unhealthySources.length > 0) {
      refreshedStalePage = true;
      try {
        await refreshPage(Math.max(1, deadline - Date.now()));
      } catch (error) {
        lastUiError = error instanceof Error ? `dashboard refresh failed: ${error.message}` : 'dashboard refresh failed';
      }
    }

    const pauseMs = Math.min(pollIntervalMs, Math.max(0, deadline - Date.now()));
    if (pauseMs > 0) await new Promise(resolve => setTimeout(resolve, pauseMs));
  }

  const error = new Error('an unhealthy demo source with a rendered unhealthy badge was not observed before the deadline');
  error.healthSnapshot = { ...healthSnapshot, lastUiError };
  throw error;
}
