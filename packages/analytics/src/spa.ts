/**
 * SPA navigation detection.
 * Patches History API and listens for popstate to detect client-side navigations.
 */
export function onNavigation(callback: () => void): void {
  // Patch pushState
  const origPush = history.pushState;
  history.pushState = function (...args) {
    origPush.apply(this, args);
    callback();
  };

  // Patch replaceState
  const origReplace = history.replaceState;
  history.replaceState = function (...args) {
    origReplace.apply(this, args);
    callback();
  };

  // Listen for back/forward navigation
  window.addEventListener('popstate', callback);
}
