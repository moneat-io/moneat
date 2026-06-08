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

import React, {useCallback, useEffect, useMemo, useRef, useState} from 'react'
import {useTimezone} from '@/hooks/useTimezone'
import {formatTimeWithMs} from '@/lib/date-format'
import {Link} from '@tanstack/react-router'
import {useQuery} from '@tanstack/react-query'
import type {ReplayTimelineItem as BaseTimelineItem} from '@/lib/api'
import {api} from '@/lib/api'
import {Tabs, TabsContent, TabsList, TabsTrigger} from '@/components/ui/tabs'
import {Badge} from '@/components/ui/badge'
import {SpanWaterfall} from '@/components/SpanWaterfall'
import {cn} from '@/lib/utils'
import {
    Activity,
    AlertCircle,
    ChevronDown,
    ChevronRight,
    Clock,
    DatabaseZap,
    ExternalLink,
    Layers,
    Loader2,
    MousePointerClick,
    Navigation,
    Network,
    Tag,
} from 'lucide-react'

/** Extended item type that can carry raw breadcrumb payload data */
export type TimelineItem = BaseTimelineItem & {
  data?: Record<string, unknown>
}

export interface ReplayTimelinePanelProps {
  readonly items: TimelineItem[]
  readonly currentOffsetMs: number
  /** Project resource ID for replay links; legacy numeric IDs are accepted during migration. */
  readonly projectId?: string | number
  readonly onSeek: (offsetMs: number) => void
}

type FilterValue = 'all' | 'error' | 'transaction' | 'span'

function issueSearch(projectId?: string | number): { projectId: string | undefined } {
  return { projectId: projectId === undefined ? undefined : String(projectId) }
}

function formatOffset(offsetMs: number): string {
  if (offsetMs < 0) return '0:00'
  const totalSeconds = Math.floor(offsetMs / 1000)
  const minutes = Math.floor(totalSeconds / 60)
  const seconds = totalSeconds % 60
  if (minutes > 0) {
    return `+${minutes}m ${String(seconds).padStart(2, '0')}s`
  }
  return `+${seconds}.${String(Math.floor((offsetMs % 1000) / 100))}s`
}

function formatDurationLabel(ms: number): string {
  if (ms >= 1000) return `${(ms / 1000).toFixed(2)}s`
  return `${Math.round(ms)}ms`
}

function formatTimestamp(isoString: string, timezone: string): string {
  if (!isoString) return ''
  const date = new Date(isoString)
  if (Number.isNaN(date.getTime())) return ''
  return formatTimeWithMs(date, timezone)
}

function findActiveIndex(items: TimelineItem[], currentOffsetMs: number): number {
  if (items.length === 0) return -1
  let lo = 0
  let hi = items.length - 1
  let best = -1
  while (lo <= hi) {
    const mid = Math.floor((lo + hi) / 2)
    if (items[mid].offsetMs <= currentOffsetMs) {
      best = mid
      lo = mid + 1
    } else {
      hi = mid - 1
    }
  }
  return best
}

/* ── Color helpers ── */

function typeColorClasses(type: TimelineItem['type']) {
  switch (type) {
    case 'error':
      return {
        border: 'border-l-danger-solid',
        bg: 'bg-danger-bg/60 dark:bg-danger-bg',
        bgActive: 'bg-danger-bg dark:bg-danger-bg',
        text: 'text-danger-fg',
        badge: 'bg-danger-bg text-danger-fg',
        dot: 'bg-danger-solid',
      }
    case 'transaction':
      return {
        border: 'border-l-chart-2',
        bg: 'bg-chart-2/[0.08] dark:bg-chart-2/10',
        bgActive: 'bg-chart-2/15 dark:bg-chart-2/20',
        text: 'text-chart-2',
        badge: 'bg-chart-2/15 text-chart-2',
        dot: 'bg-chart-2',
      }
    case 'span':
      return {
        border: 'border-l-chart-4',
        bg: 'bg-chart-4/[0.08] dark:bg-chart-4/10',
        bgActive: 'bg-chart-4/15 dark:bg-chart-4/20',
        text: 'text-chart-4',
        badge: 'bg-chart-4/15 text-chart-4',
        dot: 'bg-chart-4',
      }
    default:
      return {
        border: 'border-l-border',
        bg: 'bg-muted/40 dark:bg-muted/40',
        bgActive: 'bg-muted dark:bg-muted',
        text: 'text-muted-foreground',
        badge: 'bg-muted text-muted-foreground',
        dot: 'bg-muted-foreground/70',
      }
  }
}

function TypeIcon({ type, className }: { readonly type: TimelineItem['type']; readonly className?: string }) {
  const colors = typeColorClasses(type)
  switch (type) {
    case 'error':
      return <AlertCircle className={cn('h-4 w-4 shrink-0', colors.text, className)} />
    case 'transaction':
      return <Layers className={cn('h-4 w-4 shrink-0', colors.text, className)} />
    case 'span':
      return <Activity className={cn('h-4 w-4 shrink-0', colors.text, className)} />
    default:
      return <Activity className={cn('h-4 w-4 shrink-0', colors.text, className)} />
  }
}

/** Pick a contextual icon based on the breadcrumb category */
function CategoryIcon({ category, className }: { readonly category?: string; readonly className?: string }) {
  const cat = (category ?? '').toLowerCase()
  if (cat.includes('http') || cat.includes('network'))
    return <Network className={cn('h-3.5 w-3.5 text-chart-2', className)} />
  if (cat.includes('navigation') || cat.includes('nav'))
    return <Navigation className={cn('h-3.5 w-3.5 text-chart-10', className)} />
  if (cat.includes('ui.click') || cat.includes('touch') || cat.includes('gesture'))
    return <MousePointerClick className={cn('h-3.5 w-3.5 text-chart-7', className)} />
  if (cat.includes('ui'))
    return <Activity className={cn('h-3.5 w-3.5 text-chart-5', className)} />
  return <Tag className={cn('h-3.5 w-3.5 text-muted-foreground', className)} />
}

/* ── Does this item have fetchable trace data? ── */

function canFetchSpans(item: TimelineItem): boolean {
  if (item.type === 'transaction' && !!item.eventId) return true
  if (item.traceId) return true
  return false
}

/* ── Breadcrumb detail panel (for items without trace data) ── */

/** Well-known payload keys we want to surface prominently */
const PROMOTED_KEYS: Record<string, string> = {
  method: 'Method',
  url: 'URL',
  status_code: 'Status',
  reason: 'Reason',
  screen: 'Screen',
  state: 'State',
  from: 'From',
  to: 'To',
  action: 'Action',
  'view.class': 'View Class',
  'view.id': 'View ID',
  level: 'Level',
  message: 'Message',
  duration: 'Duration',
}

function serializeValue(value: unknown): string {
  if (typeof value === 'object' && value !== null) return JSON.stringify(value)
  return String(value ?? '')
}

function BreadcrumbDetailPanel({
  item,
  projectId,
}: {
  readonly item: TimelineItem
  readonly projectId?: string | number
}) {
  const { timezone } = useTimezone()
  const colors = typeColorClasses(item.type)
  const data = item.data ?? {}

  // Split payload into promoted (well-known) and extra keys
  const promoted: { label: string; value: string }[] = []
  const extra: { key: string; value: unknown }[] = []

  for (const [key, value] of Object.entries(data)) {
    if (value == null || value === '') continue
    // Skip category/type since we show it as a badge
    if (key === 'category' || key === 'type') continue
    const label = PROMOTED_KEYS[key]
    if (label) {
      promoted.push({ label, value: serializeValue(value) })
    } else {
      extra.push({ key, value })
    }
  }

  return (
    <div className={cn('border-t', colors.bg)}>
      {/* Header */}
      <div className="flex items-center gap-2 px-3 py-2 border-b border-border/50">
        <CategoryIcon category={item.category} />
        <span className="text-xs font-medium">Breadcrumb Details</span>
        {item.category && (
          <Badge variant="outline" className="text-[10px] font-mono px-1.5 py-0">
            {item.category}
          </Badge>
        )}
      </div>

      <div className="px-3 py-2.5 space-y-2.5">
        {/* Timestamp */}
        <div className="flex items-center gap-2 text-xs">
          <Clock className="h-3 w-3 text-muted-foreground" />
          <span className="text-muted-foreground">Time:</span>
          <span className="font-mono">{formatTimestamp(item.timestamp, timezone)}</span>
          <span className="text-muted-foreground font-mono">({formatOffset(item.offsetMs)})</span>
        </div>

        {/* Description */}
        {item.description && (
          <div className="text-sm">{item.description}</div>
        )}

        {/* Promoted fields */}
        {promoted.length > 0 && (
          <div className="rounded-md border bg-background/50 divide-y divide-border/50">
            {promoted.map(({ label, value }) => (
              <div key={label} className="flex items-start justify-between gap-3 px-3 py-1.5 text-xs">
                <span className="text-muted-foreground shrink-0">{label}</span>
                <span className="font-mono text-right break-all min-w-0">{value}</span>
              </div>
            ))}
          </div>
        )}

        {/* Extra fields */}
        {extra.length > 0 && (
          <details className="group">
            <summary className="text-[11px] text-muted-foreground cursor-pointer hover:text-foreground transition-colors select-none">
              {extra.length} more field{extra.length === 1 ? '' : 's'}
            </summary>
            <pre className="mt-1.5 rounded-md border bg-muted/30 px-3 py-2 text-[11px] font-mono overflow-x-auto whitespace-pre-wrap break-all text-muted-foreground">
{extra.map(({ key, value }) => `${key}: ${serializeValue(value)}`).join('\n')}
            </pre>
          </details>
        )}

        {/* Links */}
        <div className="flex items-center gap-2 pt-0.5">
          {item.issueId && (
            <Link
              to="/issues/$issueId"
              params={{ issueId: item.issueId }}
              search={issueSearch(projectId)}
              className="inline-flex items-center gap-1 text-[11px] font-medium text-danger-fg hover:underline"
            >
              View Issue <ExternalLink className="h-3 w-3" />
            </Link>
          )}
        </div>
      </div>
    </div>
  )
}

/* ── Waterfall panel (for items with trace data) ── */

function WaterfallPanel({
  item,
  projectId,
}: {
  readonly item: TimelineItem
  readonly projectId?: string | number
}) {
  const colors = typeColorClasses(item.type)

  const hasEventId = item.type === 'transaction' && !!item.eventId
  const hasTraceId = !!item.traceId && !!projectId

  const { data: txnData, isLoading: txnLoading } = useQuery({
    queryKey: ['transaction-spans', item.eventId],
    queryFn: () => api.getTransactionSpans(item.eventId!),
    enabled: hasEventId,
  })

  const { data: traceData, isLoading: traceLoading } = useQuery({
    queryKey: ['trace-detail', projectId, item.traceId],
    queryFn: () => api.getTraceDetails(projectId!, item.traceId!),
    enabled: !hasEventId && hasTraceId,
  })

  const isLoading = hasEventId ? txnLoading : traceLoading
  const transaction = txnData?.transaction ?? null
  const spans = txnData?.spans ?? traceData?.spans ?? []

  const effectiveTransaction = transaction ?? (traceData ? {
    eventId: '',
    name: item.title,
    op: item.category ?? 'trace',
    startTimestamp: traceData.startTimestamp,
    duration: traceData.duration,
    traceId: traceData.traceId,
    timestamp: '',
    tags: {},
    contexts: '{}',
  } : null)

  const canRenderWaterfall = effectiveTransaction && spans.length > 0

  return (
    <div className={cn('border-t', colors.bg)}>
      {/* Header */}
      <div className="flex items-center justify-between px-3 py-2 border-b border-border/50">
        <div className="flex items-center gap-2 text-xs">
          <DatabaseZap className={cn('h-3.5 w-3.5', colors.text)} />
          <span className="font-medium">Span Waterfall</span>
          {spans.length > 0 && (
            <Badge variant="secondary" className="text-[10px] px-1.5 py-0">
              {spans.length} span{spans.length === 1 ? '' : 's'}
            </Badge>
          )}
        </div>
        <div className="flex items-center gap-2">
          {item.issueId && (
            <Link
              to="/issues/$issueId"
              params={{ issueId: item.issueId }}
              search={issueSearch(projectId)}
              className="inline-flex items-center gap-1 text-[11px] font-medium text-danger-fg hover:underline"
            >
              View Issue <ExternalLink className="h-3 w-3" />
            </Link>
          )}
          {item.traceId && item.type === 'transaction' && (
            <Link
              to="/performance/traces/$traceId"
              params={{ traceId: item.traceId }}
              className="inline-flex items-center gap-1 text-[11px] font-medium text-chart-2 hover:underline"
            >
              Full Trace <ExternalLink className="h-3 w-3" />
            </Link>
          )}
        </div>
      </div>

      {/* Content */}
      <div className="px-1 py-1">
        {isLoading && (
          <div className="flex items-center justify-center gap-2 py-8 text-xs text-muted-foreground">
            <Loader2 className="h-4 w-4 animate-spin" />
            Loading spans...
          </div>
        )}
        {!isLoading && canRenderWaterfall && (
          <div className="max-h-[300px] overflow-auto rounded-md">
            <SpanWaterfall transaction={effectiveTransaction} spans={spans} />
          </div>
        )}
        {!isLoading && !canRenderWaterfall && (
          <div className="flex flex-col items-center justify-center gap-1.5 py-6 text-xs text-muted-foreground">
            <Activity className="h-5 w-5 opacity-40" />
            <span>No spans found for this trace</span>
          </div>
        )}
      </div>
    </div>
  )
}

/* ── Expanded item: dispatches to waterfall or breadcrumb detail ── */

function ExpandedItemPanel({
  item,
  projectId,
}: {
  readonly item: TimelineItem
  readonly projectId?: string | number
}) {
  if (canFetchSpans(item)) {
    return <WaterfallPanel item={item} projectId={projectId} />
  }
  return <BreadcrumbDetailPanel item={item} projectId={projectId} />
}

/* ── Main component ── */

export function ReplayTimelinePanel({ items, currentOffsetMs, projectId, onSeek }: ReplayTimelinePanelProps) {
  const listRef = useRef<HTMLDivElement>(null)
  const [tab, setTab] = useState<FilterValue>('all')
  const [expandedId, setExpandedId] = useState<string | null>(null)
  const activeIndex = useMemo(() => findActiveIndex(items, currentOffsetMs), [items, currentOffsetMs])

  const activeItem = activeIndex >= 0 ? items[activeIndex] : null
  const errorItems = useMemo(() => items.filter((i) => i.type === 'error'), [items])
  const transactionItems = useMemo(() => items.filter((i) => i.type === 'transaction'), [items])
  const spanItems = useMemo(() => items.filter((i) => i.type === 'span'), [items])
  const activeErrorIndex = activeItem?.type === 'error' ? errorItems.findIndex((it) => it.id === activeItem.id) : -1
  const activeTransactionIndex = activeItem?.type === 'transaction' ? transactionItems.findIndex((it) => it.id === activeItem.id) : -1
  const activeSpanIndex = activeItem?.type === 'span' ? spanItems.findIndex((it) => it.id === activeItem.id) : -1
  let scrollIndex: number
  if (tab === 'all') {
    scrollIndex = activeIndex
  } else if (tab === 'error') {
    scrollIndex = activeErrorIndex
  } else if (tab === 'transaction') {
    scrollIndex = activeTransactionIndex
  } else {
    scrollIndex = activeSpanIndex
  }

  useEffect(() => {
    if (scrollIndex >= 0 && listRef.current) {
      const container = listRef.current
      const el = container.querySelector<HTMLElement>(`[data-timeline-index="${scrollIndex}"]`)
      if (el) {
        const padding = 8
        const viewTop = container.scrollTop
        const viewBottom = viewTop + container.clientHeight
        const elTop = el.offsetTop
        const elBottom = elTop + el.offsetHeight

        if (elTop < viewTop + padding) {
          container.scrollTop = Math.max(0, elTop - padding)
        } else if (elBottom > viewBottom - padding) {
          container.scrollTop = Math.max(0, elBottom - container.clientHeight + padding)
        }
      }
    }
  }, [scrollIndex])

  const handleItemClick = useCallback((item: TimelineItem) => {
    onSeek(item.offsetMs)
    setExpandedId((prev) => (prev === item.id ? null : item.id))
  }, [onSeek])

  return (
    <div className="h-full min-h-0 rounded-lg border bg-card">
      <Tabs
        value={tab}
        onValueChange={(v) => setTab(v as FilterValue)}
        className="w-full h-full min-h-0 flex flex-col"
      >
        {/* Colored tab bar */}
        <div className="px-2 pt-2">
          <TabsList className="w-full grid grid-cols-4 h-9 gap-1 p-1 bg-muted/50">
            <TabsTrigger value="all" className="text-xs data-[state=active]:bg-background">
              All <span className="ml-1 text-[10px] font-mono text-muted-foreground">{items.length}</span>
            </TabsTrigger>
            <TabsTrigger value="error" className="text-xs data-[state=active]:bg-background data-[state=active]:text-danger-fg">
              <span className="flex items-center gap-1">
                <span className="h-1.5 w-1.5 rounded-full bg-danger-solid" />Errors
              </span>
              <span className="ml-1 text-[10px] font-mono">{errorItems.length}</span>
            </TabsTrigger>
            <TabsTrigger value="transaction" className="text-xs data-[state=active]:bg-background data-[state=active]:text-chart-2">
              <span className="flex items-center gap-1">
                <span className="h-1.5 w-1.5 rounded-full bg-chart-2" />Txns
              </span>
              <span className="ml-1 text-[10px] font-mono">{transactionItems.length}</span>
            </TabsTrigger>
            <TabsTrigger value="span" className="text-xs data-[state=active]:bg-background data-[state=active]:text-chart-4">
              <span className="flex items-center gap-1">
                <span className="h-1.5 w-1.5 rounded-full bg-chart-4" />Spans
              </span>
              <span className="ml-1 text-[10px] font-mono">{spanItems.length}</span>
            </TabsTrigger>
          </TabsList>
        </div>

        <TabsContent value="all" className="mt-0 flex-1 min-h-0 px-2 pb-2">
          <TimelineList
            ref={listRef}
            items={items}
            activeIndex={activeIndex}
            expandedId={expandedId}
            projectId={projectId}
            onItemClick={handleItemClick}
          />
        </TabsContent>
        <TabsContent value="error" className="mt-0 flex-1 min-h-0 px-2 pb-2">
          <TimelineList
            ref={listRef}
            items={errorItems}
            activeIndex={activeErrorIndex}
            expandedId={expandedId}
            projectId={projectId}
            onItemClick={handleItemClick}
          />
        </TabsContent>
        <TabsContent value="transaction" className="mt-0 flex-1 min-h-0 px-2 pb-2">
          <TimelineList
            ref={listRef}
            items={transactionItems}
            activeIndex={activeTransactionIndex}
            expandedId={expandedId}
            projectId={projectId}
            onItemClick={handleItemClick}
          />
        </TabsContent>
        <TabsContent value="span" className="mt-0 flex-1 min-h-0 px-2 pb-2">
          <TimelineList
            ref={listRef}
            items={spanItems}
            activeIndex={activeSpanIndex}
            expandedId={expandedId}
            projectId={projectId}
            onItemClick={handleItemClick}
          />
        </TabsContent>
      </Tabs>
    </div>
  )
}

/* ── Timeline list ── */

interface TimelineListProps {
  readonly items: TimelineItem[]
  readonly activeIndex: number
  readonly expandedId: string | null
  readonly projectId?: string | number
  readonly onItemClick: (item: TimelineItem) => void
}

const TimelineList = React.forwardRef<HTMLDivElement, TimelineListProps>(function TimelineList(
  { items, activeIndex, expandedId, projectId, onItemClick },
  ref
) {
  return (
    <div
      ref={ref}
      className="h-full min-h-[280px] overflow-y-auto rounded-md border bg-muted/20 mt-2"
    >
      {items.length === 0 ? (
        <div className="p-6 text-sm text-muted-foreground text-center">
          <Activity className="h-8 w-8 mx-auto mb-2 opacity-30" />
          No timeline items in this category.
        </div>
      ) : (
        <ul className="divide-y divide-border/50">
          {items.map((item, index) => {
            const isActive = index === activeIndex
            const isExpanded = expandedId === item.id
            const colors = typeColorClasses(item.type)
            let itemBgClass: string
            if (isActive) {
              itemBgClass = colors.bgActive
            } else if (isExpanded) {
              itemBgClass = colors.bg
            } else {
              itemBgClass = 'hover:bg-muted/40'
            }

            return (
              <li key={item.id}>
                <button
                  type="button"
                  data-timeline-index={index}
                  onClick={() => onItemClick(item)}
                  className={cn(
                    'w-full text-left flex items-center gap-3 px-3 py-2.5 border-l-[3px] transition-all duration-150',
                    colors.border,
                    itemBgClass,
                  )}
                >
                  {/* Expand indicator */}
                  <div className="shrink-0">
                    {isExpanded ? (
                      <ChevronDown className={cn('h-3.5 w-3.5', colors.text)} />
                    ) : (
                      <ChevronRight className="h-3.5 w-3.5 text-muted-foreground/50" />
                    )}
                  </div>

                  <TypeIcon type={item.type} />

                  <div className="min-w-0 flex-1">
                    <div className="flex items-center gap-2 flex-wrap">
                      <span className="font-mono text-[11px] text-muted-foreground shrink-0 tabular-nums">
                        {formatOffset(item.offsetMs)}
                      </span>
                      {item.durationMs != null && item.durationMs > 0 && (
                        <span className={cn(
                          'text-[10px] px-1.5 py-0.5 rounded-full font-mono font-medium',
                          colors.badge,
                        )}>
                          {formatDurationLabel(item.durationMs)}
                        </span>
                      )}
                      {item.category && (
                        <span className="text-[10px] px-1.5 py-0.5 rounded bg-muted text-muted-foreground font-mono">
                          {item.category}
                        </span>
                      )}
                      {isActive && (
                        <span className="h-1.5 w-1.5 rounded-full bg-primary animate-pulse" />
                      )}
                    </div>
                    <div className="text-sm font-medium truncate mt-0.5" title={item.title}>
                      {item.title}
                    </div>
                    {item.description && !isExpanded && (
                      <div className="text-xs text-muted-foreground truncate" title={item.description}>
                        {item.description}
                      </div>
                    )}
                  </div>

                  {/* Quick link icons */}
                  {item.issueId && (
                    <Link
                      to="/issues/$issueId"
                      params={{ issueId: item.issueId }}
                      search={issueSearch(projectId)}
                      onClick={(e) => e.stopPropagation()}
                      className="shrink-0 p-1.5 rounded-md hover:bg-danger-bg text-danger-fg transition-colors"
                      aria-label="View issue"
                    >
                      <ExternalLink className="h-3.5 w-3.5" />
                    </Link>
                  )}
                  {item.traceId && !item.issueId && item.type === 'transaction' && (
                    <Link
                      to="/performance/traces/$traceId"
                      params={{ traceId: item.traceId }}
                      onClick={(e) => e.stopPropagation()}
                      className="shrink-0 p-1.5 rounded-md hover:bg-chart-2/15 text-chart-2 transition-colors"
                      aria-label="View trace"
                    >
                      <ExternalLink className="h-3.5 w-3.5" />
                    </Link>
                  )}
                </button>

                {/* Expanded detail — waterfall or breadcrumb details */}
                {isExpanded && (
                  <ExpandedItemPanel
                    item={item}
                    projectId={projectId}
                  />
                )}
              </li>
            )
          })}
        </ul>
      )}
    </div>
  )
})
