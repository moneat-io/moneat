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
import {Badge} from '@/components/ui/badge'
import {SpanWaterfall} from '@/components/SpanWaterfall'
import {cn} from '@/lib/utils'
import {
    Activity,
    AlertTriangle,
    Clock,
    DatabaseZap,
    ExternalLink,
    Loader2,
    MousePointerClick,
    Navigation,
    Network,
    Tag,
    Terminal,
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

type FilterValue = 'all' | 'error' | 'http' | 'ui'

type EventCategory = 'navigation' | 'ui' | 'http' | 'exception' | 'console' | 'other'

/** Behavioral category for an event — drives the colored category label, icon, and filter tabs. */
function eventCategory(item: TimelineItem): EventCategory {
  if (item.type === 'error') return 'exception'
  const c = (item.category ?? '').toLowerCase()
  if (/exception|crash|fatal|\banr\b|\berror\b/.test(c)) return 'exception'
  if (/console|logcat|(^|\W)log(\W|$)/.test(c)) return 'console'
  if (/http|fetch|xhr|network|request|response|\bapi\b|graphql/.test(c)) return 'http'
  if (/nav|route|page|screen/.test(c)) return 'navigation'
  if (/\bui\b|click|tap|touch|gesture|input|scroll|press|swipe|key/.test(c)) return 'ui'
  return 'other'
}

const CATEGORY_META: Record<EventCategory, { readonly label: string; readonly color: string; readonly Icon: typeof Activity }> = {
  navigation: { label: 'navigation', color: 'text-success-fg', Icon: Navigation },
  ui: { label: 'ui', color: 'text-info-fg', Icon: MousePointerClick },
  http: { label: 'http', color: 'text-accent', Icon: Network },
  exception: { label: 'exception', color: 'text-danger-fg', Icon: AlertTriangle },
  console: { label: 'console', color: 'text-warning-fg', Icon: Terminal },
  other: { label: 'event', color: 'text-muted-foreground', Icon: Activity },
}

/** HTTP status from the explicit field, else parsed from a "→ 500" style title. */
function statusOf(item: TimelineItem): number | null {
  if (typeof item.statusCode === 'number' && Number.isFinite(item.statusCode)) return item.statusCode
  const m = /→\s*(\d{3})\b/.exec(item.title ?? '')
  return m ? Number(m[1]) : null
}

/** A row reads as an error when it's a thrown error/exception or a failed (>=400) network call. */
function isErrorEvent(item: TimelineItem): boolean {
  if (item.type === 'error') return true
  if (eventCategory(item) === 'exception') return true
  const status = statusOf(item)
  return status != null && status >= 400
}

function statusBadgeVariant(status: number): 'success' | 'warning' | 'danger' {
  if (status >= 400) return 'danger'
  if (status >= 300) return 'warning'
  return 'success'
}

/** Strip a trailing "→ 500" / bare status from the title so we can render the code as a badge instead. */
function titleWithoutStatus(title: string, status: number | null): string {
  if (status == null) return title
  return title.replace(/\s*→?\s*\b\d{3}\b\s*$/, '').trimEnd()
}

/** m:ss clock for the right-aligned time column (matches the player scrubber). */
function formatClock(offsetMs: number): string {
  const s = Math.max(0, Math.floor(offsetMs / 1000))
  return `${Math.floor(s / 60)}:${String(s % 60).padStart(2, '0')}`
}

/** Lowercase + strip non-alphanumerics, for comparing a title against its category label. */
function normalizeLabel(s: string): string {
  return s.toLowerCase().replaceAll(/[^a-z0-9]/g, '')
}

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

const FILTERS: { readonly value: FilterValue; readonly label: string }[] = [
  { value: 'all', label: 'All' },
  { value: 'error', label: 'Errors' },
  { value: 'http', label: 'Network' },
  { value: 'ui', label: 'User' },
]

export function ReplayTimelinePanel({ items, currentOffsetMs, projectId, onSeek }: ReplayTimelinePanelProps) {
  const listRef = useRef<HTMLDivElement>(null)
  const [filter, setFilter] = useState<FilterValue>('all')
  const [expandedId, setExpandedId] = useState<string | null>(null)

  // Mirror the mockup's All / Errors / Network / User filter over behavioral categories.
  const visibleItems = useMemo(() => {
    switch (filter) {
      case 'error':
        return items.filter(isErrorEvent)
      case 'http':
        return items.filter((i) => eventCategory(i) === 'http')
      case 'ui':
        // "User" = user-driven interactions: clicks/taps/inputs plus navigation.
        return items.filter((i) => {
          const c = eventCategory(i)
          return c === 'ui' || c === 'navigation'
        })
      default:
        return items
    }
  }, [items, filter])

  // Active row is tracked against the visible list so the highlight + autoscroll stay in sync.
  const activeIndex = useMemo(
    () => findActiveIndex(visibleItems, currentOffsetMs),
    [visibleItems, currentOffsetMs]
  )

  useEffect(() => {
    if (activeIndex >= 0 && listRef.current) {
      const container = listRef.current
      const el = container.querySelector<HTMLElement>(`[data-timeline-index="${activeIndex}"]`)
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
  }, [activeIndex])

  const handleItemClick = useCallback((item: TimelineItem) => {
    onSeek(item.offsetMs)
    setExpandedId((prev) => (prev === item.id ? null : item.id))
  }, [onSeek])

  return (
    <div className="flex h-full min-h-0 w-full flex-col">
      {/* Header: title + total count + segmented category filter */}
      <div className="flex items-center gap-2 border-b px-2.5 py-2">
        <span className="text-xs font-semibold">Events</span>
        <Badge variant="neutral" className="text-[10px] tabular-nums">{items.length}</Badge>
        <span className="flex-1" />
        <div className="inline-flex items-center gap-0.5 rounded-md border bg-muted/40 p-0.5 text-xs">
          {FILTERS.map((f) => (
            <button
              key={f.value}
              type="button"
              onClick={() => setFilter(f.value)}
              aria-pressed={filter === f.value}
              className={cn(
                'rounded px-2 py-0.5 font-medium transition-colors',
                filter === f.value
                  ? 'bg-background text-foreground shadow-sm'
                  : 'text-muted-foreground hover:text-foreground'
              )}
            >
              {f.label}
            </button>
          ))}
        </div>
      </div>

      <TimelineList
        ref={listRef}
        items={visibleItems}
        activeIndex={activeIndex}
        expandedId={expandedId}
        projectId={projectId}
        onItemClick={handleItemClick}
      />
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
    <div ref={ref} className="min-h-0 flex-1 overflow-y-auto">
      {items.length === 0 ? (
        <div className="flex flex-col items-center justify-center gap-2 p-6 text-center text-sm text-muted-foreground">
          <Activity className="h-6 w-6 opacity-30" />
          No events in this category.
        </div>
      ) : (
        <ul>
          {items.map((item, index) => {
            const isActive = index === activeIndex
            const isExpanded = expandedId === item.id
            const meta = CATEGORY_META[eventCategory(item)]
            const error = isErrorEvent(item)
            const status = statusOf(item)
            const rawTitle = titleWithoutStatus(item.title, status)
            const categoryLabel = item.category && item.category.trim().length > 0 ? item.category : meta.label
            // The title often just echoes the category ("Navigation", "Logcat") — when it does, promote
            // the actual detail to the single primary line rather than repeating the category label.
            const titleRedundant =
              normalizeLabel(rawTitle).length > 0 && normalizeLabel(rawTitle) === normalizeLabel(categoryLabel)
            const primary = titleRedundant && item.description ? item.description : rawTitle
            const secondary = titleRedundant ? undefined : item.description

            let rowClass: string
            if (isActive && error) {
              rowClass = 'border-l-danger-solid bg-danger-bg'
            } else if (isActive) {
              rowClass = 'border-l-primary bg-muted/60'
            } else if (error) {
              rowClass = 'border-l-transparent bg-danger-bg/50 hover:bg-danger-bg'
            } else {
              rowClass = 'border-l-transparent hover:bg-muted/40'
            }

            return (
              <li key={item.id} className="border-b border-border/60 last:border-b-0">
                <button
                  type="button"
                  data-timeline-index={index}
                  onClick={() => onItemClick(item)}
                  style={{ gridTemplateColumns: '132px minmax(0, 1fr) auto' }}
                  className={cn(
                    'grid w-full items-center gap-3 border-l-2 px-3 py-2 text-left text-sm transition-colors',
                    rowClass,
                  )}
                >
                  {/* Category */}
                  <span className={cn('inline-flex items-center gap-1.5 text-xs font-medium', meta.color)}>
                    <meta.Icon className="h-3.5 w-3.5 shrink-0" />
                    <span className="truncate">{categoryLabel}</span>
                  </span>

                  {/* Message: primary detail + status / rage badges, optional secondary line */}
                  <span className="min-w-0">
                    <span className="flex items-center gap-1.5 text-foreground">
                      <span className="truncate" title={item.title}>{primary}</span>
                      {status != null && (
                        <Badge variant={statusBadgeVariant(status)} className="h-4 shrink-0 px-1 text-[10px] tabular-nums">
                          {status}
                        </Badge>
                      )}
                      {item.rage && (
                        <Badge variant="warning" className="h-4 shrink-0 px-1 text-[10px]">rage</Badge>
                      )}
                    </span>
                    {secondary && (
                      <span className="mt-0.5 block truncate text-[11px] text-muted-foreground" title={item.description}>
                        {secondary}
                      </span>
                    )}
                  </span>

                  {/* Offset clock */}
                  <span className="shrink-0 font-mono text-xs tabular-nums text-muted-foreground">
                    {formatClock(item.offsetMs)}
                  </span>
                </button>

                {/* Expanded detail — waterfall or breadcrumb details */}
                {isExpanded && <ExpandedItemPanel item={item} projectId={projectId} />}
              </li>
            )
          })}
        </ul>
      )}
    </div>
  )
})
