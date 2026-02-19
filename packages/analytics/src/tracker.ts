import { sendEvent } from './request';
import { onNavigation } from './spa';

export interface TrackerConfig {
  domain: string;
  apiHost: string;
  key: string;
  trackSpa?: boolean;
  respectDnt?: boolean;
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
    config?.respectDnt !== false &&
    'doNotTrack' in navigator &&
    navigator.doNotTrack === '1'
  );
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
  if (!config || isBot() || isDnt()) return;
  sendEvent(config.apiHost, config.domain, config.key, buildPayload('pageview'));
}

/**
 * Track a custom event.
 */
export function trackEvent(name: string, props?: Record<string, string>): void {
  if (!config || isBot() || isDnt()) return;
  sendEvent(config.apiHost, config.domain, config.key, buildPayload(name, props));
}

/**
 * Initialize the tracker. Automatically sends a pageview.
 */
export function init(cfg: TrackerConfig): void {
  config = cfg;

  // Track initial pageview
  if ((document.visibilityState as string) === 'prerender') {
    document.addEventListener('visibilitychange', () => {
      if (document.visibilityState === 'visible') trackPageview();
    }, { once: true });
  } else {
    trackPageview();
  }

  // SPA support
  if (cfg.trackSpa !== false) {
    onNavigation(trackPageview);
  }
}
