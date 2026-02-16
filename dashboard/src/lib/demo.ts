/**
 * Demo mode virtual clock utilities.
 * When in demo mode, all time operations use a fake "now" (the demo epoch)
 * instead of the actual current time.
 */

let demoEpochMs: number | null = null;

/**
 * Set the demo epoch. Call this after demo login or when loading user data.
 */
export function setDemoEpoch(ms: number | null) {
  demoEpochMs = ms;
}

/**
 * Check if currently in demo mode.
 */
export function isDemo(): boolean {
  return demoEpochMs !== null;
}

/**
 * Get the current timestamp in milliseconds.
 * In demo mode, returns the demo epoch; otherwise returns actual current time.
 */
export function getNow(): number {
  return demoEpochMs ?? Date.now();
}

/**
 * Get the current Date object.
 * In demo mode, returns a Date at the demo epoch; otherwise returns actual current time.
 */
export function getNowDate(): Date {
  return new Date(getNow());
}

/**
 * Get the demo epoch timestamp (for debugging/display).
 */
export function getDemoEpochMs(): number | null {
  return demoEpochMs;
}
