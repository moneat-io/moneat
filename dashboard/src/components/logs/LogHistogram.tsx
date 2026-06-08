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

import {useMemo} from 'react'
import {Bar, BarChart, CartesianGrid, ResponsiveContainer, Tooltip, XAxis, YAxis} from 'recharts'
import type {LogAggregateBucket} from '@/lib/api'
import {useTimezone} from '@/hooks/useTimezone'
import {formatDateTime, formatMonthDay, formatTimeHM, parseDate} from '@/lib/date-format'
import {logIntervalToMs} from '@/components/logs/logInterval'

interface LogHistogramProps {
  buckets: LogAggregateBucket[]
  grouped?: boolean
  height?: number
  interval?: string
  rangeFrom?: string
  rangeTo?: string
  onBucketClick?: (timestamp: string) => void
}

interface HistogramPoint {
  timestamp: string
  timestampMs: number
  total: number
  [key: string]: string | number
}

// Level fills use the shared status language; non-level groups fall back to the
// categorical chart palette (style guide).
const LEVEL_COLORS: Record<string, string> = {
  fatal: 'hsl(var(--danger-solid))',
  error: 'hsl(var(--chart-8))',
  warn: 'hsl(var(--warning-solid))',
  info: 'hsl(var(--info-solid))',
  debug: 'hsl(var(--chart-3))',
  trace: 'hsl(var(--muted-foreground))',
}

const GROUP_COLORS = [
  'hsl(var(--chart-1))',
  'hsl(var(--chart-2))',
  'hsl(var(--chart-3))',
  'hsl(var(--chart-4))',
  'hsl(var(--chart-6))',
  'hsl(var(--chart-7))',
  'hsl(var(--chart-9))',
  'hsl(var(--chart-10))',
]
const MAX_BAR_WIDTH_PX = 14

const levelOrder = ['fatal', 'error', 'warn', 'info', 'debug', 'trace']

function colorForGroup(key: string): string {
  if (LEVEL_COLORS[key]) return LEVEL_COLORS[key]
  let hash = 0
  for (let i = 0; i < key.length; i += 1) {
    hash = (hash * 31 + key.charCodeAt(i)) >>> 0
  }
  return GROUP_COLORS[hash % GROUP_COLORS.length]
}

function toTimestampMs(value: string | undefined): number | undefined {
  if (!value) return undefined
  const timestamp = parseDate(value).getTime()
  return Number.isNaN(timestamp) ? undefined : timestamp
}

function formatTooltipValue(value: unknown, name: unknown): [string, string] {
  const numericValue = typeof value === 'number' ? value : Number(value ?? 0)
  const displayValue = Number.isFinite(numericValue) ? numericValue.toLocaleString() : '0'
  return [displayValue, name === 'total' ? 'logs' : String(name ?? '')]
}

export function LogHistogram({
  buckets,
  grouped = true,
  height = 120,
  interval,
  rangeFrom,
  rangeTo,
  onBucketClick,
}: LogHistogramProps) {
  const { timezone } = useTimezone()
  const formatAxisTime = (ts: number, totalRangeMs: number): string => {
    const date = new Date(ts)
    if (totalRangeMs <= 21_600_000) return formatTimeHM(date, timezone)
    if (totalRangeMs <= 172_800_000) {
      return new Intl.DateTimeFormat(undefined, { timeZone: timezone, day: 'numeric', hour: '2-digit' }).format(date)
    }
    return formatMonthDay(date, timezone)
  }
  const formatTooltipTime = (ts: number): string => formatDateTime(ts, timezone)
  const {chartData, groupKeys, totalRangeMs, domainMin, domainMax, estimatedBucketCount} = useMemo(() => {
    const keys = new Set<string>()
    const data: HistogramPoint[] = buckets
      .map((b) => {
        const point: HistogramPoint = {
          timestamp: b.timestamp,
          timestampMs: parseDate(b.timestamp).getTime(),
          total: b.count,
        }
        if (grouped && Object.keys(b.groups).length > 0) {
          for (const [key, val] of Object.entries(b.groups)) {
            keys.add(key)
            point[key] = val
          }
        }
        return point
      })
      .filter((point) => !Number.isNaN(point.timestampMs))
      .sort((a, b) => a.timestampMs - b.timestampMs)
    const sortedKeys = [...keys].sort((a, b) => {
      const ai = levelOrder.indexOf(a)
      const bi = levelOrder.indexOf(b)
      if (ai === -1 && bi === -1) return a.localeCompare(b)
      return (ai === -1 ? 999 : ai) - (bi === -1 ? 999 : bi)
    })
    const firstTs = data[0]?.timestampMs ?? 0
    const lastTs = data[data.length - 1]?.timestampMs ?? firstTs
    const bucketInterval = logIntervalToMs(interval)
    const rangeStartMs = toTimestampMs(rangeFrom)
    const rangeEndMs = toTimestampMs(rangeTo)
    const minFromData = firstTs - bucketInterval / 2
    const maxFromData = lastTs + bucketInterval / 2
    const min = Math.min(rangeStartMs ?? minFromData, minFromData)
    const max = Math.max(rangeEndMs ?? maxFromData, maxFromData)
    const range = Math.max(max - min, bucketInterval)
    return {
      chartData: data,
      groupKeys: sortedKeys,
      totalRangeMs: range,
      domainMin: min,
      domainMax: max,
      estimatedBucketCount: Math.max(1, Math.ceil(range / bucketInterval)),
    }
  }, [buckets, grouped, interval, rangeFrom, rangeTo])

  if (chartData.length === 0) return null

  const useGroups = grouped && groupKeys.length > 0
  const tickCount = Math.min(8, Math.max(3, Math.floor(estimatedBucketCount / 12)))

  return (
    <div className="w-full" style={{height}}>
      <ResponsiveContainer width="100%" height="100%">
        <BarChart
          data={chartData}
          margin={{top: 2, right: 8, left: 0, bottom: 0}}
          barGap={1}
          barCategoryGap="10%"
          onClick={(nextState: unknown) => {
            const ev = nextState as { activePayload?: Array<{ payload: HistogramPoint }> }
            const point = ev?.activePayload?.[0]?.payload
            if (point?.timestamp && onBucketClick) onBucketClick(point.timestamp)
          }}
        >
          <CartesianGrid vertical={false} strokeDasharray="3 3" className="stroke-muted/50" />
          <XAxis
            dataKey="timestampMs"
            type="number"
            domain={[domainMin, domainMax]}
            tickFormatter={(ts) => formatAxisTime(ts, totalRangeMs)}
            fontSize={10}
            height={18}
            minTickGap={40}
            tickCount={tickCount}
            tickLine={false}
            axisLine={false}
            className="fill-muted-foreground"
          />
          <YAxis
            allowDecimals={false}
            fontSize={10}
            tickLine={false}
            axisLine={false}
            width={40}
            tickFormatter={(v: number) => {
              if (v >= 1_000_000) return `${(v / 1_000_000).toFixed(v % 1_000_000 === 0 ? 0 : 1)}M`
              if (v >= 1_000) return `${(v / 1_000).toFixed(v % 1_000 === 0 ? 0 : 1)}k`
              return String(v)
            }}
            className="fill-muted-foreground"
          />
          <Tooltip
            cursor={false}
            wrapperStyle={{zIndex: 30}}
            labelFormatter={(ts) => formatTooltipTime(ts as number)}
            formatter={formatTooltipValue}
            contentStyle={{
              backgroundColor: 'hsl(var(--popover) / 0.95)',
              border: '1px solid hsl(var(--border))',
              borderRadius: '6px',
              color: 'hsl(var(--popover-foreground))',
              padding: '8px 12px',
              fontSize: '12px',
            }}
          />
          {useGroups ? (
            groupKeys.map((key) => (
              <Bar
                key={key}
                dataKey={key}
                stackId="1"
                fill={colorForGroup(key)}
                isAnimationActive={false}
                maxBarSize={MAX_BAR_WIDTH_PX}
                radius={[1, 1, 0, 0]}
              />
            ))
          ) : (
            <Bar
              dataKey="total"
              fill={LEVEL_COLORS.error}
              isAnimationActive={false}
              maxBarSize={MAX_BAR_WIDTH_PX}
              radius={[1, 1, 0, 0]}
            />
          )}
        </BarChart>
      </ResponsiveContainer>
    </div>
  )
}
