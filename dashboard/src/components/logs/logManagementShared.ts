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

import type {LogMonitorCondition} from '@/lib/api'

/** Constants shared by the log-management surfaces (the management sheet and the
 * Explorer's focused create dialogs) so query/level/group-by/threshold controls
 * stay identical across both. Components live in LogManagementControls.tsx. */

export const KNOWN_LEVELS = ['trace', 'debug', 'info', 'warn', 'error', 'fatal']

export const GROUP_BY_NONE = 'none'

export const GROUP_BY_OPTIONS: ReadonlyArray<{value: string; label: string}> = [
  {value: GROUP_BY_NONE, label: 'No grouping'},
  {value: 'level', label: 'level'},
  {value: 'service', label: 'service'},
  {value: 'environment', label: 'environment'},
]

/** Threshold comparators a log monitor can use, in menu order. */
export const LOG_MONITOR_CONDITIONS: readonly LogMonitorCondition[] = ['>', '>=', '<', '<=', '==']

export function toGroupByValue(groupBy: string): string | null {
  return groupBy === GROUP_BY_NONE ? null : groupBy
}
