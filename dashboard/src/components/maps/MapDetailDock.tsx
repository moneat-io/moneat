// Moneat - observability platform
// Copyright (C) 2026 Moneat
//
// This program is free software: you can redistribute it and/or modify
// it under the terms of the GNU Affero General Public License as published by
// the Free Software Foundation, either version 3 of the License, or
// (at your option) any later version.

import {X} from 'lucide-react'
import type {ReactNode} from 'react'
import {Button} from '@/components/ui/button'
import {cn} from '@/lib/utils'

type MapDetailDockProps = Readonly<{
  title: string
  subtitle?: string
  onClose: () => void
  children: ReactNode
  className?: string
}>

/**
 * Detail panel that docks as a column beside the map on md+ (so the graph stays
 * visible) and becomes a right slide-over on small screens. Replaces the old
 * floating MapDetailPanel that covered the canvas. The parent map area must be
 * `relative` for the small-screen overlay to anchor correctly.
 */
export function MapDetailDock({title, subtitle, onClose, children, className}: MapDetailDockProps) {
  return (
    <aside
      className={cn(
        'flex flex-col border-l bg-card',
        'absolute inset-y-0 right-0 z-20 w-[88%] max-w-[340px] shadow-lg',
        'md:static md:z-auto md:w-[320px] md:flex-none md:shadow-none',
        className,
      )}
    >
      <div className="flex items-start justify-between gap-2 border-b px-3 py-2.5">
        <div className="min-w-0">
          <h3 className="truncate text-sm font-semibold" title={title}>
            {title}
          </h3>
          {subtitle && (
            <p className="mt-0.5 truncate text-[11px] text-muted-foreground" title={subtitle}>
              {subtitle}
            </p>
          )}
        </div>
        <Button
          type="button"
          variant="ghost"
          size="icon"
          onClick={onClose}
          className="h-6 w-6 shrink-0 text-muted-foreground hover:text-foreground"
          aria-label="Close detail"
        >
          <X className="h-3.5 w-3.5" />
        </Button>
      </div>
      <div className="flex-1 space-y-3 overflow-y-auto px-3 py-3 text-sm">{children}</div>
    </aside>
  )
}
