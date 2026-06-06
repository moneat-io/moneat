// Moneat - observability platform
// Copyright (C) 2026 Moneat
//
// This program is free software: you can redistribute it and/or modify
// it under the terms of the GNU Affero General Public License as published by
// the Free Software Foundation, either version 3 of the License, or
// (at your option) any later version.

import {memo, type ComponentType, type ReactNode} from 'react'
import {Loader2} from 'lucide-react'
import {cn} from '@/lib/utils'
import type {InfrastructureMapTone} from './infrastructureMapModel'
import type {HostmapGroup, HostmapSizeTier, HostmapTile, HostmapView} from './hostmapModel'

// Utilization color = the app's threshold language (there is no dedicated
// --scale-* token set): green healthy, amber elevated, red hot, plus a muted
// neutral for no-data/zero.
const TONE_FILL: Record<InfrastructureMapTone, string> = {
  success: 'hsl(var(--success-solid))',
  warning: 'hsl(var(--warning-solid))',
  danger: 'hsl(var(--danger-solid))',
  info: 'hsl(var(--info-solid))',
  neutral: 'hsl(var(--muted-foreground) / 0.45)',
}

// Capacity tier -> tile footprint (px). Bigger host = bigger tile, packed tight.
const TIER_DIMS: Record<HostmapSizeTier, {width: number; height: number}> = {
  s: {width: 26, height: 26},
  m: {width: 38, height: 38},
  l: {width: 54, height: 54},
  xl: {width: 72, height: 54},
}

type HostmapProps = Readonly<{
  view: HostmapView
  selectedId: string | null
  onSelectNode: (id: string) => void
  isLoading?: boolean
  isEmpty?: boolean
  emptyIcon?: ComponentType<{className?: string}>
  emptyTitle?: string
  emptyDescription?: string
  fallback?: ReactNode
}>

export function Hostmap({
  view,
  selectedId,
  onSelectNode,
  isLoading = false,
  isEmpty = false,
  emptyIcon: EmptyIcon,
  emptyTitle = 'No resources found',
  emptyDescription,
  fallback,
}: HostmapProps) {
  if (isLoading) {
    return (
      <HostmapFrame>
        <div className="flex flex-col items-center gap-3">
          <Loader2 className="h-6 w-6 animate-spin text-muted-foreground" />
          <p className="text-sm text-muted-foreground">Loading map...</p>
        </div>
      </HostmapFrame>
    )
  }

  if (fallback) {
    return <HostmapFrame>{fallback}</HostmapFrame>
  }

  if (isEmpty) {
    return (
      <HostmapFrame>
        <div className="max-w-sm space-y-2 text-center">
          {EmptyIcon && <EmptyIcon className="mx-auto h-8 w-8 text-muted-foreground/40" />}
          <p className="text-sm font-medium text-muted-foreground">{emptyTitle}</p>
          {emptyDescription && <p className="text-xs text-muted-foreground/75">{emptyDescription}</p>}
        </div>
      </HostmapFrame>
    )
  }

  return (
    <div className="min-w-0 flex-1 overflow-auto bg-background p-3">
      <div className="flex flex-wrap content-start gap-3">
        {view.groups.map((group) => (
          <HostmapGroupPanel
            key={group.key}
            group={group}
            selectedId={selectedId}
            onSelectNode={onSelectNode}
          />
        ))}
      </div>
    </div>
  )
}

function HostmapFrame({children}: Readonly<{children: ReactNode}>) {
  return (
    <div className="flex min-w-0 flex-1 items-center justify-center bg-background p-6">{children}</div>
  )
}

const HostmapGroupPanel = memo(function HostmapGroupPanel({
  group,
  selectedId,
  onSelectNode,
}: Readonly<{
  group: HostmapGroup
  selectedId: string | null
  onSelectNode: (id: string) => void
}>) {
  return (
    <section className="self-start overflow-hidden rounded-lg border bg-card">
      <header className="flex items-center gap-2 border-b px-3 py-1.5 text-[11px]">
        <span className="truncate font-semibold" title={group.label}>
          {group.label}
        </span>
        <span className="ml-auto shrink-0 font-mono text-muted-foreground">
          {group.healthyCount}/{group.count}
        </span>
      </header>
      <div className="flex max-w-[300px] flex-wrap gap-1 p-2">
        {group.tiles.map((tile) => (
          <HostmapTileButton
            key={tile.id}
            tile={tile}
            selected={selectedId === tile.id}
            onSelect={onSelectNode}
          />
        ))}
      </div>
    </section>
  )
})

const HostmapTileButton = memo(function HostmapTileButton({
  tile,
  selected,
  onSelect,
}: Readonly<{
  tile: HostmapTile
  selected: boolean
  onSelect: (id: string) => void
}>) {
  const dims = TIER_DIMS[tile.tier]
  return (
    <button
      type="button"
      onClick={() => onSelect(tile.id)}
      title={`${tile.label} · ${tile.node.statusLabel} · ${tile.node.metricLabel}`}
      aria-label={`${tile.label}, ${tile.node.statusLabel}`}
      className={cn(
        'rounded-sm border border-black/20 transition-transform duration-100',
        'hover:z-10 hover:scale-110 focus:outline-none focus-visible:ring-2 focus-visible:ring-ring',
        selected && 'z-10 ring-2 ring-primary ring-offset-1 ring-offset-card',
      )}
      style={{width: dims.width, height: dims.height, backgroundColor: TONE_FILL[tile.tone]}}
    />
  )
})
