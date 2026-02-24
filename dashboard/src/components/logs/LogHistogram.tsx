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
import {formatDateTime, formatMonthDay, formatTimeHM} from '@/lib/date-format'

interface LogHistogramProps {
  buckets: LogAggregateBucket[]
  grouped?: boolean
  height?: number
  onBucketClick?: (timestamp: string) => void
}

interface HistogramPoint {
  timestamp: string
  timestampMs: number
  total: number
  [key: string]: string | number
}

const LEVEL_COLORS: Record<string, string> = {
  fatal: '#e11d48',
  error: '#ef4444',
  warn: '#f59e0b',
  info: '#6366f1',
  debug: '#14b8a6',
  trace: '#a1a1aa',
}

const GROUP_COLORS = ['#ef4444', '#f59e0b', '#6366f1', '#14b8a6', '#a855f7', '#22c55e', '#ec4899', '#0ea5e9']

const levelOrder = ['fatal', 'error', 'warn', 'info', 'debug', 'trace']

function colorForGroup(key: string): string {
  if (LEVEL_COLORS[key]) return LEVEL_COLORS[key]
  let hash = 0
  for (let i = 0; i < key.length; i += 1) {
    hash = (hash * 31 + key.charCodeAt(i)) >>> 0
  }
  return GROUP_COLORS[hash % GROUP_COLORS.length]
}

export function LogHistogram({buckets, grouped = true, height = 120, onBucketClick}: LogHistogramProps) {
  const { timezone } = useTimezone()
  const formatAxisTime = (ts: number, totalRangeMs: number): string => {
    const date = new Date(ts)
    if (totalRangeMs <= 21_600_000) return formatTimeHM(date, timezone)
    if (totalRangeMs <= 172_800_000) {
      return new Intl.DateTimeFormat(undefined, { timeZone: timezone, day: 'numeric', hour: '2-digit' }).format(date)
    }
    return formatMonthDay(date, timezone)
  }
  const formatTooltipTime = (ts: number): string => formatDateTime(new Date(ts), timezone)
  const {chartData, groupKeys, totalRangeMs, domainMin, domainMax} = useMemo(() => {
    const keys = new Set<string>()
    const data: HistogramPoint[] = buckets.map((b) => {
      const point: HistogramPoint = {
        timestamp: b.timestamp,
        timestampMs: new Date(b.timestamp).getTime(),
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
    const sortedKeys = [...keys].sort((a, b) => {
      const ai = levelOrder.indexOf(a)
      const bi = levelOrder.indexOf(b)
      if (ai === -1 && bi === -1) return a.localeCompare(b)
      return (ai === -1 ? 999 : ai) - (bi === -1 ? 999 : bi)
    })
    const timestamps = data.map((d) => d.timestampMs)
    const minTs = timestamps.length > 0 ? Math.min(...timestamps) : 0
    const maxTs = timestamps.length > 0 ? Math.max(...timestamps) : minTs
    return {
      chartData: data,
      groupKeys: sortedKeys,
      totalRangeMs: Math.max(maxTs - minTs, 60_000),
    }
  }, [buckets, grouped])

  if (chartData.length === 0) return null

  const useGroups = grouped && groupKeys.length > 0
  const tickCount = Math.min(8, Math.max(3, Math.floor(chartData.length / 12)))

  return (
    <div className="w-full" style={{height}}>
      <ResponsiveContainer width="100%" height="100%">
        <BarChart
          data={chartData}
          margin={{top: 2, right: 8, left: 0, bottom: 0}}
          barGap={1}
          barCategoryGap="10%"
          onClick={(event) => {
            const point = event?.activePayload?.[0]?.payload as HistogramPoint | undefined
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
            labelFormatter={(ts) => formatTooltipTime(ts as number)}
            formatter={(value: number, name: string) => [value.toLocaleString(), name === 'total' ? 'logs' : name]}
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
                radius={[1, 1, 0, 0]}
              />
            ))
          ) : (
            <Bar
              dataKey="total"
              fill={LEVEL_COLORS.error}
              isAnimationActive={false}
              radius={[1, 1, 0, 0]}
            />
          )}
        </BarChart>
      </ResponsiveContainer>
    </div>
  )
}
