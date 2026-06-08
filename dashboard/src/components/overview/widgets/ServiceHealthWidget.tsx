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

import {ArrowUpRight, Server} from 'lucide-react'
import {cn} from '@/lib/utils'
import {Badge} from '@/components/ui/badge'
import {StatusDot} from '@/components/ui/status-dot'
import {useServiceHealth, type ServiceRow, type Tone} from '../overviewMockData'
import {MiniBar, OverviewPanel, PanelLink, Sparkline} from '../OverviewPanel'
import {toneDot, toneText} from '../overviewTone'

const TH = 'px-2.5 py-1 text-left text-[10px] font-semibold uppercase tracking-wide text-muted-foreground'
const THR = cn(TH, 'text-right')
const TD = 'px-2.5 py-1 align-middle'
const TDR = cn(TD, 'text-right font-mono tabular-nums')

function errorTone(pct: number): Tone {
  if (pct > 2) return 'bad'
  if (pct > 0.8) return 'warn'
  return 'good'
}
function p95Tone(ms: number): Tone {
  if (ms >= 700) return 'bad'
  if (ms >= 400) return 'warn'
  return 'neutral'
}
function apdexTone(a: number): Tone {
  if (a < 0.85) return 'bad'
  if (a >= 0.94) return 'good'
  return 'neutral'
}

function ServiceHealthRow({row}: {row: ServiceRow}) {
  const errTone = errorTone(row.errorPct)
  return (
    <tr className="border-b last:border-0 hover:bg-muted/40">
      <td className={TD}>
        <div className="flex min-w-0 items-center gap-1.5">
          <StatusDot tone={toneDot[row.status]} pulse={row.status === 'bad'} />
          <span className="truncate text-xs font-medium text-foreground">{row.name}</span>
          <span className="rounded-sm border px-1 font-mono text-[9px] text-muted-foreground">
            {row.env}
          </span>
        </div>
      </td>
      <td className={cn(TDR, 'text-xs')}>{row.reqPerMin.toLocaleString()}</td>
      <td className={TD}>
        <div className="flex items-center justify-end gap-1.5">
          <MiniBar pct={row.errorPct * 12} tone={errTone} className="max-w-[56px] flex-1" />
          <span className={cn('w-9 text-right font-mono text-[11px] tabular-nums', toneText[errTone])}>
            {row.errorPct}%
          </span>
        </div>
      </td>
      <td className={cn(TDR, 'text-[11px]', row.p95Ms != null && toneText[p95Tone(row.p95Ms)])}>
        {row.p95Ms != null ? `${row.p95Ms}ms` : <span className="text-muted-foreground">—</span>}
      </td>
      <td className={cn(TDR, 'text-[11px]', row.apdex != null && toneText[apdexTone(row.apdex)])}>
        {row.apdex != null ? (
          row.apdex
        ) : row.lag ? (
          <Badge variant="warning" className="px-1 py-0 text-[9px]">
            {row.lag}
          </Badge>
        ) : (
          <span className="text-muted-foreground">—</span>
        )}
      </td>
      <td className={cn(TD, 'w-[72px]')}>
        <Sparkline data={row.trend} className={toneText[row.status]} height={18} />
      </td>
      <td className={cn(TDR, 'text-[11px]')}>{row.issues}</td>
      <td className={TD}>
        <div className="flex items-center gap-1 whitespace-nowrap">
          {row.deploy.tone === 'bad' ? (
            <Badge variant="danger" className="px-1 py-0 text-[9px]">
              {row.deploy.version}
            </Badge>
          ) : (
            <span className="font-mono text-[10px] text-muted-foreground">{row.deploy.version}</span>
          )}
          <span className="font-mono text-[9px] text-muted-foreground/70">{row.deploy.ageLabel}</span>
        </div>
      </td>
    </tr>
  )
}

/** Per-service health table. */
export function ServiceHealthWidget() {
  const rows = useServiceHealth()
  const critical = rows.filter((r) => r.status === 'bad').length
  const degraded = rows.filter((r) => r.status === 'warn').length
  return (
    <OverviewPanel
      testId="widget-service_health"
      title="Service health"
      icon={Server}
      count={`${rows.length} services`}
      actions={
        <>
          {critical > 0 && (
            <Badge variant="danger" className="px-1.5 py-0 text-[10px]">
              {critical} critical
            </Badge>
          )}
          {degraded > 0 && (
            <Badge variant="warning" className="px-1.5 py-0 text-[10px]">
              {degraded} degraded
            </Badge>
          )}
          <PanelLink>
            View all
            <ArrowUpRight className="h-3 w-3" />
          </PanelLink>
        </>
      }
      flush
    >
      <table className="w-full border-collapse text-sm">
        <thead>
          <tr className="border-b bg-muted/40">
            <th className={TH}>Service</th>
            <th className={THR}>Req/min</th>
            <th className={THR}>Error %</th>
            <th className={THR}>p95</th>
            <th className={THR}>Apdex</th>
            <th className={TH}>Errors (24h)</th>
            <th className={THR}>Issues</th>
            <th className={TH}>Last deploy</th>
          </tr>
        </thead>
        <tbody>
          {rows.map((row) => (
            <ServiceHealthRow key={row.name} row={row} />
          ))}
        </tbody>
      </table>
    </OverviewPanel>
  )
}
