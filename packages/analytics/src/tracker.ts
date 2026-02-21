import { sendEvent } from './request';
import { onHashNavigation, onNavigation } from './spa';

export interface TrackerConfig {
  domain: string;
  apiHost: string;
  key: string;
  trackSpa?: boolean;
  trackLocalhost?: boolean;
  hashMode?: boolean;
}

let config: TrackerConfig | null = null;

function isBot(): boolean {
  return !!(
    (window as any)._phantom ||
    (window as any).__nightmare ||
    navigator.webdriver ||
    (window as any).__puppeteer
  );
}

function isDnt(): boolean {
  return (
    'doNotTrack' in navigator &&
    navigator.doNotTrack === '1'
  );
}

function shouldTrack(): boolean {
  if (!config) return false;
  if (isBot() || isDnt()) return false;
  if (!config.trackLocalhost && typeof window !== 'undefined') {
    const host = window.location?.hostname ?? '';
    if (host === 'localhost' || host === '127.0.0.1') return false;
  }
  return true;
}

function buildPayload(eventName: string, props?: Record<string, string>) {
  return {
    n: eventName,
    u: location.href,
    d: config!.domain,
    r: document.referrer,
    w: window.innerWidth,
    ...(props ? { p: props } : {}),
  };
}

function trackPageview(): void {
  if (!shouldTrack()) return;
  sendEvent(config!.apiHost, config!.domain, config!.key, buildPayload('pageview'));
}

/**
 * Track a custom event.
 */
export function trackEvent(name: string, props?: Record<string, string>): void {
  if (!shouldTrack()) return;
  sendEvent(config!.apiHost, config!.domain, config!.key, buildPayload(name, props));
}

let initialized = false;

/**
 * Initialize the tracker. Automatically sends a pageview.
 * Idempotent: multiple calls do not double-patch History API or duplicate pageviews.
 */
export function init(cfg: TrackerConfig): void {
  config = cfg;

  if (initialized) return;

  // Track initial pageview (once)
  if ((document.visibilityState as string) === 'prerender') {
    document.addEventListener('visibilitychange', () => {
      if (document.visibilityState === 'visible') trackPageview();
    }, { once: true });
  } else {
    trackPageview();
  }

  // SPA support: History API or hash-based routing
  if (cfg.trackSpa !== false) {
    if (cfg.hashMode) {
      onHashNavigation(trackPageview);
    } else {
      onNavigation(trackPageview);
    }
  }
  initialized = true;
}
