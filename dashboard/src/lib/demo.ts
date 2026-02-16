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
