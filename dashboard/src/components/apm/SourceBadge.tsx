// Moneat - observability platform
// Copyright (C) 2026 Moneat
//
// This program is free software: you can redistribute it and/or modify
// it under the terms of the GNU Affero General Public License as published by
// the Free Software Foundation, either version 3 of the License, or
// (at your option) any later version.

import {Badge} from '@/components/ui/badge'
import {cn} from '@/lib/utils'

const SOURCE_CONFIG: Record<string, {label: string; className: string}> = {
  datadog: {
    label: 'Datadog',
    className: 'text-violet-600 border-violet-600/30 dark:text-violet-400 dark:border-violet-400/30',
  },
  otlp: {
    label: 'OTEL',
    className: 'text-teal-600 border-teal-600/30 dark:text-teal-400 dark:border-teal-400/30',
  },
}

export function SourceBadge({source, className}: {source: string; className?: string}) {
  const config = SOURCE_CONFIG[source] ?? {
    label: source.charAt(0).toUpperCase() + source.slice(1),
    className: 'text-muted-foreground',
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
