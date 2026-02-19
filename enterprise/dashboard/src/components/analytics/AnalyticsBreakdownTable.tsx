// Moneat Enterprise - proprietary module
// Copyright (c) 2026 Moneat. All rights reserved.
// See enterprise/LICENSE for license terms.

import {useState} from 'react'
import {Card, CardContent, CardHeader, CardTitle} from '@/components/ui/card'
import {cn} from '@/lib/utils'
import type {AnalyticsBreakdownItem} from '@/lib/api'
import {ArrowUpDown, ChevronDown, ChevronUp} from 'lucide-react'
import type {LucideIcon} from 'lucide-react'

type SortField = 'visitors' | 'pageviews' | 'bounceRate' | 'avgDuration'
type SortDir = 'asc' | 'desc'

interface AnalyticsBreakdownTableProps {
  title: string
  icon?: LucideIcon
  iconColor?: string
  data?: AnalyticsBreakdownItem[]
  isLoading: boolean
  maxRows?: number
  showBounceRate?: boolean
  showDuration?: boolean
  onRowClick?: (item: AnalyticsBreakdownItem) => void
}

function formatDuration(seconds: number): string {
  if (seconds < 60) return `${Math.round(seconds)}s`
  const minutes = Math.floor(seconds / 60)
  const secs = Math.round(seconds % 60)
  return `${minutes}m ${secs}s`
}

export function AnalyticsBreakdownTable({
  title,
  icon: Icon,
  iconColor = 'text-blue-500',
  data,
  isLoading,
  maxRows = 10,
  showBounceRate = false,
  showDuration = false,
  onRowClick,
}: AnalyticsBreakdownTableProps) {
  const [sortField, setSortField] = useState<SortField>('visitors')
  const [sortDir, setSortDir] = useState<SortDir>('desc')
  const [expanded, setExpanded] = useState(false)
  const safeData = Array.isArray(data) ? data : []

  const toggleSort = (field: SortField) => {
    if (sortField === field) {
      setSortDir(sortDir === 'desc' ? 'asc' : 'desc')
    } else {
      setSortField(field)
      setSortDir('desc')
    }
  }

  const SortIcon = ({field}: {field: SortField}) => {
    if (sortField !== field) return <ArrowUpDown className="h-3 w-3 text-muted-foreground/50" />
    return sortDir === 'desc'
      ? <ChevronDown className="h-3 w-3" />
      : <ChevronUp className="h-3 w-3" />
  }

  const sortedData = [...safeData].sort((a, b) => {
    const aVal = a[sortField] ?? 0
    const bVal = b[sortField] ?? 0
    return sortDir === 'desc' ? bVal - aVal : aVal - bVal
  })

  const displayedData = expanded ? sortedData : sortedData.slice(0, maxRows)
  const maxVisitors = Math.max(...safeData.map(d => d.visitors), 1)

  return (
    <Card>
      <CardHeader className="px-4 py-2.5">
        <CardTitle className="text-xs font-medium flex items-center gap-1.5">
          {Icon && <Icon className={cn('h-3.5 w-3.5', iconColor)} />}
          {title}
        </CardTitle>
      </CardHeader>
      <CardContent className="px-0 pb-1.5">
        {isLoading ? (
          <div className="px-4 space-y-3">
            {[...Array(5)].map((_, i) => (
              <div key={i} className="flex items-center gap-3">
                <div className="h-4 flex-1 bg-muted rounded animate-pulse" style={{animationDelay: `${i * 80}ms`}} />
                <div className="h-4 w-12 bg-muted rounded animate-pulse" style={{animationDelay: `${i * 80}ms`}} />
              </div>
            ))}
          </div>
        ) : safeData.length === 0 ? (
          <div className="px-4 py-6 text-center text-sm text-muted-foreground">
            No data for this period
          </div>
        ) : (
          <>
            {/* Table header */}
            <div className="flex items-center px-4 pb-1.5 text-[11px] font-medium text-muted-foreground uppercase tracking-wider border-b">
              <div className="flex-1">Name</div>
              <button className="w-20 text-right flex items-center justify-end gap-1" onClick={() => toggleSort('visitors')}>
                Visitors <SortIcon field="visitors" />
              </button>
              <button className="w-20 text-right flex items-center justify-end gap-1" onClick={() => toggleSort('pageviews')}>
                Views <SortIcon field="pageviews" />
              </button>
              {showBounceRate && (
                <button className="w-20 text-right flex items-center justify-end gap-1" onClick={() => toggleSort('bounceRate')}>
                  Bounce <SortIcon field="bounceRate" />
                </button>
              )}
              {showDuration && (
                <button className="w-20 text-right flex items-center justify-end gap-1" onClick={() => toggleSort('avgDuration')}>
                  Duration <SortIcon field="avgDuration" />
                </button>
              )}
            </div>

            {/* Table rows */}
            <div className="divide-y">
              {displayedData.map((item, i) => (
                <button
                  key={`${item.name}-${i}`}
                  className={cn(
                    'w-full flex items-center px-4 py-1.5 text-[13px] transition-colors relative group',
                    onRowClick ? 'hover:bg-accent/50 cursor-pointer' : 'cursor-default'
                  )}
                  onClick={() => onRowClick?.(item)}
                  disabled={!onRowClick}
                >
                  {/* Background bar */}
                  <div
                    className="absolute left-0 top-0 bottom-0 bg-blue-500/5 group-hover:bg-blue-500/10 transition-colors"
                    style={{width: `${(item.visitors / maxVisitors) * 100}%`}}
                  />

                  <div className="flex-1 text-left truncate relative z-10">
                    {item.name || '(direct / none)'}
                  </div>
                  <div className="w-20 text-right tabular-nums relative z-10">
                    {item.visitors.toLocaleString()}
                  </div>
                  <div className="w-20 text-right tabular-nums text-muted-foreground relative z-10">
                    {item.pageviews.toLocaleString()}
                  </div>
                  {showBounceRate && (
                    <div className="w-20 text-right tabular-nums text-muted-foreground relative z-10">
                      {item.bounceRate != null ? `${Math.round(item.bounceRate * 100)}%` : '—'}
                    </div>
                  )}
                  {showDuration && (
                    <div className="w-20 text-right tabular-nums text-muted-foreground relative z-10">
                      {item.avgDuration != null ? formatDuration(item.avgDuration) : '—'}
                    </div>
                  )}
                </button>
              ))}
            </div>

            {sortedData.length > maxRows && (
              <button
                onClick={() => setExpanded(!expanded)}
                className="w-full text-center py-2 text-xs text-muted-foreground hover:text-foreground transition-colors"
              >
                {expanded ? 'Show less' : `Show all ${sortedData.length} rows`}
              </button>
            )}
          </>
        )}
      </CardContent>
    </Card>
  )
}
