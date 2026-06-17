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

import {Badge} from '@/components/ui/badge'
import {cn} from '@/lib/utils'

const SOURCE_CONFIG: Record<string, {label: string; className: string}> = {
  datadog: {
    label: 'Datadog',
    className: 'text-chart-6 border-chart-6/30',
  },
  'datadog-agent': {
    label: 'Datadog Agent',
    className: 'text-chart-6 border-chart-6/30',
  },
  otlp: {
    label: 'OTEL',
    className: 'text-chart-3 border-chart-3/30',
  },
  opentelemetry: {
    label: 'OpenTelemetry',
    className: 'text-chart-3 border-chart-3/30',
  },
  'sentry-sdk': {
    label: 'Sentry SDK',
    className: 'text-muted-foreground border-border/50',
  },
  sdk: {
    label: 'SDK',
    className: 'text-muted-foreground border-border/50',
  },
}

export function SourceBadge({
  source,
  className,
}: Readonly<{source?: string | null; className?: string}>) {
  const rawSource = source?.trim() ?? ''
  const key = rawSource.toLowerCase()
  const config =
    key === ''
      ? {label: 'Unknown', className: 'text-muted-foreground border-border/50'}
      : SOURCE_CONFIG[key] ?? {
          label: rawSource.charAt(0).toUpperCase() + rawSource.slice(1),
          className: 'text-muted-foreground border-border/50',
        }

  return (
    <Badge
      variant="outline"
      className={cn('text-[10px] px-1.5 py-0 font-normal', config.className, className)}
    >
      {config.label}
    </Badge>
  )
}
