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

import type {SignalSeverity, SignalStatus} from '@/lib/api'
import {cn} from '@/lib/utils'
import {
  colorFor,
  severityColors,
  STATUS_LABELS,
  STATUS_ORDER,
  statusColors,
  SEVERITY_ORDER,
} from './securityChips'

export interface SignalFilters {
  status?: SignalStatus
  severity?: SignalSeverity
  source?: string
}

interface SignalFilterBarProps {
  filters: SignalFilters
  onChange: (filters: SignalFilters) => void
  sources: string[]
}

function Chip({
  active,
  label,
  className,
  onClick,
}: {
  active: boolean
  label: string
  className?: string
  onClick: () => void
}) {
  return (
    <button
      type="button"
      onClick={onClick}
      aria-pressed={active}
      className={cn(
        'rounded-full border px-2 py-0.5 text-[10px] font-medium capitalize transition-colors',
        active
          ? cn('border-transparent', className || 'bg-primary/10 text-primary')
          : 'border-border text-muted-foreground hover:bg-muted/50'
      )}>
      {label}
    </button>
  )
}

export function SignalFilterBar({filters, onChange, sources}: SignalFilterBarProps) {
  const toggle = <K extends keyof SignalFilters>(key: K, value: SignalFilters[K]) => {
    onChange({...filters, [key]: filters[key] === value ? undefined : value})
  }

  return (
    <div className="flex flex-wrap items-center gap-x-4 gap-y-1.5">
      <div className="flex items-center gap-1">
        <span className="text-[10px] uppercase tracking-wide text-muted-foreground">Status</span>
        {STATUS_ORDER.map((status) => (
          <Chip
            key={status}
            label={STATUS_LABELS[status]}
            active={filters.status === status}
            className={colorFor(statusColors, status)}
            onClick={() => toggle('status', status)}
          />
        ))}
      </div>
      <div className="flex items-center gap-1">
        <span className="text-[10px] uppercase tracking-wide text-muted-foreground">Severity</span>
        {SEVERITY_ORDER.map((severity) => (
          <Chip
            key={severity}
            label={severity}
            active={filters.severity === severity}
            className={colorFor(severityColors, severity)}
            onClick={() => toggle('severity', severity)}
          />
        ))}
      </div>
      {sources.length > 0 && (
        <div className="flex items-center gap-1">
          <span className="text-[10px] uppercase tracking-wide text-muted-foreground">Source</span>
          {sources.map((source) => (
            <Chip
              key={source}
              label={source.replace(/_/g, ' ')}
              active={filters.source === source}
              onClick={() => toggle('source', source)}
            />
          ))}
        </div>
      )}
    </div>
  )
}
