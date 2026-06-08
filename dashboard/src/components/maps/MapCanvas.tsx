// Moneat - observability platform
// Copyright (C) 2026 Moneat
//
// This program is free software: you can redistribute it and/or modify
// it under the terms of the GNU Affero General Public License as published by
// the Free Software Foundation, either version 3 of the License, or
// (at your option) any later version.

import {ReactFlow, Controls, Background, BackgroundVariant, useReactFlow, type Edge, type Node} from '@xyflow/react'
import '@xyflow/react/dist/style.css'
import {Loader2} from 'lucide-react'
import {useEffect, type ComponentProps, type ComponentType, type ReactNode} from 'react'
import {cn} from '@/lib/utils'

type ReactFlowProps = ComponentProps<typeof ReactFlow>
const DEFAULT_FIT_VIEW_OPTIONS: ReactFlowProps['fitViewOptions'] = {padding: 0.25}

type MapCanvasProps = Readonly<{
  nodes: Node[]
  edges?: Edge[]
  nodeTypes?: ReactFlowProps['nodeTypes']
  onNodeClick?: ReactFlowProps['onNodeClick']
  onPaneClick?: ReactFlowProps['onPaneClick']
  isLoading?: boolean
  isEmpty?: boolean
  loadingLabel?: string
  emptyIcon?: ComponentType<{className?: string}>
  emptyTitle?: string
  emptyDescription?: string
  fallback?: ReactNode
  children?: ReactNode
  className?: string
  fitSignature?: string
  fitViewOptions?: ReactFlowProps['fitViewOptions']
  minZoom?: number
  maxZoom?: number
}>

type MapCanvasFrameProps = Readonly<{
  children: ReactNode
  className?: string
}>

function FitOnChange({
  signature,
  fitViewOptions,
}: Readonly<{
  signature?: string
  fitViewOptions: ReactFlowProps['fitViewOptions']
}>) {
  const {fitView} = useReactFlow()

  useEffect(() => {
    const frame = globalThis.requestAnimationFrame(() => {
      void fitView(fitViewOptions)
    })
    return () => globalThis.cancelAnimationFrame(frame)
  }, [signature, fitView, fitViewOptions])

  return null
}

export function MapCanvas({
  nodes,
  edges = [],
  nodeTypes,
  onNodeClick,
  onPaneClick,
  isLoading = false,
  isEmpty = false,
  loadingLabel = 'Loading map...',
  emptyIcon: EmptyIcon,
  emptyTitle = 'No map data found',
  emptyDescription,
  fallback,
  children,
  className,
  fitSignature,
  fitViewOptions = DEFAULT_FIT_VIEW_OPTIONS,
  minZoom = 0.2,
  maxZoom = 2,
}: MapCanvasProps) {
  if (isLoading) {
    return (
      <MapCanvasFrame className={className}>
        <div className="flex h-full min-h-[280px] items-center justify-center">
          <div className="flex flex-col items-center gap-3">
            <Loader2 className="h-6 w-6 animate-spin text-muted-foreground" />
            <p className="text-sm text-muted-foreground">{loadingLabel}</p>
          </div>
        </div>
      </MapCanvasFrame>
    )
  }

  if (fallback) {
    return (
      <MapCanvasFrame className={className}>
        <div className="flex h-full min-h-[280px] items-center justify-center p-6">
          {fallback}
        </div>
      </MapCanvasFrame>
    )
  }

  if (isEmpty) {
    return (
      <MapCanvasFrame className={className}>
        <div className="flex h-full min-h-[280px] items-center justify-center p-6 text-center">
          <div className="max-w-sm space-y-2">
            {EmptyIcon && <EmptyIcon className="mx-auto h-8 w-8 text-muted-foreground/40" />}
            <p className="text-sm font-medium text-muted-foreground">{emptyTitle}</p>
            {emptyDescription && (
              <p className="text-xs text-muted-foreground/75">{emptyDescription}</p>
            )}
          </div>
        </div>
      </MapCanvasFrame>
    )
  }

  return (
    <MapCanvasFrame className={className}>
      <ReactFlow
        nodes={nodes}
        edges={edges}
        nodeTypes={nodeTypes}
        onNodeClick={onNodeClick}
        onPaneClick={onPaneClick}
        nodesDraggable={false}
        nodesConnectable={false}
        onlyRenderVisibleElements
        fitView
        fitViewOptions={fitViewOptions}
        minZoom={minZoom}
        maxZoom={maxZoom}
        proOptions={{hideAttribution: true}}
      >
        <FitOnChange signature={fitSignature} fitViewOptions={fitViewOptions} />
        <Background
          variant={BackgroundVariant.Dots}
          gap={24}
          size={1}
          color="hsl(var(--viz-grid) / 0.1)"
        />
        <Controls showInteractive={false} />
      </ReactFlow>
      {children}
    </MapCanvasFrame>
  )
}

function MapCanvasFrame({children, className}: MapCanvasFrameProps) {
  return (
    <div className={cn('map-canvas relative rounded-lg border bg-[hsl(var(--viz-surface))] overflow-hidden', className)}>
      {children}
    </div>
  )
}
