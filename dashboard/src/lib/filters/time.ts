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

export interface TimeRangePreset {
  label: string
  value: string
  minutes: number
}

/** The full preset set the log viewer ships; surfaces may pass a subset. */
export const TIME_PRESETS: TimeRangePreset[] = [
  {label: 'Last 5 minutes', value: '5m', minutes: 5},
  {label: 'Last 15 minutes', value: '15m', minutes: 15},
  {label: 'Last 30 minutes', value: '30m', minutes: 30},
  {label: 'Last 1 hour', value: '1h', minutes: 60},
  {label: 'Last 4 hours', value: '4h', minutes: 240},
  {label: 'Last 12 hours', value: '12h', minutes: 720},
  {label: 'Last 24 hours', value: '24h', minutes: 1440},
  {label: 'Last 3 days', value: '3d', minutes: 4320},
  {label: 'Last 7 days', value: '7d', minutes: 10080},
  {label: 'Last 14 days', value: '14d', minutes: 20160},
  {label: 'Last 30 days', value: '30d', minutes: 43200},
]
