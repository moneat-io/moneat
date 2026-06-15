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

import {formatDateTime, formatMonthDay, formatTimeHM} from './date-format'

const MILLISECONDS_PER_SECOND = 1000
const SECONDS_PER_DAY = 86_400
const LONG_RANGE_COMPACT_SECONDS = 7 * SECONDS_PER_DAY
const DEFAULT_MAX_CHART_POINTS = 360

type HostMetricValue = number | null | undefined
type HostMetricBucket = {
  count: number
  metricCounts: Record<string, number>
  metricSums: Record<string, number>
  timestampSum: number
}

export type HostMetricChartDatum = {
  timestamp: number
  [metric: string]: HostMetricValue
}

export function toHostMetricChartTimestamp(timestampSeconds: number): number {
  return timestampSeconds * MILLISECONDS_PER_SECOND
}

export function buildHostMetricQueryRange(rangeSeconds: number, now: Date): readonly [string, string] {
  const toMs = now.getTime()
  const fromMs = toMs - rangeSeconds * MILLISECONDS_PER_SECOND
  return [
    Math.floor(fromMs / MILLISECONDS_PER_SECOND).toString(),
    Math.floor(toMs / MILLISECONDS_PER_SECOND).toString(),
  ]
}

export function formatHostMetricAxisTick(
  value: string | number,
  rangeSeconds: number,
  timezone: string,
): string {
  const timestamp = Number(value)
  if (Number.isNaN(timestamp)) return String(value)

  const date = new Date(timestamp)
  if (rangeSeconds > SECONDS_PER_DAY) return formatMonthDay(date, timezone)
  return formatTimeHM(date, timezone)
}

export function formatHostMetricTooltipLabel(value: string | number | undefined, timezone: string): string {
  if (value === undefined) return ''

  const timestamp = Number(value)
  if (Number.isNaN(timestamp)) return String(value)
  return formatDateTime(timestamp, timezone)
}

export function compactHostMetricChartData<T extends HostMetricChartDatum>(
  data: T[],
  rangeSeconds: number,
  metricKeys: readonly (keyof T & string)[],
  maxPoints = DEFAULT_MAX_CHART_POINTS,
): T[] {
  if (rangeSeconds <= LONG_RANGE_COMPACT_SECONDS || data.length <= maxPoints || maxPoints <= 0) {
    return data
  }

  const firstTimestamp = data[0]?.timestamp
  const lastTimestamp = data[data.length - 1]?.timestamp
  if (!Number.isFinite(firstTimestamp) || !Number.isFinite(lastTimestamp) || lastTimestamp <= firstTimestamp) {
    return data
  }

  const spanMs = lastTimestamp - firstTimestamp + 1
  const bucketSizeMs = Math.ceil(spanMs / maxPoints)
  const buckets = new Map<number, HostMetricBucket>()

  for (const point of data) {
    const bucketIndex = Math.min(
      maxPoints - 1,
      Math.floor((point.timestamp - firstTimestamp) / bucketSizeMs),
    )
    const bucket = buckets.get(bucketIndex) ?? {
      count: 0,
      metricCounts: {},
      metricSums: {},
      timestampSum: 0,
    }

    bucket.count += 1
    bucket.timestampSum += point.timestamp
    for (const metricKey of metricKeys) {
      const value = point[metricKey]
      if (typeof value !== 'number' || !Number.isFinite(value)) continue

      bucket.metricSums[metricKey] = (bucket.metricSums[metricKey] ?? 0) + value
      bucket.metricCounts[metricKey] = (bucket.metricCounts[metricKey] ?? 0) + 1
    }
    buckets.set(bucketIndex, bucket)
  }

  return [...buckets.values()].map((bucket) => {
    const compacted: HostMetricChartDatum = {
      timestamp: Math.round(bucket.timestampSum / bucket.count),
    }

    for (const metricKey of metricKeys) {
      const metricCount = bucket.metricCounts[metricKey] ?? 0
      compacted[metricKey] = metricCount > 0
        ? bucket.metricSums[metricKey] / metricCount
        : null
    }

    return compacted as T
  })
}
