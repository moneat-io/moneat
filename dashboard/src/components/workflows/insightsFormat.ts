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

/** Formats a 0..1 ratio as a whole-number percentage string (e.g. 0.953 -> "95%"). */
export function formatRate(rate: number): string {
  if (!Number.isFinite(rate)) return '0%'
  const clamped = Math.min(1, Math.max(0, rate))
  return Math.round(clamped * 100) + '%'
}

/** Formats an integer with locale grouping; falls back to "0" for non-finite input. */
export function formatCount(value: number): string {
  if (!Number.isFinite(value)) return '0'
  return Math.round(value).toLocaleString()
}

/** Formats an ISO timestamp as a locale date-time string; returns "—" when absent and the raw value when unparseable. */
export function formatDateTime(value?: string | null): string {
  if (!value) return '—'
  const date = new Date(value)
  if (Number.isNaN(date.getTime())) return value
  return date.toLocaleString()
}
