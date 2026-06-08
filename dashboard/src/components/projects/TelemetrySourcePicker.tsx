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

import type {ComponentType} from 'react'
import {Check, DatabaseZap, RadioTower, ServerCog} from 'lucide-react'
import {Label} from '@/components/ui/label'
import {cn} from '@/lib/utils'
import {
  TELEMETRY_SOURCES,
  toggleTelemetrySourceId,
  type TelemetrySourceId,
} from '@/lib/telemetry-sources'

const sourceIcons: Record<TelemetrySourceId, ComponentType<{className?: string}>> = {
  'opentelemetry': RadioTower,
  'sentry-sdk': DatabaseZap,
  'datadog-agent': ServerCog,
}

interface TelemetrySourcePickerProps {
  readonly value: TelemetrySourceId[]
  readonly onChange: (next: TelemetrySourceId[]) => void
  readonly label?: string
  readonly description?: string
}

/**
 * Controlled grid of telemetry sources (OpenTelemetry / Sentry SDK / Datadog Agent).
 * Extracted from ServiceSetupForm so the same picker drives both service creation
 * and per-service configuration. Toggling keeps at least one source selected
 * (via toggleTelemetrySourceId).
 */
export function TelemetrySourcePicker({
  value,
  onChange,
  label = 'Telemetry sources',
  description = 'Pick the source setup Moneat should walk you through after the service is created.',
}: TelemetrySourcePickerProps) {
  return (
    <div className="flex flex-col gap-3">
      {(label || description) && (
        <div>
          {label ? <Label>{label}</Label> : null}
          {description ? <p className="mt-1 text-sm text-muted-foreground">{description}</p> : null}
        </div>
      )}
      <div className="grid gap-2 sm:grid-cols-2">
        {TELEMETRY_SOURCES.map((source) => {
          const Icon = sourceIcons[source.id]
          const isSelected = value.includes(source.id)
          return (
            <button
              key={source.id}
              type="button"
              onClick={() => onChange(toggleTelemetrySourceId(value, source.id))}
              className={cn(
                'flex min-h-28 items-start gap-3 rounded-lg border-2 p-3 text-left transition-all',
                isSelected ? 'border-primary bg-primary/5' : 'border-border hover:border-primary/50'
              )}
              aria-pressed={isSelected}
            >
              <span className="flex h-9 w-9 shrink-0 items-center justify-center rounded-md bg-muted">
                <Icon className="h-4 w-4" />
              </span>
              <span className="min-w-0 flex-1">
                <span className="flex items-center gap-2 text-sm font-medium">
                  {source.shortLabel}
                  {isSelected ? <Check className="h-3.5 w-3.5 text-primary" /> : null}
                </span>
                <span className="mt-1 block text-xs leading-5 text-muted-foreground">{source.description}</span>
              </span>
            </button>
          )
        })}
      </div>
    </div>
  )
}
