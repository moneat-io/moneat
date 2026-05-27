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

import {useMemo, useRef, useState} from 'react'
import type {Span, TransactionDetail} from '@/lib/api'
import {ChevronDown, ChevronRight} from 'lucide-react'
import {cn} from '@/lib/utils'

type SpanNode = Span & { children: SpanNode[] }
type VisibleSpan = { span: SpanNode; depth: number }

type SpanWaterfallProps = {
  readonly transaction: TransactionDetail
  readonly spans: Span[]
}

type SpanTooltipProps = {
  readonly span: Span
  readonly x: number
  readonly y: number
}

type TimeRulerProps = {
  readonly durationMs: number
}

function opColorClass(op: string) {
  const normalized = op.toLowerCase()
  if (normalized.includes('db')) return 'bg-blue-500/85'
  if (normalized.includes('http') || normalized.includes('network')) return 'bg-emerald-500/85'
  if (normalized.includes('render') || normalized.includes('serialize')) return 'bg-amber-500/85'
  if (normalized.includes('cache') || normalized.includes('redis')) return 'bg-violet-500/85'
  if (normalized.includes('ui')) return 'bg-pink-500/85'
  return 'bg-slate-500/80'
}

function buildTree(spans: Span[]): SpanNode[] {
  const nodes = new Map<string, SpanNode>()
  const roots: SpanNode[] = []

  for (const span of spans) {
    nodes.set(span.spanId, { ...span, children: [] })
  }

  for (const node of nodes.values()) {
    const parentId = node.parentSpanId || ''
    const parent = parentId ? nodes.get(parentId) : undefined
    if (parent) {
      parent.children.push(node)
    } else {
      roots.push(node)
    }
  }

  const sortNodes = (items: SpanNode[]) => {
    items.sort((a, b) => a.startTimestamp - b.startTimestamp)
    for (const item of items) {
      if (item.children.length > 0) sortNodes(item.children)
    }
  }
  sortNodes(roots)

  return roots
}

function flattenVisible(nodes: SpanNode[], collapsed: Set<string>, depth = 0): VisibleSpan[] {
  const rows: VisibleSpan[] = []
  for (const node of nodes) {
    rows.push({ span: node, depth })
    if (!collapsed.has(node.spanId)) {
      rows.push(...flattenVisible(node.children, collapsed, depth + 1))
    }
  }
  return rows
}

function TimeRuler({ durationMs }: TimeRulerProps) {
  const ticks = 6
  const points = Array.from({ length: ticks }, (_, i) => {
    const ratio = i / (ticks - 1)
    return {
      label: `${Math.round(durationMs * ratio)}ms`,
      left: `${ratio * 100}%`,
    }
  })

  return (
    <div className="grid grid-cols-[minmax(260px,36%)_1fr] border-b bg-muted/20 text-xs">
      <div className="flex items-center px-2 py-1.5 text-foreground/70 font-medium" aria-hidden="true">
        Time
      </div>
      <div className="relative h-9 overflow-hidden px-2 py-1">
        {points.map((tick) => (
          <div key={tick.left} className="absolute top-0 h-full" style={{ left: tick.left }}>
            <div className="h-3 w-px bg-border/80 shrink-0" />
            <div className="absolute left-1/2 -translate-x-1/2 top-[10px] whitespace-nowrap text-[10px] font-medium text-foreground/80">
              {tick.label}
            </div>
          </div>
        ))}
      </div>
    </div>
  )
}

function SpanTooltip({ span, x, y }: SpanTooltipProps) {
  return (
    <div
      className="fixed z-50 w-72 rounded-md border bg-popover px-3 py-2 text-xs text-popover-foreground"
      style={{ left: x, top: y }}
    >
      <div className="mb-1 font-semibold">{span.op || 'operation'}</div>
      <div className="text-foreground/90">{span.description || 'No description'}</div>
      <div className="mt-2 grid grid-cols-2 gap-x-3 gap-y-1">
        <span className="text-foreground/70">Duration</span>
        <span className="text-right font-medium">{span.duration.toFixed(2)}ms</span>
        <span className="text-foreground/70">Status</span>
        <span className="text-right font-medium">{span.status || 'unknown'}</span>
      </div>
    </div>
  )
}

type SpanRowProps = {
  readonly visible: VisibleSpan
  readonly durationSec: number
  readonly transactionStartSec: number
  readonly collapsed: Set<string>
  readonly onToggle: (spanId: string) => void
  readonly onHover: (span: Span, x: number, y: number) => void
  readonly onLeave: () => void
}

function SpanRow({
  visible,
  durationSec,
  transactionStartSec,
  collapsed,
  onToggle,
  onHover,
  onLeave,
}: SpanRowProps) {
  const { span, depth } = visible
  const hasChildren = span.children.length > 0
  const isCollapsed = collapsed.has(span.spanId)
  const leftPct = durationSec > 0 ? ((span.startTimestamp - transactionStartSec) / durationSec) * 100 : 0
  const widthPct = durationSec > 0 ? ((span.duration / 1000) / durationSec) * 100 : 0
  const durationLabel = span.duration >= 1000 ? `${(span.duration / 1000).toFixed(2)}s` : `${Math.round(span.duration)}ms`
  const showLabelInBar = widthPct >= 12

  return (
    <div className="grid min-h-8 grid-cols-[minmax(260px,36%)_1fr] border-b text-xs last:border-b-0">
      <div className="flex items-center gap-1 px-2 py-1">
        <div style={{ marginLeft: depth * 14 }} className="flex items-center gap-1 min-w-0">
          {hasChildren ? (
            <button
              type="button"
              onClick={() => onToggle(span.spanId)}
              className="rounded p-0.5 text-foreground/70 hover:bg-accent hover:text-foreground"
              aria-label={isCollapsed ? 'Expand span' : 'Collapse span'}
            >
              {isCollapsed ? <ChevronRight className="h-3.5 w-3.5" /> : <ChevronDown className="h-3.5 w-3.5" />}
            </button>
          ) : (
            <span className="inline-block h-3.5 w-3.5" />
          )}
          <span className="truncate font-medium text-foreground">{span.op || 'unknown'}</span>
          <span className="truncate text-foreground/80">{span.description || ''}</span>
        </div>
      </div>
      <div
        aria-hidden="true"
        className="relative px-2 py-1 min-h-6"
        onMouseMove={(e) => onHover(span, e.clientX + 12, e.clientY + 12)}
        onMouseLeave={onLeave}
      >
        <div
          className={cn('relative h-6 rounded-sm min-w-[2px] overflow-hidden', opColorClass(span.op))}
          style={{
            marginLeft: `${Math.max(0, leftPct)}%`,
            width: `${Math.max(1, Math.min(100, widthPct))}%`,
          }}
        >
          {showLabelInBar && (
            <span className="absolute inset-0 flex items-center px-1.5 text-[10px] font-semibold text-white drop-shadow-[0_0_1px_rgba(0,0,0,0.8)] select-none">
              {durationLabel}
            </span>
          )}
        </div>
      </div>
    </div>
  )
}

export function SpanWaterfall({ transaction, spans }: SpanWaterfallProps) {
  const [collapsed, setCollapsed] = useState<Set<string>>(new Set())
  const [hovered, setHovered] = useState<{ span: Span; x: number; y: number } | null>(null)
  const [scrollRatio, setScrollRatio] = useState(0)
  const rowsRef = useRef<HTMLDivElement | null>(null)

  const tree = useMemo(() => buildTree(spans), [spans])
  const visibleRows = useMemo(() => flattenVisible(tree, collapsed), [tree, collapsed])
  const durationSec = Math.max(transaction.duration / 1000, 0.001)

  const toggleSpan = (spanId: string) => {
    setCollapsed((current) => {
      const next = new Set(current)
      if (next.has(spanId)) next.delete(spanId)
      else next.add(spanId)
      return next
    })
  }

  const handleScroll = () => {
    if (!rowsRef.current) return
    const { scrollTop, scrollHeight, clientHeight } = rowsRef.current
    const max = Math.max(1, scrollHeight - clientHeight)
    setScrollRatio(scrollTop / max)
  }

  const handleMiniMapChange = (value: number) => {
    if (!rowsRef.current) return
    const { scrollHeight, clientHeight } = rowsRef.current
    const max = Math.max(0, scrollHeight - clientHeight)
    rowsRef.current.scrollTop = max * value
  }

  return (
    <div className="relative rounded-lg border bg-card">
      <TimeRuler durationMs={transaction.duration} />
      <div ref={rowsRef} className="max-h-[560px] overflow-auto" onScroll={handleScroll}>
        {visibleRows.map((row) => (
          <SpanRow
            key={row.span.spanId}
            visible={row}
            durationSec={durationSec}
            transactionStartSec={transaction.startTimestamp}
            collapsed={collapsed}
            onToggle={toggleSpan}
            onHover={(span, x, y) => setHovered({ span, x, y })}
            onLeave={() => setHovered(null)}
          />
        ))}
      </div>

      {visibleRows.length > 18 && (
        <div className="border-t px-3 py-2">
          <div className="mb-1 text-[11px] text-muted-foreground">Span minimap</div>
          <input
            type="range"
            min={0}
            max={100}
            value={Math.round(scrollRatio * 100)}
            onChange={(event) => handleMiniMapChange(Number(event.target.value) / 100)}
            className="w-full accent-primary"
          />
        </div>
      )}

      {hovered && <SpanTooltip span={hovered.span} x={hovered.x} y={hovered.y} />}
    </div>
  )
}
