// Moneat - observability platform
// Copyright (C) 2026 Moneat
//
// This program is free software: you can redistribute it and/or modify
// it under the terms of the GNU Affero General Public License as published by
// the Free Software Foundation, either version 3 of the License, or
// (at your option) any later version.

import type {CSSProperties, ReactNode} from 'react'
import {cn} from '@/lib/utils'

interface MapNodeCardProps {
  title: string
  subtitle?: string
  icon?: ReactNode
  statusIndicator?: ReactNode
  metrics?: ReactNode
  footer?: ReactNode
  selected?: boolean
  dimmed?: boolean
  dashed?: boolean
  borderColor?: string
  borderClassName?: string
  minWidth?: number
  iconContainerClassName?: string
  iconContainerStyle?: CSSProperties
  className?: string
}

export function MapNodeCard({
  title,
  subtitle,
  icon,
  statusIndicator,
  metrics,
  footer,
  selected = false,
  dimmed = false,
  dashed = false,
  borderColor,
  borderClassName,
  minWidth,
  iconContainerClassName,
  iconContainerStyle,
  className,
}: MapNodeCardProps) {
  return (
    <div
      className={cn(
        'rounded-lg border-2 bg-card text-card-foreground',
        'transition-all duration-200 cursor-pointer',
        selected && 'ring-2 ring-primary scale-[1.02]',
        dimmed && 'opacity-25',
        dashed && 'border-dashed',
        borderClassName,
        className,
      )}
      style={{borderColor, minWidth}}
    >
      <div className="px-3 py-2.5">
        <div className="flex min-w-0 items-center gap-2">
          {icon && (
            <div
              className={cn(
                'flex h-7 w-7 shrink-0 items-center justify-center rounded-md',
                iconContainerClassName,
              )}
              style={iconContainerStyle}
            >
              {icon}
            </div>
          )}
          <span className="min-w-0 flex-1 truncate text-sm font-semibold" title={title}>
            {title}
          </span>
          {statusIndicator}
        </div>

        {subtitle && (
          <p className="mt-1 truncate text-[11px] text-muted-foreground" title={subtitle}>
            {subtitle}
          </p>
        )}

        {metrics && (
          <div className="mt-1.5 flex min-w-0 items-center gap-3 text-[11px] font-mono text-muted-foreground">
            {metrics}
          </div>
        )}

        {footer}
      </div>
    </div>
  )
}
