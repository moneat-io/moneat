// Moneat - observability platform
// Copyright (C) 2026 Moneat
//
// This program is free software: you can redistribute it and/or modify
// it under the terms of the GNU Affero General Public License as published by
// the Free Software Foundation, either version 3 of the License, or
// (at your option) any later version.

import type {ReactNode} from 'react'
import {cn} from '@/lib/utils'

export interface MapLegendItem {
  label: string
  color?: string
  dashed?: boolean
  className?: string
}

interface MapLegendProps {
  items: MapLegendItem[]
  trailing?: ReactNode
  className?: string
}

export function MapLegend({items, trailing, className}: MapLegendProps) {
  return (
    <div
      className={cn(
        'pointer-events-none absolute bottom-3 left-3 flex flex-wrap items-center gap-3',
        'rounded-md border bg-card/90 px-3 py-1.5 text-[11px] text-muted-foreground backdrop-blur-sm',
        className,
      )}
    >
      {items.map((item) => (
        <span key={item.label} className="flex items-center gap-1.5">
          <span
            className={cn(
              item.dashed ? 'inline-block w-4 border-t-2 border-dashed' : 'inline-block h-2 w-2 rounded-sm',
              item.className,
            )}
            style={item.color ? {backgroundColor: item.dashed ? undefined : item.color, borderColor: item.color} : undefined}
          />
          <span>{item.label}</span>
        </span>
      ))}
      {trailing}
    </div>
  )
}
