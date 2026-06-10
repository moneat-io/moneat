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

import {api, type LogEntry} from '@/lib/api'
import {stripAnsi} from '@/lib/ansi'
import {cn} from '@/lib/utils'
import {getNow} from '@/lib/demo'
import {useTimezone} from '@/hooks/useTimezone'
import {formatDateTimeWithMs} from '@/lib/date-format'
import {useQuery} from '@tanstack/react-query'
import {
  Check,
  ChevronDown,
  ChevronUp,
  GitBranch,
  Layers,
  Link2,
  List,
  Maximize2,
  Minimize2,
  Minus,
  Plus,
  Server,
  Share2,
  SlidersHorizontal,
  X,
  Zap,
} from 'lucide-react'
import {useCallback, useEffect, useMemo, useState} from 'react'
import {AttributesPanel} from './AttributesPanel'
import {ContentPanel} from './ContentPanel'
import {ContextPanel} from './ContextPanel'
import {CopyButton} from './CopyButton'
import {countLogAttributes, groupLogAttributes} from './logContextHelpers'
import {levelBadge, normalizeLevel} from './logLevelStyles'
import {PatternsPanel} from './PatternsPanel'
import {TracePanel} from './TracePanel'

type TabId = 'content' | 'context' | 'trace' | 'attributes' | 'patterns'

export interface LogContextViewerProps {
  log: LogEntry
  /** Ordered, currently-loaded result list (newest first). */
  logs: LogEntry[]
  index: number
  /** Total matching results, for the "Event N of M" label. */
  total: number
  onNavigate: (delta: number) => void
  onClose: () => void
  onAddFacetFilter?: (key: string, value: string, exclude?: boolean) => void
  /** Current explorer time window, used to scope pattern rollups. */
  timeRange?: {from?: string; to?: string}
  /** Open the create-metric flow seeded from this log's pattern. */
  onCreateMetric?: () => void
  /** Open the create-monitor flow seeded from this log's pattern. */
  onCreateMonitor?: () => void
  expanded?: boolean
  onToggleExpand?: () => void
  className?: string
}

function relativeFromNow(iso: string): string {
  const t = new Date(iso).getTime()
  if (Number.isNaN(t)) return ''
  const diff = getNow() - t
  if (diff < 0 || diff < 1000) return 'just now'
  if (diff < 60_000) return `${Math.floor(diff / 1000)}s ago`
  if (diff < 3_600_000) return `${Math.floor(diff / 60_000)}m ago`
  if (diff < 86_400_000) return `${Math.floor(diff / 3_600_000)}h ago`
  return `${Math.floor(diff / 86_400_000)}d ago`
}

function FacetChip({
  label,
  value,
  accent,
  filterKey,
  onAddFacetFilter,
  excludable,
}: {
  label: string
  value: string
  accent?: boolean
  filterKey?: string
  onAddFacetFilter?: (key: string, value: string, exclude?: boolean) => void
  excludable?: boolean
}) {
  return (
    <span
      className={cn(
        'group/chip inline-flex h-6 max-w-full items-center gap-2 rounded-sm border py-0 pl-2 pr-1 text-xs',
        accent ? 'border-primary/30 bg-primary/10' : 'border-border bg-muted/60'
      )}
    >
      <span
        className={cn(
          'text-[10px] font-semibold uppercase tracking-wide',
          accent ? 'text-primary/80' : 'text-muted-foreground/70'
        )}
      >
        {label}
      </span>
      <span className={cn('truncate font-mono text-[11px]', accent ? 'text-primary' : 'text-foreground')}>{value}</span>
      {filterKey && onAddFacetFilter && (
        <span className="flex items-center gap-px opacity-0 transition-opacity group-hover/chip:opacity-100">
          <button
            type="button"
            title="Filter to"
            onClick={() => onAddFacetFilter(filterKey, value, false)}
            className="grid h-[18px] w-[18px] place-items-center rounded-sm text-muted-foreground/70 hover:bg-accent hover:text-foreground"
          >
            <Plus className="h-2.5 w-2.5" />
          </button>
          {excludable && (
            <button
              type="button"
              title="Exclude"
              onClick={() => onAddFacetFilter(filterKey, value, true)}
              className="grid h-[18px] w-[18px] place-items-center rounded-sm text-muted-foreground/70 hover:bg-accent hover:text-foreground"
            >
              <Minus className="h-2.5 w-2.5" />
            </button>
          )}
        </span>
      )}
    </span>
  )
}

function ConnectedNode({
  icon,
  title,
  subtitle,
  status,
  onClick,
}: {
  icon: React.ReactNode
  title: string
  subtitle: string
  status?: 'ok' | 'err' | 'warn'
  onClick: () => void
}) {
  return (
    <button
      type="button"
      onClick={onClick}
      className="flex min-w-0 flex-1 items-center gap-2.5 rounded-md border border-border bg-muted/50 p-2 text-left transition-colors hover:border-primary/30 hover:bg-accent"
    >
      <span className="grid h-7 w-7 shrink-0 place-items-center rounded-md bg-primary/10 text-primary">{icon}</span>
      <span className="min-w-0">
        <span className="flex items-center gap-1.5 text-xs font-semibold text-foreground">
          {title}
          {status && (
            <span
              className={cn(
                'h-[7px] w-[7px] rounded-full',
                status === 'ok' ? 'bg-emerald-500' : status === 'err' ? 'bg-red-500' : 'bg-amber-500'
              )}
            />
          )}
        </span>
        <span className="block truncate text-[11px] text-muted-foreground">{subtitle}</span>
      </span>
    </button>
  )
}

export function LogContextViewer({
  log,
  logs,
  index,
  total,
  onNavigate,
  onClose,
  onAddFacetFilter,
  timeRange,
  onCreateMetric,
  onCreateMonitor,
  expanded,
  onToggleExpand,
  className,
}: LogContextViewerProps) {
  const {timezone} = useTimezone()
  const [activeTab, setActiveTab] = useState<TabId>('content')
  const [linkCopied, setLinkCopied] = useState(false)

  const level = normalizeLevel(log.level)
  const message = stripAnsi(log.message || log.body || '')
  const attrCount = useMemo(() => countLogAttributes(groupLogAttributes(log)), [log])

  // Trace fetched once at the shell so the cluster, tab badge and panel agree.
  const traceQuery = useQuery({
    queryKey: ['apm-trace', log.traceId],
    queryFn: () => api.getApmTraceDetail(log.traceId),
    enabled: !!log.traceId,
    retry: false,
    staleTime: 60_000,
  })
  const spans = traceQuery.data?.spans ?? []
  const traceErrorCount = spans.filter((s) => s.error > 0 || (s.statusCode != null && s.statusCode >= 500)).length
  const traceServiceCount = new Set(spans.map((s) => s.service).filter(Boolean)).size
  const traceHref = log.traceId ? `/performance/traces/${log.traceId}` : undefined

  const canPrev = index > 0
  const canNext = index < logs.length - 1

  // Keyboard: J/K to move between events, Esc to close. Ignore while typing.
  useEffect(() => {
    const handler = (e: KeyboardEvent) => {
      const el = e.target as HTMLElement | null
      const tag = el?.tagName
      if (tag === 'INPUT' || tag === 'TEXTAREA' || tag === 'SELECT' || el?.isContentEditable) return
      if (e.metaKey || e.ctrlKey || e.altKey) return
      if (e.key === 'Escape') {
        onClose()
      } else if (e.key === 'j' || e.key === 'J') {
        if (canNext) {
          e.preventDefault()
          onNavigate(1)
        }
      } else if (e.key === 'k' || e.key === 'K') {
        if (canPrev) {
          e.preventDefault()
          onNavigate(-1)
        }
      }
    }
    globalThis.window?.addEventListener('keydown', handler)
    return () => globalThis.window?.removeEventListener('keydown', handler)
  }, [canPrev, canNext, onNavigate, onClose])

  const handleCopyLink = useCallback(async () => {
    const url = globalThis.window?.location?.href
    const clipboard = globalThis.navigator?.clipboard
    if (!url || !clipboard) return
    try {
      await clipboard.writeText(url)
      setLinkCopied(true)
      setTimeout(() => setLinkCopied(false), 1100)
    } catch {
      // no-op in insecure contexts
    }
  }, [])

  const handleShare = useCallback(async () => {
    const url = globalThis.window?.location?.href
    const nav = globalThis.navigator
    if (url && typeof nav?.share === 'function') {
      try {
        await nav.share({title: 'Moneat log', text: message, url})
        return
      } catch {
        // user cancelled or unsupported — fall through to copy
      }
    }
    void handleCopyLink()
  }, [message, handleCopyLink])

  const pod = log.resourceAttributes?.['k8s.pod.name'] || log.tags?.['k8s.pod.name']
  const region = log.resourceAttributes?.['cloud.region']

  const tabs: {id: TabId; label: string; icon: React.ReactNode; count?: number}[] = [
    {id: 'content', label: 'Content', icon: <List className="h-3.5 w-3.5" />},
    {id: 'context', label: 'Context', icon: <Layers className="h-3.5 w-3.5" />},
    {id: 'trace', label: 'Trace', icon: <GitBranch className="h-3.5 w-3.5" />, count: log.traceId ? spans.length : undefined},
    {id: 'attributes', label: 'Attributes', icon: <SlidersHorizontal className="h-3.5 w-3.5" />, count: attrCount},
    {id: 'patterns', label: 'Patterns', icon: <Zap className="h-3.5 w-3.5" />},
  ]

  return (
    <section
      aria-label="Log context viewer"
      className={cn('flex min-h-0 flex-col border-l border-border bg-card shadow-xl', className)}
    >
      {/* ---- header ---- */}
      <div className="shrink-0 border-b border-border">
        <div className="flex items-center gap-2 px-3 pt-2.5">
          <div className="flex items-center overflow-hidden rounded-md border border-border">
            <button
              type="button"
              title="Previous event (K)"
              disabled={!canPrev}
              onClick={() => onNavigate(-1)}
              className="grid h-6 w-[26px] place-items-center text-muted-foreground hover:bg-accent hover:text-foreground disabled:opacity-30"
            >
              <ChevronUp className="h-3.5 w-3.5" />
            </button>
            <button
              type="button"
              title="Next event (J)"
              disabled={!canNext}
              onClick={() => onNavigate(1)}
              className="grid h-6 w-[26px] place-items-center border-l border-border text-muted-foreground hover:bg-accent hover:text-foreground disabled:opacity-30"
            >
              <ChevronDown className="h-3.5 w-3.5" />
            </button>
          </div>
          <span className="text-xs text-muted-foreground">
            <b className="font-semibold text-foreground">Event {index + 1}</b> of {total.toLocaleString('en-US')}
          </span>
          <kbd className="rounded-sm border border-border bg-muted px-1.5 font-mono text-[10px] text-muted-foreground">J</kbd>
          <kbd className="rounded-sm border border-border bg-muted px-1.5 font-mono text-[10px] text-muted-foreground">K</kbd>
          <span className="text-[11px] text-muted-foreground/60">to move</span>

          <div className="ml-auto flex items-center gap-1">
            <button
              type="button"
              onClick={handleCopyLink}
              className="inline-flex h-[26px] items-center gap-1.5 rounded-md border border-border px-2 text-xs font-medium text-muted-foreground hover:bg-accent hover:text-foreground"
            >
              {linkCopied ? <Check className="h-3 w-3 text-emerald-500" /> : <Link2 className="h-3 w-3" />} Copy link
            </button>
            <button
              type="button"
              title="Share"
              onClick={handleShare}
              className="grid h-[26px] w-[26px] place-items-center rounded-md border border-border text-muted-foreground hover:bg-accent hover:text-foreground"
            >
              <Share2 className="h-3 w-3" />
            </button>
            {onToggleExpand && (
              <button
                type="button"
                title={expanded ? 'Collapse' : 'Expand'}
                onClick={onToggleExpand}
                className="grid h-[26px] w-[26px] place-items-center rounded-md border border-border text-muted-foreground hover:bg-accent hover:text-foreground"
              >
                {expanded ? <Minimize2 className="h-3 w-3" /> : <Maximize2 className="h-3 w-3" />}
              </button>
            )}
            <button
              type="button"
              title="Close (Esc)"
              onClick={onClose}
              className="grid h-[26px] w-[26px] place-items-center rounded-md border border-border text-muted-foreground hover:bg-accent hover:text-foreground"
            >
              <X className="h-3.5 w-3.5" />
            </button>
          </div>
        </div>

        {/* headline */}
        <div className="flex items-start gap-2.5 px-3 pb-2.5 pt-2.5">
          <span
            className={cn(
              'inline-flex h-6 shrink-0 items-center gap-1.5 rounded-sm border px-2.5 text-[11px] font-bold uppercase tracking-wide',
              levelBadge(level)
            )}
          >
            <span className="h-[7px] w-[7px] rounded-full bg-current" />
            {level}
          </span>
          <div className="min-w-0 flex-1 break-words font-mono text-sm leading-snug text-foreground">{message}</div>
          <CopyButton value={message} label="message" className="h-6 w-6" />
        </div>

        {/* facet chips */}
        <div className="flex flex-wrap gap-1.5 px-3 pb-2.5">
          <FacetChip label="time" value={`${formatDateTimeWithMs(new Date(log.timestamp), timezone)} · ${relativeFromNow(log.timestamp)}`} />
          {log.service && (
            <FacetChip label="service" value={log.service} accent filterKey="service" onAddFacetFilter={onAddFacetFilter} excludable />
          )}
          {log.environment && (
            <FacetChip label="env" value={log.environment} filterKey="environment" onAddFacetFilter={onAddFacetFilter} />
          )}
          {log.host && <FacetChip label="host" value={log.host} filterKey="host" onAddFacetFilter={onAddFacetFilter} />}
          {log.source && <FacetChip label="source" value={log.source} />}
          {pod && <FacetChip label="pod" value={pod} filterKey="k8s.pod.name" onAddFacetFilter={onAddFacetFilter} />}
          {!pod && log.containerName && (
            <FacetChip label="container" value={log.containerName} filterKey="container_name" onAddFacetFilter={onAddFacetFilter} />
          )}
        </div>

        {/* connected telemetry cluster */}
        <div className="flex gap-2 px-3 pb-2.5">
          {log.traceId && (
            <ConnectedNode
              icon={<GitBranch className="h-3.5 w-3.5" />}
              title="Trace"
              status={traceErrorCount > 0 ? 'err' : 'ok'}
              subtitle={
                traceQuery.isLoading
                  ? 'loading…'
                  : spans.length > 0
                    ? `${spans.length} spans · ${traceServiceCount} services · ${traceErrorCount} error${traceErrorCount === 1 ? '' : 's'}`
                    : 'view trace'
              }
              onClick={() => setActiveTab('trace')}
            />
          )}
          {log.host && (
            <ConnectedNode
              icon={<Server className="h-3.5 w-3.5" />}
              title="Host"
              subtitle={region ? `${log.host} · ${region}` : log.host}
              onClick={() => setActiveTab('attributes')}
            />
          )}
          <ConnectedNode
            icon={<Zap className="h-3.5 w-3.5" />}
            title="Pattern"
            subtitle="similar logs"
            onClick={() => setActiveTab('patterns')}
          />
        </div>

        {/* tabs */}
        <div className="flex items-center gap-0.5 px-2.5">
          {tabs.map((t) => {
            const active = activeTab === t.id
            return (
              <button
                key={t.id}
                type="button"
                onClick={() => setActiveTab(t.id)}
                className={cn(
                  'relative inline-flex h-[38px] items-center gap-1.5 px-3 text-sm font-medium',
                  active ? 'font-semibold text-foreground' : 'text-muted-foreground hover:text-foreground'
                )}
              >
                {t.icon}
                {t.label}
                {t.count != null && (
                  <span
                    className={cn(
                      'grid h-4 min-w-[16px] place-items-center rounded-full border px-1.5 text-[10px] font-semibold',
                      active ? 'border-primary/30 bg-primary/10 text-primary' : 'border-border bg-muted text-muted-foreground'
                    )}
                  >
                    {t.count}
                  </span>
                )}
                {active && <span className="absolute inset-x-2 -bottom-px h-0.5 rounded-t bg-primary" />}
              </button>
            )
          })}
        </div>
      </div>

      {/* ---- body ---- */}
      <div className="min-h-0 flex-1 overflow-auto bg-background">
        {activeTab === 'content' && <ContentPanel log={log} />}
        {activeTab === 'context' && <ContextPanel log={log} />}
        {activeTab === 'trace' && (
          <TracePanel
            log={log}
            spans={spans}
            isLoading={traceQuery.isLoading}
            isError={traceQuery.isError}
            traceHref={traceHref}
            onViewAllTraceLogs={
              onAddFacetFilter && log.traceId ? () => onAddFacetFilter('trace_id', log.traceId) : undefined
            }
          />
        )}
        {activeTab === 'attributes' && (
          <AttributesPanel
            log={log}
            traceHref={traceHref}
            spanHref={traceHref && log.spanId ? `${traceHref}?span=${log.spanId}` : undefined}
            onAddFacetFilter={onAddFacetFilter}
          />
        )}
        {activeTab === 'patterns' && (
          <PatternsPanel
            log={log}
            from={timeRange?.from}
            to={timeRange?.to}
            onViewMatching={
              onAddFacetFilter ? (pattern) => onAddFacetFilter('message_pattern', pattern) : undefined
            }
            onCreateMetric={onCreateMetric}
            onCreateMonitor={onCreateMonitor}
          />
        )}
      </div>
    </section>
  )
}
