/**
 * IIFE entry point for <script> tag usage.
 * Reads configuration from data attributes on the script element:
 *   <script defer data-domain="myapp.com" data-key="abc123" src="https://moneat.example.com/js/m.js"></script>
 */
import { sendEvent } from './request';
import { onNavigation } from './spa';

(function () {
  'use strict';

  const script = document.currentScript as HTMLScriptElement | null;
  if (!script) return;

  const domain = script.getAttribute('data-domain');
  const key = script.getAttribute('data-key');
  const apiHost = script.getAttribute('data-api') || new URL(script.src).origin;

  if (!domain || !key) return;

  function isBot(): boolean {
    return !!(
      (window as any)._phantom ||
      (window as any).__nightmare ||
      navigator.webdriver ||
      (window as any).__puppeteer
    );
  }

  function isDnt(): boolean {
    return 'doNotTrack' in navigator && navigator.doNotTrack === '1';
  }

  function trackPageview(): void {
    if (isBot() || isDnt()) return;

    sendEvent(apiHost, domain!, key!, {
      n: 'pageview',
      u: location.href,
      d: domain!,
      r: document.referrer,
      w: window.innerWidth,
    });
  }

  function trackCustomEvent(name: string, props?: Record<string, string>): void {
    if (isBot() || isDnt()) return;

    sendEvent(apiHost, domain!, key!, {
      n: name,
      u: location.href,
      d: domain!,
      r: document.referrer,
      w: window.innerWidth,
      ...(props ? { p: props } : {}),
    });
  }

  // SPA navigation support
  onNavigation(trackPageview);

  // Initial pageview
  if ((document.visibilityState as string) === 'prerender') {
    document.addEventListener('visibilitychange', () => {
      if (document.visibilityState === 'visible') trackPageview();
    }, { once: true });
  } else {
    trackPageview();
  }

  // Expose global API
  (window as any).moneat = { track: trackCustomEvent };
})();
