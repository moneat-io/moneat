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
 * Centralized timezone-aware date formatting.
 * All functions accept an IANA timezone identifier (e.g. "America/New_York").
 * Use useTimezone() to obtain the current user's timezone preference.
 */

function toDate(date: Date | string | number): Date {
  if (date instanceof Date) return date
  return new Date(date)
}

/** Full date + time: "Jan 15, 2:34:56 PM" */
export function formatDateTime(date: Date | string | number, timezone: string): string {
  const d = toDate(date)
  if (isNaN(d.getTime())) return String(date)
  return new Intl.DateTimeFormat(undefined, {
    timeZone: timezone,
    month: 'short',
    day: 'numeric',
    hour: '2-digit',
    minute: '2-digit',
    second: '2-digit',
  }).format(d)
}

/** Date only: "Jan 15, 2025" */
export function formatDate(date: Date | string | number, timezone: string): string {
  const d = toDate(date)
  if (isNaN(d.getTime())) return String(date)
  return new Intl.DateTimeFormat(undefined, {
    timeZone: timezone,
    year: 'numeric',
    month: 'short',
    day: 'numeric',
  }).format(d)
}

/** Month + day only: "Jan 15" */
export function formatMonthDay(date: Date | string | number, timezone: string): string {
  const d = toDate(date)
  if (isNaN(d.getTime())) return String(date)
  return new Intl.DateTimeFormat(undefined, {
    timeZone: timezone,
    month: 'short',
    day: 'numeric',
  }).format(d)
}

/** Time only: "02:34:56" (24h) */
export function formatTime(date: Date | string | number, timezone: string): string {
  const d = toDate(date)
  if (isNaN(d.getTime())) return String(date)
  return new Intl.DateTimeFormat(undefined, {
    timeZone: timezone,
    hour: '2-digit',
    minute: '2-digit',
    second: '2-digit',
    hour12: false,
  }).format(d)
}

/** Hour + minute only: "02:34" (24h) */
export function formatTimeHM(date: Date | string | number, timezone: string): string {
  const d = toDate(date)
  if (isNaN(d.getTime())) return String(date)
  return new Intl.DateTimeFormat(undefined, {
    timeZone: timezone,
    hour: '2-digit',
    minute: '2-digit',
    hour12: false,
  }).format(d)
}

/** Hour + minute, 12h format: "2:34 PM" */
export function formatTimeHM12(date: Date | string | number, timezone: string): string {
  const d = toDate(date)
  if (isNaN(d.getTime())) return String(date)
  return new Intl.DateTimeFormat(undefined, {
    timeZone: timezone,
    hour: 'numeric',
    minute: '2-digit',
    hour12: true,
  }).format(d)
}

/** Full date + time with milliseconds: "Jan 15, 02:34:56.789" */
export function formatDateTimeWithMs(date: Date | string | number, timezone: string): string {
  const d = toDate(date)
  if (isNaN(d.getTime())) return String(date)
  const base = new Intl.DateTimeFormat(undefined, {
    timeZone: timezone,
    month: 'short',
    day: 'numeric',
    hour: '2-digit',
    minute: '2-digit',
    second: '2-digit',
  }).format(d)
  const ms = String(d.getMilliseconds()).padStart(3, '0')
  return `${base}.${ms}`
}

/** Time with milliseconds: "02:34:56.789" (24h) */
export function formatTimeWithMs(date: Date | string | number, timezone: string): string {
  const d = toDate(date)
  if (isNaN(d.getTime())) return String(date)
  const base = new Intl.DateTimeFormat(undefined, {
    timeZone: timezone,
    hour: '2-digit',
    minute: '2-digit',
    second: '2-digit',
    hour12: false,
  }).format(d)
  const ms = String(d.getMilliseconds()).padStart(3, '0')
  return `${base}.${ms}`
}

/** Full datetime with weekday and timezone name: "Mon, Jan 15, 2025, 02:34:56 EST" */
export function formatDateTimeFull(date: Date | string | number, timezone: string): string {
  const d = toDate(date)
  if (isNaN(d.getTime())) return String(date)
  return new Intl.DateTimeFormat(undefined, {
    timeZone: timezone,
    weekday: 'short',
    year: 'numeric',
    month: 'short',
    day: 'numeric',
    hour: '2-digit',
    minute: '2-digit',
    second: '2-digit',
    timeZoneName: 'short',
  }).format(d)
}

/** Returns the browser's current timezone identifier */
export function browserTimezone(): string {
  return Intl.DateTimeFormat().resolvedOptions().timeZone
}
