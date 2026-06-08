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

import {useState} from 'react'
import {
  Area,
  AreaChart,
  Bar,
  BarChart,
  CartesianGrid,
  ReferenceLine,
  ResponsiveContainer,
} from 'recharts'
import {LineChart as LineChartIcon} from 'lucide-react'
import {cn} from '@/lib/utils'
import {useTelemetry, type TelemetrySeriesKey} from '../overviewMockData'
import {OverviewPanel} from '../OverviewPanel'

interface TabDef {
  key: TelemetrySeriesKey
  label: string
  legend: string
  color: string
}

const TABS: TabDef[] = [
  {key: 'errors', label: 'Errors', legend: 'error rate', color: 'hsl(var(--danger-solid))'},
  {key: 'latency', label: 'Latency', legend: 'p95 latency', color: 'hsl(var(--primary))'},
  {key: 'throughput', label: 'Throughput', legend: 'requests/min', color: 'hsl(var(--chart-2))'},
  {key: 'logs', label: 'Logs', legend: 'log volume', color: 'hsl(var(--info-solid))'},
]

const X_LABELS = ['00:00', '04:00', '08:00', '12:00', '16:00', '20:00', 'now']

/** Tabbed telemetry chart (errors / latency / throughput / logs). */
export function TelemetryWidget() {
  const telem = useTelemetry()
  const [activeKey, setActiveKey] = useState<TelemetrySeriesKey>('errors')
  const tab = TABS.find((t) => t.key === activeKey) ?? TABS[0]

  const series = telem[activeKey]
  const data = series.map((value, i) => ({i, value}))
  const deployIndex = Math.round((telem.deployAtPct / 100) * (series.length - 1))

  return (
    <OverviewPanel
      testId="widget-telemetry"
      title="Telemetry"
      icon={LineChartIcon}
      actions={
        <span className="flex items-center gap-1.5 text-xs text-muted-foreground">
          <span
            className="inline-block h-2 w-2 rounded-sm"
            style={{background: tab.color}}
            aria-hidden="true"
          />
          {tab.legend}
        </span>
      }
    >
      <div className="mb-2 inline-flex rounded-md border bg-muted/40 p-0.5">
        {TABS.map((t) => (
          <button
            key={t.key}
            type="button"
            onClick={() => setActiveKey(t.key)}
            className={cn(
              'rounded-sm px-2.5 py-1 text-xs font-medium transition-colors',
              t.key === activeKey
                ? 'bg-background text-foreground shadow-sm'
                : 'text-muted-foreground hover:text-foreground',
            )}
          >
            {t.label}
          </button>
        ))}
      </div>

      <div className="h-[150px] w-full">
        <ResponsiveContainer width="100%" height="100%">
          {activeKey === 'logs' ? (
            <BarChart data={data} margin={{top: 6, right: 6, left: 0, bottom: 0}}>
              <CartesianGrid vertical={false} stroke="hsl(var(--border))" strokeOpacity={0.6} />
              <ReferenceLine
                x={deployIndex}
                stroke="hsl(var(--primary))"
                strokeDasharray="4 3"
                label={{value: telem.deployLabel, position: 'top', fontSize: 10, fill: 'hsl(var(--primary))'}}
              />
              <Bar dataKey="value" fill={tab.color} radius={[1, 1, 0, 0]} />
            </BarChart>
          ) : (
            <AreaChart data={data} margin={{top: 6, right: 6, left: 0, bottom: 0}}>
              <defs>
                <linearGradient id="overviewTelemetryFill" x1="0" y1="0" x2="0" y2="1">
                  <stop offset="0" stopColor={tab.color} stopOpacity={0.3} />
                  <stop offset="1" stopColor={tab.color} stopOpacity={0} />
                </linearGradient>
              </defs>
              <CartesianGrid vertical={false} stroke="hsl(var(--border))" strokeOpacity={0.6} />
              {activeKey === 'errors' && (
                <ReferenceLine
                  y={50}
                  stroke="hsl(var(--danger-solid))"
                  strokeDasharray="5 4"
                  strokeOpacity={0.8}
                  label={{value: 'alert: 5xx > 2%', position: 'insideTopRight', fontSize: 10, fill: 'hsl(var(--danger-fg))'}}
                />
              )}
              <ReferenceLine
                x={deployIndex}
                stroke="hsl(var(--primary))"
                strokeDasharray="4 3"
                label={{value: telem.deployLabel, position: 'top', fontSize: 10, fill: 'hsl(var(--primary))'}}
              />
              <Area
                type="monotone"
                dataKey="value"
                stroke={tab.color}
                strokeWidth={2}
                fill="url(#overviewTelemetryFill)"
                isAnimationActive={false}
              />
            </AreaChart>
          )}
        </ResponsiveContainer>
      </div>

      <div className="mt-1 flex justify-between px-1 font-mono text-[10px] text-muted-foreground/70">
        {X_LABELS.map((l) => (
          <span key={l}>{l}</span>
        ))}
      </div>
    </OverviewPanel>
  )
}
