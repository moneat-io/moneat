/**
 * Moneat Analytics — lightweight, privacy-focused page & event tracker.
 *
 * Tracks pageviews automatically (including SPA navigations via History API)
 * and exposes a `trackEvent` helper for custom events.
 */

interface AnalyticsConfig {
  domain: string
  apiHost: string
  key: string
  respectDnt?: boolean
}

let config: AnalyticsConfig | null = null

function isBot(): boolean {
  return !!(
    (window as any)._phantom ||
    (window as any).__nightmare ||
    navigator.webdriver ||
    (window as any).__puppeteer
  )
}

function isDnt(): boolean {
  return (
    config?.respectDnt !== false &&
    'doNotTrack' in navigator &&
    navigator.doNotTrack === '1'
  )
}

// --- request helpers ---

function sendEvent(payload: Record<string, unknown>): void {
  if (!config) return
  const url = `${config.apiHost}/api/${config.domain}/analytics/event?sentry_key=${config.key}`
  const body = JSON.stringify(payload)

  if (navigator.sendBeacon) {
    const blob = new Blob([body], { type: 'application/json' })
    if (navigator.sendBeacon(url, blob)) return
  }

  fetch(url, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body,
    keepalive: true,
  }).catch(() => {})
}

function buildPayload(eventName: string, props?: Record<string, string>) {
  return {
    n: eventName,
    u: location.href,
    d: config!.domain,
    r: document.referrer,
    w: window.innerWidth,
    ...(props ? { p: props } : {}),
  }
}

// --- SPA navigation detection ---

function onNavigation(callback: () => void): void {
  const origPush = history.pushState
  history.pushState = function (...args) {
    origPush.apply(this, args)
    callback()
  }

  const origReplace = history.replaceState
  history.replaceState = function (...args) {
    origReplace.apply(this, args)
    callback()
  }

  window.addEventListener('popstate', callback)
}

// --- public API ---

function trackPageview(): void {
  if (!config || isBot() || isDnt()) return
  sendEvent(buildPayload('pageview'))
}

/**
 * Track a named custom event with optional properties.
 */
export function trackEvent(name: string, props?: Record<string, string>): void {
  if (!config || isBot() || isDnt()) return
  sendEvent(buildPayload(name, props))
}

/**
 * Initialize Moneat analytics. Sends an initial pageview and
 * automatically tracks subsequent SPA navigations.
 */
export function initAnalytics(cfg: AnalyticsConfig): void {
  config = cfg

  if ((document.visibilityState as string) === 'prerender') {
    document.addEventListener(
      'visibilitychange',
      () => {
        if (document.visibilityState === 'visible') trackPageview()
      },
      { once: true },
    )
  } else {
    trackPageview()
  }

  onNavigation(trackPageview)
}
