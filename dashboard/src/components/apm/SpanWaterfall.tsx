// Moneat - observability platform
// Copyright (C) 2026 Moneat
//
// This program is free software: you can redistribute it and/or modify
// it under the terms of the GNU Affero General Public License as published by
// the Free Software Foundation, either version 3 of the License, or
// (at your option) any later version.

import {type ApmSpanResponse} from '@/lib/api'
import {cn} from '@/lib/utils'
import {useState, useMemo} from 'react'
import {Badge} from '@/components/ui/badge'
import {ChevronDown, ChevronRight} from 'lucide-react'

interface Props {
  spans: ApmSpanResponse[]
}

interface SpanNode {
  span: ApmSpanResponse
  children: SpanNode[]
  depth: number
}

const SERVICE_COLORS: Record<string, string> = {
  web: 'bg-emerald-500',
  http: 'bg-blue-500',
  db: 'bg-amber-500',
  cache: 'bg-violet-500',
  grpc: 'bg-cyan-500',
  custom: 'bg-gray-500',
}

function getSpanColor(type: string): string {
  return SERVICE_COLORS[type] || SERVICE_COLORS.custom
}

function formatDuration(ns: number): string {
  if (ns < 1000) return `${ns}ns`
  if (ns < 1_000_000) return `${(ns / 1000).toFixed(1)}µs`
  if (ns < 1_000_000_000) return `${(ns / 1_000_000).toFixed(1)}ms`
  return `${(ns / 1_000_000_000).toFixed(2)}s`
}

function buildTree(spans: ApmSpanResponse[]): SpanNode[] {
  const spanMap = new Map<string, SpanNode>()
  const roots: SpanNode[] = []

  for (const span of spans) {
    spanMap.set(span.spanId, {span, children: [], depth: 0})
  }

  for (const span of spans) {
    const node = spanMap.get(span.spanId)!
    const parent = spanMap.get(span.parentId)
    if (parent && span.parentId !== '0') {
      parent.children.push(node)
      node.depth = parent.depth + 1
    } else {
      roots.push(node)
    }
  }

  return roots
}

function flattenTree(nodes: SpanNode[]): SpanNode[] {
  const result: SpanNode[] = []
  function walk(nodes: SpanNode[]) {
    for (const node of nodes) {
      result.push(node)
      walk(node.children)
    }
  }
  walk(nodes)
  return result
}

export function SpanWaterfall({spans}: Props) {
  const [collapsed, setCollapsed] = useState<Set<string>>(new Set())

  const {flatSpans, traceStart, traceDuration} = useMemo(() => {
    const tree = buildTree(spans)
    const flatSpans = flattenTree(tree)
    const traceStart = Math.min(...spans.map((s) => s.startNs))
    const traceEnd = Math.max(...spans.map((s) => s.startNs + s.durationNs))
    return {flatSpans, traceStart, traceDuration: traceEnd - traceStart}
  }, [spans])

  const toggleCollapse = (spanId: string) => {
    setCollapsed((prev) => {
      const next = new Set(prev)
      if (next.has(spanId)) {
        next.delete(spanId)
      } else {
        next.add(spanId)
      }
      return next
    })
  }

  // Filter out collapsed children
  const visibleSpans = useMemo(() => {
    const visible: SpanNode[] = []
    const hiddenParents = new Set<string>()

    for (const node of flatSpans) {
      // Check if any ancestor is collapsed
      let parentId = node.span.parentId
      let hidden = false
      while (parentId && parentId !== '0') {
        if (hiddenParents.has(parentId)) {
          hidden = true
          break
        }
        const parent = spans.find((s) => s.spanId === parentId)
        parentId = parent?.parentId ?? '0'
      }

      if (hidden) continue
      visible.push(node)

      if (collapsed.has(node.span.spanId)) {
        hiddenParents.add(node.span.spanId)
      }
    }
    return visible
  }, [flatSpans, collapsed, spans])

  if (spans.length === 0) {
    return (
      <div className="text-center py-8 text-muted-foreground">
        No spans found
      </div>
    )
  }

  return (
    <div className="border rounded-lg overflow-hidden">
      {/* Header */}
      <div className="grid grid-cols-[300px_1fr] border-b bg-muted/50 px-2 py-1.5 text-xs font-medium text-muted-foreground">
        <div>Operation</div>
        <div>Timeline ({formatDuration(traceDuration)})</div>
      </div>

      {/* Rows */}
      <div className="divide-y">
        {visibleSpans.map((node) => {
          const {span} = node
          const left =
            traceDuration > 0
              ? ((span.startNs - traceStart) / traceDuration) * 100
              : 0
          const width =
            traceDuration > 0
              ? Math.max((span.durationNs / traceDuration) * 100, 0.5)
              : 100
          const hasChildren = node.children.length > 0
          const isCollapsed = collapsed.has(span.spanId)

          return (
            <div
              key={span.spanId}
              className="grid grid-cols-[300px_1fr] items-center px-2 py-1 hover:bg-muted/30 text-sm"
            >
              {/* Operation name */}
              <div
                className="flex items-center gap-1 min-w-0"
                style={{paddingLeft: `${node.depth * 16}px`}}
              >
                {hasChildren ? (
                  <button
                    onClick={() => toggleCollapse(span.spanId)}
                    className="p-0.5 hover:bg-muted rounded"
                  >
                    {isCollapsed ? (
                      <ChevronRight className="h-3 w-3" />
                    ) : (
                      <ChevronDown className="h-3 w-3" />
                    )}
                  </button>
                ) : (
                  <span className="w-4" />
                )}
                <span
                  className={cn(
                    'w-2 h-2 rounded-full shrink-0',
                    getSpanColor(span.type)
                  )}
                />
                <span className="truncate font-medium text-xs">
                  {span.service}
                </span>
                <span className="truncate text-xs text-muted-foreground">
                  {span.name}
                </span>
                {span.error > 0 && (
                  <Badge
                    variant="destructive"
                    className="h-4 text-[10px] px-1"
                  >
                    ERR
                  </Badge>
                )}
              </div>

              {/* Timeline bar */}
              <div className="relative h-5">
                <div
                  className={cn(
                    'absolute top-1 h-3 rounded-sm opacity-80',
                    getSpanColor(span.type)
                  )}
                  style={{
                    left: `${left}%`,
                    width: `${width}%`,
                    minWidth: '2px',
                  }}
                  title={`${span.service}.${span.name}: ${formatDuration(span.durationNs)}`}
                />
                <span
                  className="absolute top-0.5 text-[10px] text-muted-foreground"
                  style={{left: `${Math.min(left + width + 1, 90)}%`}}
                >
                  {formatDuration(span.durationNs)}
                </span>
              </div>
            </div>
          )
        })}
      </div>
    </div>
  )
}
