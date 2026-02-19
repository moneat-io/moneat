interface EventPayload {
  n: string;
  u: string;
  d: string;
  r: string;
  w: number;
  p?: Record<string, string>;
}

/**
 * Send an analytics event to the backend.
 * Uses sendBeacon with fetch fallback.
 */
export function sendEvent(
  apiHost: string,
  domain: string,
  key: string,
  payload: EventPayload,
): void {
  const url = `${apiHost}/api/${domain}/analytics/event?sentry_key=${key}`;
  const body = JSON.stringify(payload);

  if (navigator.sendBeacon) {
    const blob = new Blob([body], { type: 'application/json' });
    const sent = navigator.sendBeacon(url, blob);
    if (sent) return;
  }

  // Fallback to fetch
  fetch(url, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body,
    keepalive: true,
  }).catch(() => {
    // Silently ignore send failures
  });
}
