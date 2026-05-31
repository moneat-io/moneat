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

import type {SignalDetailResponse, TriageRequest} from '@/lib/api'
import {Badge} from '@/components/ui/badge'
import {Separator} from '@/components/ui/separator'
import {cn} from '@/lib/utils'
import {
  ARCHIVE_REASON_LABELS,
  colorFor,
  severityColors,
  STATUS_LABELS,
  statusColors,
} from './securityChips'
import {SignalTriagePanel} from './SignalTriagePanel'

// Renders a raw audit status enum (e.g. "under_review") with its user-facing label, falling back to
// the raw value for anything unrecognised.
function statusLabel(value: string): string {
  return Object.hasOwn(STATUS_LABELS, value)
    ? STATUS_LABELS[value as keyof typeof STATUS_LABELS]
    : value
}

interface SignalDetailContentProps {
  detail: SignalDetailResponse
  onTriage: (request: TriageRequest) => Promise<unknown>
  isSubmitting?: boolean
}

function Field({label, children}: {label: string; children: React.ReactNode}) {
  return (
    <div className="space-y-0.5">
      <div className="text-[10px] uppercase tracking-wide text-muted-foreground">{label}</div>
      <div className="text-xs">{children}</div>
    </div>
  )
}

export function SignalDetailContent({detail, onTriage, isSubmitting}: SignalDetailContentProps) {
  const {signal, evidence, audit, sample_events: sampleEvents} = detail
  const threatIntel = detail.threat_intel ?? []

  return (
    <div className="space-y-4">
      <div className="flex flex-wrap items-center gap-2">
        <Badge variant="outline" className={cn('text-[10px]', colorFor(severityColors, signal.severity))}>
          {signal.severity}
        </Badge>
        <Badge variant="outline" className={cn('text-[10px]', colorFor(statusColors, signal.status))}>
          {statusLabel(signal.status)}
        </Badge>
        {signal.archive_reason && (
          <Badge variant="outline" className="text-[10px]">
            {ARCHIVE_REASON_LABELS[signal.archive_reason] ?? signal.archive_reason}
          </Badge>
        )}
      </div>

      <div className="grid grid-cols-2 gap-3">
        <Field label="Source">{signal.source.replace(/_/g, ' ')}</Field>
        <Field label="Rule">{signal.rule_name}</Field>
        <Field label="Samples">{signal.sample_count}</Field>
        <Field label="Assignee">{signal.assignee_user_id ? `User #${signal.assignee_user_id}` : 'Unassigned'}</Field>
        <Field label="First seen">{signal.first_seen}</Field>
        <Field label="Last seen">{signal.last_seen}</Field>
      </div>

      {Object.keys(signal.entities).length > 0 && (
        <div className="space-y-1">
          <div className="text-[10px] uppercase tracking-wide text-muted-foreground">Entities</div>
          <div className="flex flex-wrap gap-1">
            {Object.entries(signal.entities).map(([k, v]) => (
              <Badge key={k} variant="outline" className="text-[10px] font-mono">
                {k}={v}
              </Badge>
            ))}
          </div>
        </div>
      )}

      {threatIntel.length > 0 && (
        <div className="space-y-1">
          <div className="text-[10px] uppercase tracking-wide text-muted-foreground">Threat Intel</div>
          <div className="space-y-1">
            {threatIntel.map((item) => (
              <div key={`${item.entity_key}:${item.entity_value}:${item.feed_name}`} className="rounded border p-1.5">
                <div className="flex flex-wrap items-center gap-1">
                  <Badge variant="outline" className="text-[10px]">{item.indicator_type}</Badge>
                  <span className="font-mono text-[10px]">{item.entity_key}={item.entity_value}</span>
                  <Badge variant="outline" className="text-[10px]">{item.confidence}%</Badge>
                </div>
                <div className="mt-1 text-[10px] text-muted-foreground">
                  {item.threat_type.replace(/_/g, ' ')} - {item.feed_name}
                </div>
              </div>
            ))}
          </div>
        </div>
      )}

      {signal.tags.length > 0 && (
        <div className="flex flex-wrap gap-1">
          {signal.tags.map((tag) => (
            <Badge key={tag} variant="outline" className="text-[10px]">
              {tag}
            </Badge>
          ))}
        </div>
      )}

      <Separator />
      <SignalTriagePanel signal={signal} onTriage={onTriage} isSubmitting={isSubmitting} />

      <Separator />
      <div className="space-y-1.5">
        <div className="text-[10px] uppercase tracking-wide text-muted-foreground">
          Evidence sample ({sampleEvents.length})
        </div>
        {sampleEvents.length === 0 ? (
          <p className="text-xs text-muted-foreground">No sample events</p>
        ) : (
          <div className="space-y-1">
            {sampleEvents.slice(0, 10).map((event, i) => (
              <pre
                key={i}
                className="overflow-x-auto rounded bg-muted/50 p-1.5 text-[10px] leading-tight text-muted-foreground">
                {JSON.stringify(event)}
              </pre>
            ))}
          </div>
        )}
        {evidence.length > 0 && (
          <p className="text-[10px] text-muted-foreground">
            {evidence.length} evidence reference{evidence.length === 1 ? '' : 's'}
          </p>
        )}
      </div>

      {audit.length > 0 && (
        <>
          <Separator />
          <div className="space-y-1.5">
            <div className="text-[10px] uppercase tracking-wide text-muted-foreground">Activity</div>
            <ul className="space-y-1">
              {audit.map((entry) => (
                <li key={entry.id} className="text-[10px] text-muted-foreground">
                  <span className="font-medium text-foreground">{entry.action.replace(/_/g, ' ')}</span>
                  {entry.from_status && entry.to_status && (
                    <span>
                      {' '}
                      {statusLabel(entry.from_status)} → {statusLabel(entry.to_status)}
                    </span>
                  )}
                  {entry.reason && <span> ({ARCHIVE_REASON_LABELS[entry.reason] ?? entry.reason})</span>}
                  {entry.note && <span className="block italic">“{entry.note}”</span>}
                  <span className="block">{entry.created_at}</span>
                </li>
              ))}
            </ul>
          </div>
        </>
      )}
    </div>
  )
}
