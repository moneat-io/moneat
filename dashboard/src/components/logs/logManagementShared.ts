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

import {useQuery} from '@tanstack/react-query'
import {api, type LogAggregateBucket, type LogMonitorCondition} from '@/lib/api'

/** Constants and logic shared by the log-management surfaces (the management
 * sheet and the Explorer's focused create dialogs) so the query / level /
 * group-by / threshold controls behave identically across both. Presentational
 * controls live in LogManagementControls.tsx. */

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

/** Defaults shared by the monitor create surfaces. */
export const DEFAULT_MONITOR_THRESHOLD = 10
export const MIN_MONITOR_THRESHOLD = 0
export const DEFAULT_MONITOR_WINDOW_MINUTES = 5
export const MIN_MONITOR_WINDOW_MINUTES = 1

export function toGroupByValue(groupBy: string): string | null {
  return groupBy === GROUP_BY_NONE ? null : groupBy
}

/** Map a stored group-by field ('' | null | field) onto a select value. */
export function toGroupBySelectValue(groupBy: string | null | undefined): string {
  return groupBy ? groupBy : GROUP_BY_NONE
}

/** Returns the single active level when the filter narrows to exactly one,
 * otherwise null. Empty (or every level) reads as "all", so there's nothing to
 * single out. */
export function soleLevel(levels: string[]): string | null {
  const active = Array.from(new Set(levels.map((level) => level.trim()).filter(Boolean)))
  return active.length === 1 ? active[0] : null
}

/** A "mostly completed" monitor name seeded from the active filter. Only
 * suggests when there's a clear signal (a single level); otherwise returns ''
 * so the field stays empty rather than guessing. */
export function suggestMonitorName(levels: string[]): string {
  const level = soleLevel(levels)
  if (level === 'error' || level === 'fatal') return `High ${level} log volume`
  if (level) return `${level} log volume`
  return ''
}

export interface LogVolumeResult {
  total: number
  buckets: LogAggregateBucket[]
  isFetching: boolean
  isError: boolean
}

/** Counts matching logs over a window so create surfaces can show real volume
 * (and let the user set an informed threshold) without inventing numbers. */
export function useLogVolume(params: {
  query?: string
  levels: string[]
  groupBy?: string | null
  from?: string
  to?: string
  enabled?: boolean
}): LogVolumeResult {
  const {query, levels, groupBy, from, to, enabled = true} = params
  const result = useQuery({
    queryKey: ['log-volume-preview', query ?? '', levels.join(','), groupBy ?? '', from ?? '', to ?? ''],
    queryFn: () =>
      api.getLogAggregate({
        from,
        to,
        query: query?.trim() ? query : undefined,
        levels: levels.length > 0 ? levels : undefined,
        groupBy: groupBy ?? undefined,
      }),
    enabled,
    retry: false,
    staleTime: 30_000,
  })
  return {
    total: result.data?.totalCount ?? 0,
    buckets: result.data?.buckets ?? [],
    isFetching: result.isFetching,
    isError: result.isError,
  }
}
