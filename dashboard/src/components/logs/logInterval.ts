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

const MINUTE_MS = 60_000
const HOUR_MS = 60 * MINUTE_MS
const DAY_MS = 24 * HOUR_MS

export function logIntervalToMs(interval: string | undefined): number {
  switch (interval) {
    case '1m':
      return MINUTE_MS
    case '5m':
      return 5 * MINUTE_MS
    case '15m':
      return 15 * MINUTE_MS
    case '1h':
      return HOUR_MS
    case '2h':
      return 2 * HOUR_MS
    case '4h':
      return 4 * HOUR_MS
    case '12h':
      return 12 * HOUR_MS
    case '1d':
      return DAY_MS
    default:
      return HOUR_MS
  }
}
