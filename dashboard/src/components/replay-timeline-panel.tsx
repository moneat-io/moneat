import React, {useCallback, useEffect, useMemo, useRef, useState} from 'react'
import {Link} from '@tanstack/react-router'
import type {ReplayTimelineItem as ReplayTimelineItemType} from '@/lib/api'
import {Tabs, TabsContent, TabsList, TabsTrigger} from '@/components/ui/tabs'
import {
  Sheet,
  SheetContent,
  SheetHeader,
  SheetTitle,
  SheetDescription,
} from '@/components/ui/sheet'
import {Badge} from '@/components/ui/badge'
import {cn} from '@/lib/utils'
import {
  Activity,
  AlertCircle,
  ChevronDown,
  ChevronRight,
  Clock,
  ExternalLink,
  Hash,
  Layers,
  Maximize2,
  Tag,
} from 'lucide-react'

export interface ReplayTimelinePanelProps {
  items: ReplayTimelineItemType[]
  currentOffsetMs: number
  durationMs?: number
  onSeek: (offsetMs: number) => void
}

type FilterValue = 'all' | 'error' | 'transaction' | 'span'

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

function formatTimestamp(isoString: string): string {
  if (!isoString) return ''
  const date = new Date(isoString)
  if (isNaN(date.getTime())) return ''
  return date.toLocaleTimeString('en-US', {
    hour: '2-digit',
    minute: '2-digit',
    second: '2-digit',
    hour12: false,
  })
}

function formatDurationLabel(ms: number): string {
  if (ms >= 1000) return `${(ms / 1000).toFixed(2)}s`
  return `${Math.round(ms)}ms`
}

function findActiveIndex(items: ReplayTimelineItemType[], currentOffsetMs: number): number {
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

function typeColorClasses(type: ReplayTimelineItemType['type']) {
  switch (type) {
    case 'error':
      return {
        border: 'border-l-red-500',
        bg: 'bg-red-500/8 dark:bg-red-500/10',
        bgActive: 'bg-red-500/15 dark:bg-red-500/20',
        text: 'text-red-600 dark:text-red-400',
        badge: 'bg-red-100 text-red-700 dark:bg-red-900/40 dark:text-red-300',
        dot: 'bg-red-500',
        bar: 'bg-red-500/70',
        barBg: 'bg-red-100 dark:bg-red-900/30',
      }
    case 'transaction':
      return {
        border: 'border-l-blue-500',
        bg: 'bg-blue-500/8 dark:bg-blue-500/10',
        bgActive: 'bg-blue-500/15 dark:bg-blue-500/20',
        text: 'text-blue-600 dark:text-blue-400',
        badge: 'bg-blue-100 text-blue-700 dark:bg-blue-900/40 dark:text-blue-300',
        dot: 'bg-blue-500',
        bar: 'bg-blue-500/70',
        barBg: 'bg-blue-100 dark:bg-blue-900/30',
      }
    case 'span':
      return {
        border: 'border-l-emerald-500',
        bg: 'bg-emerald-500/8 dark:bg-emerald-500/10',
        bgActive: 'bg-emerald-500/15 dark:bg-emerald-500/20',
        text: 'text-emerald-600 dark:text-emerald-400',
        badge: 'bg-emerald-100 text-emerald-700 dark:bg-emerald-900/40 dark:text-emerald-300',
        dot: 'bg-emerald-500',
        bar: 'bg-emerald-500/70',
        barBg: 'bg-emerald-100 dark:bg-emerald-900/30',
      }
    default:
      return {
        border: 'border-l-slate-400',
        bg: 'bg-slate-500/8 dark:bg-slate-500/10',
        bgActive: 'bg-slate-500/15 dark:bg-slate-500/20',
        text: 'text-slate-500 dark:text-slate-400',
        badge: 'bg-slate-100 text-slate-700 dark:bg-slate-800 dark:text-slate-300',
        dot: 'bg-slate-400',
        bar: 'bg-slate-400/70',
        barBg: 'bg-slate-100 dark:bg-slate-800',
      }
  }
}

function TypeIcon({ type, className }: { type: ReplayTimelineItemType['type']; className?: string }) {
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

function TypeBadge({ type }: { type: ReplayTimelineItemType['type'] }) {
  const colors = typeColorClasses(type)
  const label = type.charAt(0).toUpperCase() + type.slice(1)
  return (
    <span className={cn('inline-flex items-center gap-1 rounded-full px-2 py-0.5 text-[10px] font-medium', colors.badge)}>
      <span className={cn('h-1.5 w-1.5 rounded-full', colors.dot)} />
      {label}
    </span>
  )
}

/* ── Waterfall bar component ── */

function WaterfallBar({
  item,
  sessionDurationMs,
}: {
  item: ReplayTimelineItemType
  sessionDurationMs: number
}) {
  const colors = typeColorClasses(item.type)
  const effectiveDuration = sessionDurationMs > 0 ? sessionDurationMs : 1

  const startPct = Math.max(0, Math.min((item.offsetMs / effectiveDuration) * 100, 100))
  const spanDuration = item.durationMs ?? 0
  const widthPct = Math.max(0.5, Math.min((spanDuration / effectiveDuration) * 100, 100 - startPct))

  return (
    <div className="w-full space-y-1.5">
      <div className="flex items-center justify-between text-[10px] text-muted-foreground">
        <span>{formatOffset(item.offsetMs)}</span>
        {spanDuration > 0 && (
          <span className="font-mono font-medium">{formatDurationLabel(spanDuration)}</span>
        )}
        <span>{formatOffset(Math.min(item.offsetMs + spanDuration, sessionDurationMs))}</span>
      </div>
      <div className={cn('relative h-3 w-full rounded-full overflow-hidden', colors.barBg)}>
        <div
          className={cn('absolute top-0 h-full rounded-full transition-all', colors.bar)}
          style={{
            left: `${startPct}%`,
            width: `${widthPct}%`,
            minWidth: '4px',
          }}
        />
      </div>
      {/* Tick marks */}
      <div className="relative h-2 w-full">
        {[0, 25, 50, 75, 100].map((pct) => (
          <div key={pct} className="absolute top-0 h-1.5 w-px bg-border" style={{ left: `${pct}%` }} />
        ))}
      </div>
    </div>
  )
}

/* ── Expanded item detail (inline) ── */

function ExpandedItemDetail({
  item,
  sessionDurationMs,
  onViewFull,
}: {
  item: ReplayTimelineItemType
  sessionDurationMs: number
  onViewFull: () => void
}) {
  const colors = typeColorClasses(item.type)

  return (
    <div className={cn('px-4 py-3 space-y-3 border-t', colors.bg)}>
      {/* Waterfall */}
      <div>
        <div className="text-[10px] font-medium text-muted-foreground uppercase tracking-wider mb-2">
          Timeline Position
        </div>
        <WaterfallBar item={item} sessionDurationMs={sessionDurationMs} />
      </div>

      {/* Quick details grid */}
      <div className="grid grid-cols-2 gap-2 text-xs">
        {item.timestamp && (
          <div className="flex items-center gap-1.5">
            <Clock className="h-3 w-3 text-muted-foreground" />
            <span className="text-muted-foreground">Time:</span>
            <span className="font-mono">{formatTimestamp(item.timestamp)}</span>
          </div>
        )}
        {item.durationMs != null && item.durationMs > 0 && (
          <div className="flex items-center gap-1.5">
            <Activity className="h-3 w-3 text-muted-foreground" />
            <span className="text-muted-foreground">Duration:</span>
            <span className={cn('font-mono font-medium', colors.text)}>
              {formatDurationLabel(item.durationMs)}
            </span>
          </div>
        )}
        {item.category && (
          <div className="flex items-center gap-1.5">
            <Tag className="h-3 w-3 text-muted-foreground" />
            <span className="text-muted-foreground">Category:</span>
            <span className="font-mono truncate">{item.category}</span>
          </div>
        )}
        {item.traceId && (
          <div className="flex items-center gap-1.5">
            <Hash className="h-3 w-3 text-muted-foreground" />
            <span className="text-muted-foreground">Trace:</span>
            <span className="font-mono truncate text-[11px]">{item.traceId.slice(0, 12)}...</span>
          </div>
        )}
      </div>

      {item.description && (
        <div className="text-xs text-muted-foreground bg-muted/50 rounded-md px-3 py-2 font-mono break-all">
          {item.description}
        </div>
      )}

      {/* Action buttons */}
      <div className="flex items-center gap-2">
        <button
          type="button"
          onClick={onViewFull}
          className="inline-flex items-center gap-1.5 text-xs font-medium text-foreground bg-background border rounded-md px-3 py-1.5 hover:bg-accent transition-colors"
        >
          <Maximize2 className="h-3 w-3" />
          View Full Details
        </button>
        {item.issueId && (
          <Link
            to="/issues/$issueId"
            params={{ issueId: item.issueId }}
            className="inline-flex items-center gap-1.5 text-xs font-medium text-red-600 dark:text-red-400 bg-background border border-red-200 dark:border-red-800 rounded-md px-3 py-1.5 hover:bg-red-50 dark:hover:bg-red-950 transition-colors"
          >
            <ExternalLink className="h-3 w-3" />
            View Issue
          </Link>
        )}
        {item.eventId && !item.issueId && item.type === 'transaction' && (
          <Link
            to="/performance/$transactionId"
            params={{ transactionId: item.eventId }}
            className="inline-flex items-center gap-1.5 text-xs font-medium text-blue-600 dark:text-blue-400 bg-background border border-blue-200 dark:border-blue-800 rounded-md px-3 py-1.5 hover:bg-blue-50 dark:hover:bg-blue-950 transition-colors"
          >
            <ExternalLink className="h-3 w-3" />
            View Transaction
          </Link>
        )}
      </div>
    </div>
  )
}

/* ── Full span detail sheet ── */

function SpanDetailSheet({
  item,
  sessionDurationMs,
  open,
  onOpenChange,
}: {
  item: ReplayTimelineItemType | null
  sessionDurationMs: number
  open: boolean
  onOpenChange: (open: boolean) => void
}) {
  if (!item) return null
  const colors = typeColorClasses(item.type)

  return (
    <Sheet open={open} onOpenChange={onOpenChange}>
      <SheetContent side="right" className="w-full sm:max-w-lg overflow-y-auto">
        <SheetHeader className="pb-4">
          <div className="flex items-center gap-2">
            <TypeIcon type={item.type} className="h-5 w-5" />
            <SheetTitle className="text-base">{item.title}</SheetTitle>
          </div>
          <SheetDescription>
            <TypeBadge type={item.type} />
          </SheetDescription>
        </SheetHeader>

        <div className="space-y-6 pb-6">
          {/* Waterfall */}
          <section>
            <h3 className="text-xs font-semibold text-muted-foreground uppercase tracking-wider mb-3">
              Waterfall
            </h3>
            <div className="border rounded-lg p-4 bg-muted/20">
              <WaterfallBar item={item} sessionDurationMs={sessionDurationMs} />
            </div>
          </section>

          {/* Properties */}
          <section>
            <h3 className="text-xs font-semibold text-muted-foreground uppercase tracking-wider mb-3">
              Properties
            </h3>
            <div className="border rounded-lg divide-y">
              <DetailRow label="Type" value={
                <TypeBadge type={item.type} />
              } />
              <DetailRow label="Title" value={item.title} />
              {item.description && (
                <DetailRow label="Description" value={item.description} mono />
              )}
              <DetailRow label="Offset" value={formatOffset(item.offsetMs)} mono />
              {item.durationMs != null && item.durationMs > 0 && (
                <DetailRow label="Duration" value={
                  <span className={cn('font-mono font-semibold', colors.text)}>
                    {formatDurationLabel(item.durationMs)}
                  </span>
                } />
              )}
              {item.timestamp && (
                <DetailRow label="Timestamp" value={
                  <span className="font-mono text-xs">{item.timestamp}</span>
                } />
              )}
              {item.category && (
                <DetailRow label="Category" value={
                  <Badge variant="outline" className="text-xs font-mono">{item.category}</Badge>
                } />
              )}
              {item.eventId && (
                <DetailRow label="Event ID" value={item.eventId} mono copyable />
              )}
              {item.traceId && (
                <DetailRow label="Trace ID" value={item.traceId} mono copyable />
              )}
              {item.issueId && (
                <DetailRow label="Issue ID" value={
                  <Link
                    to="/issues/$issueId"
                    params={{ issueId: item.issueId }}
                    className="inline-flex items-center gap-1 text-red-600 dark:text-red-400 hover:underline font-mono text-xs"
                  >
                    {item.issueId}
                    <ExternalLink className="h-3 w-3" />
                  </Link>
                } />
              )}
            </div>
          </section>

          {/* Raw JSON */}
          <section>
            <h3 className="text-xs font-semibold text-muted-foreground uppercase tracking-wider mb-3">
              Raw Data
            </h3>
            <pre className="border rounded-lg p-4 bg-muted/30 text-xs font-mono overflow-x-auto whitespace-pre-wrap break-all text-muted-foreground">
              {JSON.stringify(item, null, 2)}
            </pre>
          </section>
        </div>
      </SheetContent>
    </Sheet>
  )
}

function DetailRow({
  label,
  value,
  mono,
  copyable,
}: {
  label: string
  value: React.ReactNode
  mono?: boolean
  copyable?: boolean
}) {
  const handleCopy = useCallback(() => {
    if (typeof value === 'string') {
      navigator.clipboard.writeText(value)
    }
  }, [value])

  return (
    <div className="flex items-start justify-between gap-4 px-4 py-2.5">
      <span className="text-xs text-muted-foreground shrink-0 pt-0.5">{label}</span>
      <div className="flex items-center gap-1.5 min-w-0 justify-end">
        <span className={cn(
          'text-sm text-right break-all min-w-0',
          mono && 'font-mono text-xs',
        )}>
          {value}
        </span>
        {copyable && typeof value === 'string' && (
          <button
            type="button"
            onClick={handleCopy}
            className="shrink-0 p-0.5 rounded hover:bg-muted text-muted-foreground"
            title="Copy to clipboard"
          >
            <Hash className="h-3 w-3" />
          </button>
        )}
      </div>
    </div>
  )
}

/* ── Main component ── */

export function ReplayTimelinePanel({ items, currentOffsetMs, durationMs, onSeek }: ReplayTimelinePanelProps) {
  const listRef = useRef<HTMLDivElement>(null)
  const [tab, setTab] = useState<FilterValue>('all')
  const [expandedId, setExpandedId] = useState<string | null>(null)
  const [sheetItem, setSheetItem] = useState<ReplayTimelineItemType | null>(null)
  const activeIndex = useMemo(() => findActiveIndex(items, currentOffsetMs), [items, currentOffsetMs])

  // Compute session duration from items if not provided
  const sessionDurationMs = useMemo(() => {
    if (durationMs && durationMs > 0) return durationMs
    if (items.length === 0) return 0
    let maxEnd = 0
    for (const it of items) {
      const end = it.offsetMs + (it.durationMs ?? 0)
      if (end > maxEnd) maxEnd = end
    }
    return maxEnd || items[items.length - 1].offsetMs
  }, [durationMs, items])

  const activeItem = activeIndex >= 0 ? items[activeIndex] : null
  const errorItems = useMemo(() => items.filter((i) => i.type === 'error'), [items])
  const transactionItems = useMemo(() => items.filter((i) => i.type === 'transaction'), [items])
  const spanItems = useMemo(() => items.filter((i) => i.type === 'span'), [items])
  const activeErrorIndex = activeItem?.type === 'error' ? errorItems.findIndex((it) => it.id === activeItem.id) : -1
  const activeTransactionIndex = activeItem?.type === 'transaction' ? transactionItems.findIndex((it) => it.id === activeItem.id) : -1
  const activeSpanIndex = activeItem?.type === 'span' ? spanItems.findIndex((it) => it.id === activeItem.id) : -1
  const scrollIndex = tab === 'all' ? activeIndex : tab === 'error' ? activeErrorIndex : tab === 'transaction' ? activeTransactionIndex : activeSpanIndex

  useEffect(() => {
    if (scrollIndex < 0 || !listRef.current) return
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
  }, [scrollIndex])

  const handleItemClick = useCallback((item: ReplayTimelineItemType) => {
    onSeek(item.offsetMs)
    setExpandedId((prev) => (prev === item.id ? null : item.id))
  }, [onSeek])

  return (
    <>
      <div className="h-full min-h-0 rounded-lg border bg-card shadow-sm">
        <Tabs
          value={tab}
          onValueChange={(v) => setTab(v as FilterValue)}
          className="w-full h-full min-h-0 flex flex-col"
        >
          {/* Colored tab bar */}
          <div className="px-2 pt-2">
            <TabsList className="w-full grid grid-cols-4 h-9 gap-1 p-1 bg-muted/50">
              <TabsTrigger value="all" className="text-xs data-[state=active]:bg-background data-[state=active]:shadow-sm">
                All
                <span className="ml-1 text-[10px] font-mono text-muted-foreground">{items.length}</span>
              </TabsTrigger>
              <TabsTrigger value="error" className="text-xs data-[state=active]:bg-background data-[state=active]:shadow-sm data-[state=active]:text-red-600 dark:data-[state=active]:text-red-400">
                <span className="flex items-center gap-1">
                  <span className="h-1.5 w-1.5 rounded-full bg-red-500" />
                  Errors
                </span>
                <span className="ml-1 text-[10px] font-mono">{errorItems.length}</span>
              </TabsTrigger>
              <TabsTrigger value="transaction" className="text-xs data-[state=active]:bg-background data-[state=active]:shadow-sm data-[state=active]:text-blue-600 dark:data-[state=active]:text-blue-400">
                <span className="flex items-center gap-1">
                  <span className="h-1.5 w-1.5 rounded-full bg-blue-500" />
                  Txns
                </span>
                <span className="ml-1 text-[10px] font-mono">{transactionItems.length}</span>
              </TabsTrigger>
              <TabsTrigger value="span" className="text-xs data-[state=active]:bg-background data-[state=active]:shadow-sm data-[state=active]:text-emerald-600 dark:data-[state=active]:text-emerald-400">
                <span className="flex items-center gap-1">
                  <span className="h-1.5 w-1.5 rounded-full bg-emerald-500" />
                  Spans
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
              sessionDurationMs={sessionDurationMs}
              onItemClick={handleItemClick}
              onViewFull={setSheetItem}
            />
          </TabsContent>
          <TabsContent value="error" className="mt-0 flex-1 min-h-0 px-2 pb-2">
            <TimelineList
              ref={listRef}
              items={errorItems}
              activeIndex={activeErrorIndex}
              expandedId={expandedId}
              sessionDurationMs={sessionDurationMs}
              onItemClick={handleItemClick}
              onViewFull={setSheetItem}
            />
          </TabsContent>
          <TabsContent value="transaction" className="mt-0 flex-1 min-h-0 px-2 pb-2">
            <TimelineList
              ref={listRef}
              items={transactionItems}
              activeIndex={activeTransactionIndex}
              expandedId={expandedId}
              sessionDurationMs={sessionDurationMs}
              onItemClick={handleItemClick}
              onViewFull={setSheetItem}
            />
          </TabsContent>
          <TabsContent value="span" className="mt-0 flex-1 min-h-0 px-2 pb-2">
            <TimelineList
              ref={listRef}
              items={spanItems}
              activeIndex={activeSpanIndex}
              expandedId={expandedId}
              sessionDurationMs={sessionDurationMs}
              onItemClick={handleItemClick}
              onViewFull={setSheetItem}
            />
          </TabsContent>
        </Tabs>
      </div>

      {/* Full detail sheet */}
      <SpanDetailSheet
        item={sheetItem}
        sessionDurationMs={sessionDurationMs}
        open={!!sheetItem}
        onOpenChange={(open) => { if (!open) setSheetItem(null) }}
      />
    </>
  )
}

/* ── Timeline list ── */

interface TimelineListProps {
  items: ReplayTimelineItemType[]
  activeIndex: number
  expandedId: string | null
  sessionDurationMs: number
  onItemClick: (item: ReplayTimelineItemType) => void
  onViewFull: (item: ReplayTimelineItemType) => void
}

const TimelineList = React.forwardRef<HTMLDivElement, TimelineListProps>(function TimelineList(
  { items, activeIndex, expandedId, sessionDurationMs, onItemClick, onViewFull },
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

            return (
              <li key={item.id}>
                <button
                  type="button"
                  data-timeline-index={index}
                  onClick={() => onItemClick(item)}
                  className={cn(
                    'w-full text-left flex items-center gap-3 px-3 py-2.5 border-l-[3px] transition-all duration-150',
                    colors.border,
                    isActive ? colors.bgActive : isExpanded ? colors.bg : 'hover:bg-muted/40',
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

                  {/* Quick link icon */}
                  {item.issueId && (
                    <Link
                      to="/issues/$issueId"
                      params={{ issueId: item.issueId }}
                      onClick={(e) => e.stopPropagation()}
                      className="shrink-0 p-1.5 rounded-md hover:bg-red-100 dark:hover:bg-red-900/30 text-red-500 transition-colors"
                      aria-label="View issue"
                    >
                      <ExternalLink className="h-3.5 w-3.5" />
                    </Link>
                  )}
                  {item.eventId && !item.issueId && item.type === 'transaction' && (
                    <Link
                      to="/performance/$transactionId"
                      params={{ transactionId: item.eventId }}
                      onClick={(e) => e.stopPropagation()}
                      className="shrink-0 p-1.5 rounded-md hover:bg-blue-100 dark:hover:bg-blue-900/30 text-blue-500 transition-colors"
                      aria-label="View transaction"
                    >
                      <ExternalLink className="h-3.5 w-3.5" />
                    </Link>
                  )}
                </button>

                {/* Expanded detail panel */}
                {isExpanded && (
                  <ExpandedItemDetail
                    item={item}
                    sessionDurationMs={sessionDurationMs}
                    onViewFull={() => onViewFull(item)}
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
