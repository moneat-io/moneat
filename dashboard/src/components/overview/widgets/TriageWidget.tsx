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

import type {ReactNode} from 'react'
import {Bell, CircleAlert, ListChecks, Shield, Siren} from 'lucide-react'
import {cn} from '@/lib/utils'
import {Badge} from '@/components/ui/badge'
import {StatusDot} from '@/components/ui/status-dot'
import {levelBadgeVariant} from '@/lib/severity'
import {useTriage} from '../overviewData'
import {OverviewPanel, PanelLink} from '../OverviewPanel'

type TriageLevel = 'fatal' | 'error' | 'warn' | 'info'

type TriageSectionProps = Readonly<{
  icon: typeof Bell
  label: string
  count: ReactNode
  children: ReactNode
}>

type TriageRowProps = Readonly<{
  level: TriageLevel
  title: ReactNode
  detail: string
  ageLabel: string
}>

type AttentionRouteInput = Readonly<{
  incidents: readonly unknown[]
  alerts: readonly unknown[]
  issues: readonly unknown[]
}>

function attentionRouteFor(triage: AttentionRouteInput): string {
  if (triage.incidents.length > 0) return '/on-call/incidents'
  if (triage.alerts.length > 0) return '/on-call/alerts'
  if (triage.issues.length > 0) return '/issues'
  return '/security/signals'
}

function borderForLevel(level: TriageLevel): string {
  switch (level) {
    case 'fatal':
    case 'error':
      return 'border-l-danger-solid'
    case 'warn':
      return 'border-l-warning-solid'
    default:
      return 'border-l-info-solid'
  }
}

function TriageSection({
  icon: Icon,
  label,
  count,
  children,
}: TriageSectionProps) {
  return (
    <div className="border-b border-border/40 px-2.5 py-1.5 last:border-0">
      <div className="mb-1 flex items-center gap-1.5 text-[10px] font-semibold uppercase tracking-wide text-muted-foreground">
        <Icon className="h-3 w-3 text-muted-foreground" />
        {label}
        <span className="ml-auto font-medium text-muted-foreground/70">{count}</span>
      </div>
      <div className="space-y-0.5">{children}</div>
    </div>
  )
}

function TriageRow({
  level,
  title,
  detail,
  ageLabel,
}: TriageRowProps) {
  return (
    <div
      className={cn(
        'flex items-center gap-1.5 rounded-sm border-l-2 px-1.5 py-1 hover:bg-muted/50',
        borderForLevel(level),
      )}
    >
      <div className="min-w-0 flex-1">
        <div className="truncate text-xs text-foreground">{title}</div>
        <div className="truncate text-[10px] text-muted-foreground">{detail}</div>
      </div>
      <span className="shrink-0 font-mono text-[9px] text-muted-foreground/70">{ageLabel}</span>
    </div>
  )
}

/** "Needs attention" rail — incidents, alerts, new issues, security signals. */
export function TriageWidget() {
  const t = useTriage()
  const total = t.incidents.length + t.alerts.length + t.issues.length + t.security.length
  const attentionRoute = attentionRouteFor(t)
  return (
    <OverviewPanel
      testId="widget-triage"
      title="Needs attention"
      icon={ListChecks}
      count={total}
      actions={total > 0 ? <PanelLink to={attentionRoute}>View all</PanelLink> : undefined}
      flush
    >
      {t.incidents.length > 0 && (
        <TriageSection icon={Siren} label="Active incidents" count={t.incidents.length}>
          {t.incidents.map((inc) => (
            <div
              key={inc.id}
              className="flex items-start gap-1.5 rounded-md border border-danger-border bg-danger-bg px-2 py-1.5"
            >
              <StatusDot tone="danger" pulse className="mt-0.5" />
              <div className="min-w-0 flex-1">
                <div className="text-xs font-semibold text-foreground">{inc.title}</div>
                <div className="mt-0.5 flex flex-wrap items-center gap-1">
                  <Badge variant="dangerSolid" className="px-1 py-0 text-[9px]">
                    {inc.priority}
                  </Badge>
                  <Badge variant="danger" className="px-1 py-0 text-[9px]">
                    {inc.status}
                  </Badge>
                  <span className="font-mono text-[9px] text-muted-foreground/70">{inc.id}</span>
                  <span className="text-[10px] text-muted-foreground">{inc.owner}</span>
                  <span className="ml-auto font-mono text-[9px] text-muted-foreground/70">
                    {inc.ageLabel}
                  </span>
                </div>
              </div>
            </div>
          ))}
        </TriageSection>
      )}

      {t.alerts.length > 0 && (
        <TriageSection icon={Bell} label="Alerts firing" count={t.alerts.length}>
          {t.alerts.map((a) => (
            <TriageRow
              key={`${a.level}:${a.title}:${a.detail}:${a.ageLabel}`}
              level={a.level === 'error' ? 'error' : 'warn'}
              title={<span className="font-semibold">{a.title}</span>}
              detail={a.detail}
              ageLabel={a.ageLabel}
            />
          ))}
        </TriageSection>
      )}

      <TriageSection icon={CircleAlert} label="New issues" count={t.issues.length}>
        {t.issues.map((iss) => (
          <TriageRow
            key={`${iss.level}:${iss.title}:${iss.detail}:${iss.ageLabel}`}
            level={iss.level}
            title={
              <span className="flex items-center gap-1.5">
                <Badge variant={levelBadgeVariant(iss.level)} className="px-1.5 py-0 text-[10px] capitalize">
                  {iss.level}
                </Badge>
                <span className="truncate">{iss.title}</span>
              </span>
            }
            detail={iss.detail}
            ageLabel={iss.ageLabel}
          />
        ))}
      </TriageSection>

      <TriageSection icon={Shield} label="Security signals" count={t.security.length}>
        {t.security.map((sec) => (
          <TriageRow
            key={`${sec.level}:${sec.title}:${sec.detail}:${sec.ageLabel}`}
            level={sec.level === 'error' ? 'error' : 'warn'}
            title={<span className="font-semibold">{sec.title}</span>}
            detail={sec.detail}
            ageLabel={sec.ageLabel}
          />
        ))}
      </TriageSection>
    </OverviewPanel>
  )
}
