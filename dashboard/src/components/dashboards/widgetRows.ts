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

import type {BatchQueryResult, BatchQueryResultMetadata, QueryDsl, TimeRangeDef} from '@/lib/api'
import {api} from '@/lib/api'

export const TIME_KEYS = new Set(['time_bucket', 'timestamp', 'time', 'Time', 'day', 'Day'])

const GENERATED_REF_IDS = ['A', 'B', 'C', 'D', 'E', 'F', 'G', 'H', 'I', 'J']

type WidgetRows = Record<string, unknown>[]

type WidgetDataQueryOptions = Readonly<{
  dashboardId: string
  projectId?: string
  queries: QueryDsl[]
  isBatch: boolean
  timeRange: TimeRangeDef
  variables?: Record<string, string>
}>

function queryForBatchResult(
  queries: QueryDsl[],
  refId: string,
  metadata?: BatchQueryResultMetadata,
): QueryDsl | undefined {
  if (metadata?.original_ref_id != null) {
    const byOriginalRefId = queries.find(q => q.ref_id === metadata.original_ref_id)
    if (byOriginalRefId != null) return byOriginalRefId
  }

  const byResponseRefId = queries.find(q => q.ref_id === refId)
  if (byResponseRefId != null) return byResponseRefId

  const generatedIndex = metadata?.query_index ?? GENERATED_REF_IDS.indexOf(refId)
  return generatedIndex >= 0 ? queries[generatedIndex] : undefined
}

export async function fetchWidgetRows({
  dashboardId,
  projectId,
  queries,
  isBatch,
  timeRange,
  variables,
}: WidgetDataQueryOptions): Promise<WidgetRows> {
  if (!projectId || queries.length === 0) return []
  if (!isBatch) {
    return api.executeWidgetQuery(dashboardId, queries[0], projectId, timeRange, variables)
  }

  const result = await api.executeBatchQuery(dashboardId, queries, projectId, timeRange, variables)
  return mergeBatchQueryRows(result, queries)
}

export function mergeBatchQueryRows(result: BatchQueryResult, queries: QueryDsl[]): WidgetRows {
  const mergedByTime = new Map<unknown, Record<string, unknown>>()
  Object.entries(result.results).forEach(([refId, rows]) => {
    const metadata = result.metadata?.[refId]
    if (queries.length === 1) {
      mergeSingleQueryRows(mergedByTime, rows)
    } else {
      mergeMultiQueryRows(mergedByTime, queries, refId, rows, metadata)
    }
  })
  return Array.from(mergedByTime.values())
}

function mergeSingleQueryRows(mergedByTime: Map<unknown, Record<string, unknown>>, rows: WidgetRows) {
  rows.forEach((row, index) => {
    const timeValue = Object.entries(row).find(([key]) => TIME_KEYS.has(key))?.[1]
    Object.assign(ensureMergedRow(mergedByTime, timeValue ?? index), row)
  })
}

function mergeMultiQueryRows(
  mergedByTime: Map<unknown, Record<string, unknown>>,
  queries: QueryDsl[],
  refId: string,
  rows: WidgetRows,
  metadata?: BatchQueryResultMetadata,
) {
  const query = queryForBatchResult(queries, refId, metadata)
  const alias = query?.metrics?.[0]?.alias
  const labelRefId = metadata?.original_ref_id ?? refId
  rows.forEach((row) => mergeMultiQueryRow(mergedByTime, row, alias, labelRefId))
}

function mergeMultiQueryRow(
  mergedByTime: Map<unknown, Record<string, unknown>>,
  row: Record<string, unknown>,
  alias: string | null | undefined,
  labelRefId: string,
) {
  const timeEntry = Object.entries(row).find(([key]) => TIME_KEYS.has(key))
  if (timeEntry == null) return
  Object.assign(
    ensureMergedRow(mergedByTime, timeEntry[1], {[timeEntry[0]]: timeEntry[1]}),
    numericSeriesValues(row, alias, labelRefId)
  )
}

function ensureMergedRow(
  mergedByTime: Map<unknown, Record<string, unknown>>,
  key: unknown,
  initial: Record<string, unknown> = {},
) {
  let row = mergedByTime.get(key)
  if (row == null) {
    row = {...initial}
    mergedByTime.set(key, row)
  }
  return row
}

function numericSeriesValues(
  row: Record<string, unknown>,
  alias: string | null | undefined,
  labelRefId: string,
) {
  return Object.fromEntries(
    Object.entries(row)
      .filter(([key, value]) => !TIME_KEYS.has(key) && typeof value === 'number')
      .map(([key, value]) => [alias || `${labelRefId}: ${key}`, value])
  )
}
