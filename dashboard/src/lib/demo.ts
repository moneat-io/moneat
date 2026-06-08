// Moneat - observability platform
// Copyright (C) 2026 Moneat
//
// This program is free software: you can redistribute it and/or modify
// it under the terms of the GNU Affero General Public License as published by
// the Free Software Foundation, either version 3 of the License, or
// (at your option) any later version.
//
// This program is distributed in the hope that it will be useful,
// but WITHOUT ANY WARRANTY; without even the implied warranty of
// MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the
// GNU Affero General Public License for more details.
//
// You should have received a copy of the GNU Affero General Public License
// along with this program. If not, see <https://www.gnu.org/licenses/>.

/**
 * Demo mode virtual clock utilities.
 * When in demo mode, all time operations use a fake "now" (the demo epoch)
 * instead of the actual current time.
 */

type DemoEpochSource = {
  demoEpochMs?: number | null
}

let demoEpochMs: number | null = null;

function readStoredDemoEpoch(): number | null {
  const stored = globalThis.sessionStorage?.getItem('demoEpochMs') ?? null;
  if (stored === null) return null;

  const storedEpochMs = Number(stored);
  if (!Number.isFinite(storedEpochMs)) {
    setDemoEpoch(null);
    return null;
  }

  return storedEpochMs;
}

function getActiveDemoEpoch(): number | null {
  if (demoEpochMs !== null) return demoEpochMs;

  const storedEpochMs = readStoredDemoEpoch();
  if (storedEpochMs !== null) {
    demoEpochMs = storedEpochMs;
  }

  return storedEpochMs;
}

/**
 * Set the demo epoch. Call this after demo login or when loading user data.
 */
export function setDemoEpoch(ms: number | null) {
  demoEpochMs = ms;
  if (ms !== null) {
    globalThis.sessionStorage?.setItem('demoEpochMs', String(ms));
  } else {
    globalThis.sessionStorage?.removeItem('demoEpochMs');
  }
}

/**
 * Sync local demo state from the authenticated user response.
 */
export function syncDemoEpochFromUser(user: DemoEpochSource | null | undefined) {
  setDemoEpoch(user?.demoEpochMs ?? null);
}

/**
 * Check if currently in demo mode.
 */
export function isDemo(): boolean {
  return getActiveDemoEpoch() !== null;
}

/**
 * Get the current timestamp in milliseconds.
 * In demo mode, returns the demo epoch; otherwise returns actual current time.
 */
export function getNow(): number {
  const activeDemoEpoch = getActiveDemoEpoch();
  if (activeDemoEpoch !== null) return activeDemoEpoch;

  return Date.now();
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
  return getActiveDemoEpoch();
}
