import React, {useEffect, useMemo, useRef, useState} from 'react'
import {Link} from '@tanstack/react-router'
import type {ReplayTimelineItem as ReplayTimelineItemType} from '@/lib/api'
import {Tabs, TabsContent, TabsList, TabsTrigger} from '@/components/ui/tabs'
import {cn} from '@/lib/utils'
import {Activity, AlertCircle, ExternalLink, Layers} from 'lucide-react'

export interface ReplayTimelinePanelProps {
  items: ReplayTimelineItemType[]
  currentOffsetMs: number
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

function typeBorderClass(type: ReplayTimelineItemType['type']): string {
  switch (type) {
    case 'error':
      return 'border-l-destructive'
    case 'transaction':
      return 'border-l-blue-500'
    case 'span':
      return 'border-l-teal-500'
    default:
      return 'border-l-muted-foreground'
  }
}

function TypeIcon({ type }: { type: ReplayTimelineItemType['type'] }) {
  switch (type) {
    case 'error':
      return <AlertCircle className="h-4 w-4 shrink-0 text-destructive" />
    case 'transaction':
      return <Layers className="h-4 w-4 shrink-0 text-blue-500" />
    case 'span':
      return <Activity className="h-4 w-4 shrink-0 text-teal-500" />
    default:
      return <Activity className="h-4 w-4 shrink-0 text-muted-foreground" />
  }
}

export function ReplayTimelinePanel({ items, currentOffsetMs, onSeek }: ReplayTimelinePanelProps) {
  const listRef = useRef<HTMLDivElement>(null)
  const [tab, setTab] = useState<FilterValue>('all')
  const activeIndex = useMemo(() => findActiveIndex(items, currentOffsetMs), [items, currentOffsetMs])

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

  return (
    <div className="space-y-3">
      <Tabs value={tab} onValueChange={(v) => setTab(v as FilterValue)} className="w-full">
        <TabsList className="w-full justify-start flex flex-wrap h-auto gap-1 p-1">
          <TabsTrigger value="all" className="text-xs">
            All ({items.length})
          </TabsTrigger>
          <TabsTrigger value="error" className="text-xs">
            Errors ({errorItems.length})
          </TabsTrigger>
          <TabsTrigger value="transaction" className="text-xs">
            Transactions ({transactionItems.length})
          </TabsTrigger>
          <TabsTrigger value="span" className="text-xs">
            Spans ({spanItems.length})
          </TabsTrigger>
        </TabsList>
        <TabsContent value="all" className="mt-2">
          <TimelineList
            ref={listRef}
            items={items}
            activeIndex={activeIndex}
            onSeek={onSeek}
          />
        </TabsContent>
        <TabsContent value="error" className="mt-2">
          <TimelineList
            ref={listRef}
            items={errorItems}
            activeIndex={activeErrorIndex}
            onSeek={onSeek}
          />
        </TabsContent>
        <TabsContent value="transaction" className="mt-2">
          <TimelineList
            ref={listRef}
            items={transactionItems}
            activeIndex={activeTransactionIndex}
            onSeek={onSeek}
          />
        </TabsContent>
        <TabsContent value="span" className="mt-2">
          <TimelineList
            ref={listRef}
            items={spanItems}
            activeIndex={activeSpanIndex}
            onSeek={onSeek}
          />
        </TabsContent>
      </Tabs>
    </div>
  )
}

interface TimelineListProps {
  items: ReplayTimelineItemType[]
  activeIndex: number
  onSeek: (offsetMs: number) => void
}

const TimelineList = React.forwardRef<HTMLDivElement, TimelineListProps>(function TimelineList(
  { items, activeIndex, onSeek },
  ref
) {
  return (
    <div
      ref={ref}
      className="max-h-[280px] overflow-y-auto rounded-md border bg-muted/30"
    >
      {items.length === 0 ? (
        <div className="p-4 text-sm text-muted-foreground text-center">
          No timeline items in this category.
        </div>
      ) : (
        <ul className="divide-y divide-border">
          {items.map((item, index) => {
            const isActive = index === activeIndex
            return (
              <li key={item.id}>
                <button
                  type="button"
                  data-timeline-index={index}
                  onClick={() => onSeek(item.offsetMs)}
                  className={cn(
                    'w-full text-left flex items-center gap-3 px-3 py-2.5 border-l-4 transition-colors',
                    typeBorderClass(item.type),
                    isActive ? 'bg-primary/10' : 'hover:bg-muted/50'
                  )}
                >
                  <TypeIcon type={item.type} />
                  <div className="min-w-0 flex-1">
                    <div className="flex items-center gap-2 flex-wrap">
                      <span className="font-mono text-xs text-muted-foreground shrink-0">
                        {formatOffset(item.offsetMs)}
                      </span>
                      {item.durationMs != null && item.durationMs > 0 && (
                        <span className="text-[10px] px-1.5 py-0.5 rounded bg-muted font-mono">
                          {item.durationMs >= 1000
                            ? `${(item.durationMs / 1000).toFixed(2)}s`
                            : `${Math.round(item.durationMs)}ms`}
                        </span>
                      )}
                    </div>
                    <div className="text-sm font-medium truncate" title={item.title}>
                      {item.title}
                    </div>
                    {item.description && (
                      <div className="text-xs text-muted-foreground truncate" title={item.description}>
                        {item.description}
                      </div>
                    )}
                  </div>
                  {item.issueId && (
                    <Link
                      to="/issues/$issueId"
                      params={{ issueId: item.issueId }}
                      onClick={(e) => e.stopPropagation()}
                      className="shrink-0 p-1 rounded hover:bg-muted text-muted-foreground"
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
                      className="shrink-0 p-1 rounded hover:bg-muted text-muted-foreground"
                      aria-label="View transaction"
                    >
                      <ExternalLink className="h-3.5 w-3.5" />
                    </Link>
                  )}
                </button>
              </li>
            )
          })}
        </ul>
      )}
    </div>
  )
})
