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
import {useTriage} from '../overviewMockData'
import {OverviewPanel, PanelLink} from '../OverviewPanel'

function borderForLevel(level: 'fatal' | 'error' | 'warn' | 'info'): string {
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
}: {
  icon: typeof Bell
  label: string
  count: ReactNode
  children: ReactNode
}) {
  return (
    <div className="border-b px-3 py-2 last:border-0">
      <div className="mb-1.5 flex items-center gap-1.5 text-[11px] font-semibold uppercase tracking-wide text-muted-foreground">
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
}: {
  level: 'fatal' | 'error' | 'warn' | 'info'
  title: ReactNode
  detail: string
  ageLabel: string
}) {
  return (
    <div
      className={cn(
        'flex items-center gap-2 rounded-sm border-l-[3px] px-1.5 py-1.5 hover:bg-muted/50',
        borderForLevel(level),
      )}
    >
      <div className="min-w-0 flex-1">
        <div className="truncate text-sm text-foreground">{title}</div>
        <div className="mt-0.5 truncate text-[11px] text-muted-foreground">{detail}</div>
      </div>
      <span className="shrink-0 font-mono text-[10px] text-muted-foreground/70">{ageLabel}</span>
    </div>
  )
}

/** "Needs attention" rail — incidents, alerts, new issues, security signals. */
export function TriageWidget() {
  const t = useTriage()
  const total = t.incidents.length + t.alerts.length + t.issues.length + t.security.length
  return (
    <OverviewPanel
      testId="widget-triage"
      title="Needs attention"
      icon={ListChecks}
      count={total}
      actions={<PanelLink>Acknowledge all</PanelLink>}
      flush
    >
      <TriageSection icon={Siren} label="Active incidents" count={t.incidents.length}>
        {t.incidents.map((inc) => (
          <div
            key={inc.id}
            className="flex items-start gap-2 rounded-md border border-danger-border bg-danger-bg p-2"
          >
            <StatusDot tone="danger" pulse className="mt-1" />
            <div className="min-w-0 flex-1">
              <div className="text-sm font-semibold text-foreground">{inc.title}</div>
              <div className="mt-1 flex flex-wrap items-center gap-1.5">
                <Badge variant="dangerSolid" className="px-1.5 py-0 text-[10px]">
                  {inc.priority}
                </Badge>
                <Badge variant="danger" className="px-1.5 py-0 text-[10px]">
                  {inc.status}
                </Badge>
                <span className="font-mono text-[10px] text-muted-foreground/70">{inc.id}</span>
                <span className="text-[11px] text-muted-foreground">{inc.owner}</span>
                <span className="ml-auto font-mono text-[10px] text-muted-foreground/70">
                  {inc.ageLabel}
                </span>
              </div>
            </div>
          </div>
        ))}
      </TriageSection>

      <TriageSection icon={Bell} label="Alerts firing" count={t.alerts.length}>
        {t.alerts.map((a, i) => (
          <TriageRow
            key={i}
            level={a.level === 'error' ? 'error' : 'warn'}
            title={<span className="font-semibold">{a.title}</span>}
            detail={a.detail}
            ageLabel={a.ageLabel}
          />
        ))}
      </TriageSection>

      <TriageSection icon={CircleAlert} label="New issues" count={t.issues.length}>
        {t.issues.map((iss, i) => (
          <TriageRow
            key={i}
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
        {t.security.map((sec, i) => (
          <TriageRow
            key={i}
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
