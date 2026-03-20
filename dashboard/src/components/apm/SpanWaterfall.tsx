// Moneat - observability platform
// Copyright (C) 2026 Moneat
//
// This program is free software: you can redistribute it and/or modify
// it under the terms of the GNU Affero General Public License as published by
// the Free Software Foundation, either version 3 of the License, or
// (at your option) any later version.

import {type ApmSpanResponse} from '@/lib/api'
import {cn} from '@/lib/utils'
import {useState, useMemo, useCallback} from 'react'
import {Badge} from '@/components/ui/badge'
import {SourceBadge} from '@/components/apm/SourceBadge'
import {Link} from '@tanstack/react-router'
import {
  ChevronDown,
  ChevronRight,
  X,
  Clock,
  Server,
  Tag,
  AlertTriangle,
  Copy,
  Check,
  ExternalLink,
} from 'lucide-react'

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
  SERVER: 'bg-blue-500',
  CLIENT: 'bg-emerald-500',
  INTERNAL: 'bg-gray-500',
  PRODUCER: 'bg-orange-500',
  CONSUMER: 'bg-pink-500',
}

const SERVICE_COLORS_BORDER: Record<string, string> = {
  web: 'border-emerald-500',
  http: 'border-blue-500',
  db: 'border-amber-500',
  cache: 'border-violet-500',
  grpc: 'border-cyan-500',
  custom: 'border-gray-500',
  SERVER: 'border-blue-500',
  CLIENT: 'border-emerald-500',
  INTERNAL: 'border-gray-500',
  PRODUCER: 'border-orange-500',
  CONSUMER: 'border-pink-500',
}

function getSpanColorKey(span: ApmSpanResponse): string {
  return span.type || span.kind || 'custom'
}

function getSpanColor(key: string): string {
  return SERVICE_COLORS[key] || SERVICE_COLORS.custom
}

function getSpanBorderColor(key: string): string {
  return SERVICE_COLORS_BORDER[key] || SERVICE_COLORS_BORDER.custom
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

function CopyButton({text}: {text: string}) {
  const [copied, setCopied] = useState(false)

  const handleCopy = useCallback(() => {
    navigator.clipboard.writeText(text).then(() => {
      setCopied(true)
      setTimeout(() => setCopied(false), 2000)
    })
  }, [text])

  return (
    <button
      onClick={handleCopy}
      className="p-0.5 hover:bg-muted rounded transition-colors"
      title="Copy to clipboard"
    >
      {copied ? (
        <Check className="h-3 w-3 text-green-500" />
      ) : (
        <Copy className="h-3 w-3 text-muted-foreground" />
      )}
    </button>
  )
}

export function SpanWaterfall({spans}: Props) {
  const [collapsed, setCollapsed] = useState<Set<string>>(new Set())
  const [selectedSpanId, setSelectedSpanId] = useState<string | null>(null)

  const {flatSpans, traceStart, traceDuration} = useMemo(() => {
    const tree = buildTree(spans)
    const flatSpans = flattenTree(tree)
    const traceStart = Math.min(...spans.map((s) => s.startNs))
    const traceEnd = Math.max(...spans.map((s) => s.startNs + s.durationNs))
    return {flatSpans, traceStart, traceDuration: traceEnd - traceStart}
  }, [spans])

  const toggleCollapse = (spanId: string, e: React.MouseEvent) => {
    e.stopPropagation()
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

  const visibleSpans = useMemo(() => {
    const visible: SpanNode[] = []
    const hiddenParents = new Set<string>()

    for (const node of flatSpans) {
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

  const selectedSpan = useMemo(
    () => spans.find((s) => s.spanId === selectedSpanId) ?? null,
    [spans, selectedSpanId]
  )

  const spanColorKeys = useMemo(() => {
    const keys = new Set(spans.map((s) => getSpanColorKey(s)).filter((k) => k !== 'custom'))
    return [...keys]
  }, [spans])

  if (spans.length === 0) {
    return (
      <div className="text-center py-8 text-muted-foreground">
        No spans found
      </div>
    )
  }

  return (
    <div className="flex gap-4">
      {/* Main waterfall */}
      <div
        className={cn(
          'border rounded-lg overflow-hidden flex-1 min-w-0',
          selectedSpan && 'max-w-[calc(100%-380px)]'
        )}
      >
        {/* Legend */}
        {spanColorKeys.length > 0 && (
          <div className="flex items-center gap-3 px-3 py-1.5 border-b bg-muted/30 flex-wrap">
            <span className="text-[10px] text-muted-foreground uppercase font-medium tracking-wider">
              Types
            </span>
            {spanColorKeys.map((key) => (
              <div key={key} className="flex items-center gap-1">
                <span
                  className={cn(
                    'w-2 h-2 rounded-full',
                    getSpanColor(key)
                  )}
                />
                <span className="text-[11px] text-muted-foreground">
                  {key}
                </span>
              </div>
            ))}
          </div>
        )}

        {/* Header */}
        <div className="grid grid-cols-[minmax(280px,2fr)_minmax(200px,3fr)] border-b bg-muted/50 px-2 py-1.5 text-xs font-medium text-muted-foreground">
          <div>Operation</div>
          <div>Timeline ({formatDuration(traceDuration)})</div>
        </div>

        {/* Rows */}
        <div className="divide-y max-h-[600px] overflow-y-auto">
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
            const isSelected = selectedSpanId === span.spanId

            return (
              <div
                key={span.spanId}
                className={cn(
                  'grid grid-cols-[minmax(280px,2fr)_minmax(200px,3fr)] items-center px-2 py-1 text-sm cursor-pointer transition-colors',
                  isSelected
                    ? 'bg-primary/5 hover:bg-primary/8'
                    : 'hover:bg-muted/30'
                )}
                onClick={() =>
                  setSelectedSpanId(
                    selectedSpanId === span.spanId ? null : span.spanId
                  )
                }
              >
                {/* Operation name */}
                <div
                  className="flex items-center gap-1 min-w-0"
                  style={{paddingLeft: `${node.depth * 16}px`}}
                >
                  {hasChildren ? (
                    <button
                      onClick={(e) => toggleCollapse(span.spanId, e)}
                      className="p-0.5 hover:bg-muted rounded shrink-0"
                    >
                      {isCollapsed ? (
                        <ChevronRight className="h-3 w-3" />
                      ) : (
                        <ChevronDown className="h-3 w-3" />
                      )}
                    </button>
                  ) : (
                    <span className="w-4 shrink-0" />
                  )}
                  <span
                    className={cn(
                      'w-2 h-2 rounded-full shrink-0',
                      getSpanColor(getSpanColorKey(span))
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
                      className="h-4 text-[10px] px-1 shrink-0"
                    >
                      ERR
                    </Badge>
                  )}
                </div>

                {/* Timeline bar */}
                <div className="relative h-6">
                  <div
                    className={cn(
                      'absolute top-1.5 h-3 rounded-sm transition-opacity',
                      span.error > 0
                        ? 'bg-red-500/70'
                        : getSpanColor(getSpanColorKey(span)),
                      span.error === 0 && 'opacity-70',
                      isSelected && 'opacity-100 ring-1 ring-primary'
                    )}
                    style={{
                      left: `${left}%`,
                      width: `${width}%`,
                      minWidth: '3px',
                    }}
                  />
                  <span
                    className="absolute top-1 text-[10px] text-muted-foreground tabular-nums"
                    style={{left: `${Math.min(left + width + 1, 85)}%`}}
                  >
                    {formatDuration(span.durationNs)}
                  </span>
                </div>
              </div>
            )
          })}
        </div>
      </div>

      {/* Span detail panel */}
      {selectedSpan && (
        <SpanDetailPanel
          span={selectedSpan}
          traceStart={traceStart}
          onClose={() => setSelectedSpanId(null)}
        />
      )}
    </div>
  )
}

interface SpanEvent {
  name: string
  timeUnixNano?: string
  attributes?: Record<string, string>
}

interface SpanLink {
  traceId: string
  spanId: string
  attributes?: Record<string, string>
}

function parseSpanEvents(eventsJson?: string): SpanEvent[] {
  if (!eventsJson || eventsJson === '[]') return []
  try {
    return JSON.parse(eventsJson)
  } catch {
    return []
  }
}

function parseSpanLinks(linksJson?: string): SpanLink[] {
  if (!linksJson || linksJson === '[]') return []
  try {
    return JSON.parse(linksJson)
  } catch {
    return []
  }
}

function spanEventStableKey(spanId: string, evt: SpanEvent): string {
  return `${spanId}:${JSON.stringify(evt)}`
}

function spanLinkStableKey(link: SpanLink): string {
  const attrs =
    link.attributes &&
    Object.entries(link.attributes)
      .sort(([a], [b]) => a.localeCompare(b))
      .map(([k, v]) => `${k}=${v}`)
      .join('|')
  return `${link.traceId}:${link.spanId ?? ''}:${attrs ?? ''}`
}

function SpanDetailPanel({
  span,
  traceStart,
  onClose,
}: {
  span: ApmSpanResponse
  traceStart: number
  onClose: () => void
}) {
  const offsetFromStart = span.startNs - traceStart
  const colorKey = getSpanColorKey(span)
  const spanEvents = useMemo(() => parseSpanEvents(span.events), [span.events])
  const spanLinks = useMemo(() => parseSpanLinks(span.links), [span.links])
  const hasResourceAttrs = span.resourceAttributes && Object.keys(span.resourceAttributes).length > 0

  return (
    <div className="w-[360px] shrink-0 border rounded-lg overflow-hidden">
      {/* Panel header */}
      <div
        className={cn(
          'flex items-center justify-between px-3 py-2 border-b border-l-2',
          getSpanBorderColor(colorKey)
        )}
      >
        <div className="min-w-0 flex-1">
          <div className="flex items-center gap-1.5">
            <span
              className={cn(
                'w-2 h-2 rounded-full shrink-0',
                getSpanColor(colorKey)
              )}
            />
            <span className="font-semibold text-sm truncate">
              {span.service}
            </span>
            <SourceBadge source={span.source} />
          </div>
          <p className="text-xs text-muted-foreground truncate mt-0.5">
            {span.name}
          </p>
        </div>
        <button
          onClick={onClose}
          className="p-1 hover:bg-muted rounded shrink-0"
        >
          <X className="h-4 w-4" />
        </button>
      </div>

      <div className="overflow-y-auto max-h-[540px]">
        {/* Quick info */}
        <div className="grid grid-cols-2 gap-px bg-muted/30">
          <InfoCell
            icon={<Clock className="h-3 w-3" />}
            label="Duration"
            value={formatDuration(span.durationNs)}
          />
          <InfoCell
            icon={<Clock className="h-3 w-3" />}
            label="Offset"
            value={`+${formatDuration(offsetFromStart)}`}
          />
          <InfoCell
            icon={<Server className="h-3 w-3" />}
            label="Service"
            value={span.service}
          />
          <InfoCell
            icon={<Tag className="h-3 w-3" />}
            label={span.kind ? 'Kind' : 'Type'}
            value={span.kind || span.type || '—'}
          />
        </div>

        {/* Status message for OTLP spans */}
        {span.statusMessage && (
          <div className="mx-3 mt-3 rounded-md bg-muted/50 border px-3 py-2">
            <div className="text-[10px] text-muted-foreground uppercase font-medium tracking-wider mb-0.5">
              Status Message
            </div>
            <span className="text-xs">{span.statusMessage}</span>
          </div>
        )}

        {/* Error indicator */}
        {span.error > 0 && (
          <div className="mx-3 mt-3 rounded-md bg-red-500/10 border border-red-500/20 px-3 py-2 flex items-center gap-2">
            <AlertTriangle className="h-4 w-4 text-red-500 shrink-0" />
            <span className="text-sm text-red-500 font-medium">
              This span has an error
            </span>
          </div>
        )}

        {/* Resource */}
        {span.resource && (
          <div className="px-3 pt-3">
            <div className="text-[11px] text-muted-foreground uppercase font-medium tracking-wider mb-1">
              Resource
            </div>
            <div className="bg-muted/50 rounded px-2 py-1.5 text-xs font-mono break-all flex items-start gap-1">
              <span className="flex-1">{span.resource}</span>
              <CopyButton text={span.resource} />
            </div>
          </div>
        )}

        {/* IDs */}
        <div className="px-3 pt-3">
          <div className="text-[11px] text-muted-foreground uppercase font-medium tracking-wider mb-1.5">
            Identifiers
          </div>
          <div className="space-y-1">
            <IdRow label="Span ID" value={span.spanId} />
            <IdRow label="Trace ID" value={span.traceId} />
            {span.parentId !== '0' && span.parentId !== '' && (
              <IdRow label="Parent ID" value={span.parentId} />
            )}
          </div>
        </div>

        {/* Host / Env / Version */}
        {(span.host || span.env || span.version) && (
          <div className="px-3 pt-3">
            <div className="text-[11px] text-muted-foreground uppercase font-medium tracking-wider mb-1.5">
              Infrastructure
            </div>
            <div className="space-y-1">
              {span.host && <IdRow label="Host" value={span.host} />}
              {span.env && <IdRow label="Env" value={span.env} />}
              {span.version && <IdRow label="Version" value={span.version} />}
            </div>
          </div>
        )}

        {/* Span Events (OTLP) */}
        {spanEvents.length > 0 && (
          <div className="px-3 pt-3">
            <div className="text-[11px] text-muted-foreground uppercase font-medium tracking-wider mb-1.5">
              Events ({spanEvents.length})
            </div>
            <div className="space-y-2">
              {spanEvents.map((evt) => (
                <div key={spanEventStableKey(span.spanId, evt)} className="rounded border bg-muted/30 px-2 py-1.5">
                  <div className="flex items-center gap-1.5">
                    <span className="text-xs font-medium">{evt.name}</span>
                    {evt.name === 'exception' && (
                      <Badge variant="destructive" className="h-4 text-[10px] px-1">
                        exception
                      </Badge>
                    )}
                  </div>
                  {evt.attributes && Object.keys(evt.attributes).length > 0 && (
                    <div className="mt-1 space-y-0.5">
                      {Object.entries(evt.attributes).map(([k, v]) => (
                        <div key={k} className="flex items-start gap-2 text-xs">
                          <span className="text-muted-foreground font-mono shrink-0 min-w-[80px]">{k}</span>
                          <span className="break-all flex-1 text-[11px]">{v}</span>
                        </div>
                      ))}
                    </div>
                  )}
                </div>
              ))}
            </div>
          </div>
        )}

        {/* Span Links (OTLP) */}
        {spanLinks.length > 0 && (
          <div className="px-3 pt-3">
            <div className="text-[11px] text-muted-foreground uppercase font-medium tracking-wider mb-1.5">
              Linked Traces ({spanLinks.length})
            </div>
            <div className="space-y-1">
              {spanLinks.map((link) => (
                <div key={spanLinkStableKey(link)} className="flex items-center gap-2 text-xs">
                  <Link
                    to="/performance/traces/$traceId"
                    params={{traceId: link.traceId}}
                    className="font-mono text-[11px] text-primary hover:underline flex items-center gap-1"
                    onClick={(e) => e.stopPropagation()}
                  >
                    {link.traceId.slice(0, 16)}...
                    <ExternalLink className="h-3 w-3" />
                  </Link>
                  {link.spanId && (
                    <span className="text-muted-foreground font-mono text-[10px]">
                      span: {link.spanId.slice(0, 12)}...
                    </span>
                  )}
                </div>
              ))}
            </div>
          </div>
        )}

        {/* Resource Attributes (OTLP) */}
        {hasResourceAttrs && (
          <div className="px-3 pt-3">
            <div className="text-[11px] text-muted-foreground uppercase font-medium tracking-wider mb-1.5">
              Resource Attributes ({Object.keys(span.resourceAttributes!).length})
            </div>
            <div className="space-y-0.5 max-h-[200px] overflow-y-auto">
              {Object.entries(span.resourceAttributes!).map(([k, v]) => (
                <div
                  key={k}
                  className="flex items-start gap-2 text-xs py-0.5 group"
                >
                  <span className="text-muted-foreground font-mono shrink-0 min-w-[100px]">
                    {k}
                  </span>
                  <span className="break-all flex-1">{v}</span>
                  <span className="opacity-0 group-hover:opacity-100 transition-opacity">
                    <CopyButton text={v} />
                  </span>
                </div>
              ))}
            </div>
          </div>
        )}

        {/* Meta tags */}
        {span.meta && Object.keys(span.meta).length > 0 && (
          <div className="px-3 pt-3">
            <div className="text-[11px] text-muted-foreground uppercase font-medium tracking-wider mb-1.5">
              Tags ({Object.keys(span.meta).length})
            </div>
            <div className="space-y-0.5 max-h-[200px] overflow-y-auto">
              {Object.entries(span.meta).map(([k, v]) => (
                <div
                  key={k}
                  className="flex items-start gap-2 text-xs py-0.5 group"
                >
                  <span className="text-muted-foreground font-mono shrink-0 min-w-[100px]">
                    {k}
                  </span>
                  <span className="break-all flex-1">{v}</span>
                  <span className="opacity-0 group-hover:opacity-100 transition-opacity">
                    <CopyButton text={v} />
                  </span>
                </div>
              ))}
            </div>
          </div>
        )}

        {/* Metrics */}
        {span.metrics && Object.keys(span.metrics).length > 0 && (
          <div className="px-3 pt-3 pb-3">
            <div className="text-[11px] text-muted-foreground uppercase font-medium tracking-wider mb-1.5">
              Metrics ({Object.keys(span.metrics).length})
            </div>
            <div className="space-y-0.5">
              {Object.entries(span.metrics).map(([k, v]) => (
                <div key={k} className="flex items-center gap-2 text-xs py-0.5">
                  <span className="text-muted-foreground font-mono shrink-0 min-w-[100px]">
                    {k}
                  </span>
                  <span className="font-mono tabular-nums">{v}</span>
                </div>
              ))}
            </div>
          </div>
        )}
      </div>
    </div>
  )
}

function InfoCell({
  icon,
  label,
  value,
}: {
  icon: React.ReactNode
  label: string
  value: string
}) {
  return (
    <div className="px-3 py-2">
      <div className="flex items-center gap-1 text-muted-foreground mb-0.5">
        {icon}
        <span className="text-[10px] uppercase tracking-wider">{label}</span>
      </div>
      <span className="text-sm font-medium font-mono">{value}</span>
    </div>
  )
}

function IdRow({label, value}: {label: string; value: string}) {
  return (
    <div className="flex items-center gap-2 text-xs">
      <span className="text-muted-foreground w-16 shrink-0">{label}</span>
      <span className="font-mono text-[11px] truncate flex-1">{value}</span>
      <CopyButton text={value} />
    </div>
  )
}
