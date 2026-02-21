let historyPatched = false;

/**
 * SPA navigation detection.
 * Patches History API and listens for popstate to detect client-side navigations.
 * Idempotent: patching is done at most once.
 */
export function onNavigation(callback: () => void): void {
  if (historyPatched) return;
  historyPatched = true;

  const origPush = history.pushState;
  history.pushState = function (...args) {
    origPush.apply(this, args);
    callback();
  };

  const origReplace = history.replaceState;
  history.replaceState = function (...args) {
    origReplace.apply(this, args);
    callback();
  };

  window.addEventListener('popstate', callback);
}

/**
 * Hash-based routing: listen for hashchange instead of History API.
 */
export function onHashNavigation(callback: () => void): void {
  window.addEventListener('hashchange', callback);
}
