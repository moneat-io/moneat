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

import type {LogEntry} from '@/lib/api'
import {cn} from '@/lib/utils'
import {ExternalLink, Link2, Minus, Plus, Search} from 'lucide-react'
import {useMemo, useState} from 'react'
import {CodeBox, JsonHighlight} from './CodeBox'
import {CopyButton} from './CopyButton'
import {
  type AttrPill,
  type AttrRow,
  countLogAttributes,
  filterAttrGroups,
  groupLogAttributes,
} from './logContextHelpers'

interface AttributesPanelProps {
  log: LogEntry
  traceHref?: string
  spanHref?: string
  onAddFacetFilter?: (key: string, value: string, exclude?: boolean) => void
}

const pillClass: Record<Exclude<AttrPill, null>, string> = {
  err: 'border-red-500/30 bg-red-500/10 text-red-600 dark:text-red-400',
  ok: 'border-emerald-500/30 bg-emerald-500/10 text-emerald-600 dark:text-emerald-400',
  warn: 'border-amber-500/30 bg-amber-500/10 text-amber-600 dark:text-amber-400',
}

/** Map a display key to the explorer's facet-filter key, or null if not filterable. */
function facetKeyFor(rowKey: string): string | null {
  switch (rowKey) {
    case 'status':
    case 'timestamp':
    case 'trace_id':
    case 'span_id':
      return null
    case 'env':
      return 'environment'
    default:
      return rowKey
  }
}

function buildJsonView(log: LogEntry): Record<string, unknown> {
  const out: Record<string, unknown> = {}
  if (log.level) out.status = log.level
  if (log.service) out.service = log.service
  if (log.environment) out.env = log.environment
  if (log.host) out.host = log.host
  if (log.source) out.source = log.source
  out.timestamp = log.timestamp
  if (log.traceId) out.trace_id = log.traceId
  if (log.spanId) out.span_id = log.spanId
  for (const [k, v] of Object.entries(log.tags || {})) {
    if (k.startsWith('exception.stack')) continue
    out[k] = v
  }
  if (Object.keys(log.resourceAttributes || {}).length > 0) out.resource = log.resourceAttributes
  return out
}

function AttrValue({row, traceHref, spanHref}: Readonly<{row: AttrRow; traceHref?: string; spanHref?: string}>) {
  if (row.linkable) {
    const href = row.key === 'trace_id' ? traceHref : spanHref
    if (href) {
      return (
        <a
          href={href}
          target="_blank"
          rel="noopener noreferrer"
          className="inline-flex items-center gap-1 break-all text-primary hover:underline"
        >
          {row.value}
          <ExternalLink className="h-3 w-3 shrink-0 text-muted-foreground" />
        </a>
      )
    }
  }
  if (row.pill) {
    return (
      <span className={cn('rounded-sm border px-1.5 py-0.5 font-semibold', pillClass[row.pill])}>{row.value}</span>
    )
  }
  return (
    <span className="flex items-center gap-2 break-all">
      {row.value}
      <span className="inline-grid h-3.5 shrink-0 place-items-center rounded-sm border border-border/70 px-1 font-sans text-[9px] font-semibold text-muted-foreground/80">
        {row.type}
      </span>
    </span>
  )
}

interface AttributeContentProps {
  view: 'table' | 'json'
  log: LogEntry
  query: string
  filtered: ReturnType<typeof filterAttrGroups>
  traceHref?: string
  spanHref?: string
  onAddFacetFilter?: (key: string, value: string, exclude?: boolean) => void
}

function AttributeContent({
  view,
  log,
  query,
  filtered,
  traceHref,
  spanHref,
  onAddFacetFilter,
}: Readonly<AttributeContentProps>) {
  if (view === 'json') {
    const jsonValue = buildJsonView(log)
    return (
      <CodeBox copyValue={JSON.stringify(jsonValue, null, 2)} copyLabel="attributes">
        <JsonHighlight value={jsonValue} />
      </CodeBox>
    )
  }

  if (filtered.length === 0) {
    return (
      <div className="rounded-md border border-border bg-card py-8 text-center text-xs text-muted-foreground">
        No attributes match "{query}".
      </div>
    )
  }

  return (
    <div className="overflow-hidden rounded-md border border-border bg-card">
      {filtered.map((group, gi) => (
        <div key={group.name}>
          <div
            className={cn(
              'px-2.5 pb-1 pt-2 text-[10px] font-semibold uppercase tracking-wider text-muted-foreground',
              gi > 0 && 'border-t border-border/60'
            )}
          >
            {group.name} <span className="font-medium text-muted-foreground/60">{group.rows.length}</span>
          </div>
          {group.rows.map((row) => {
            const facetKey = facetKeyFor(row.key)
            return (
              <div
                key={`${group.name}:${row.key}`}
                className="group/arow grid grid-cols-[minmax(120px,200px)_1fr_auto] items-center gap-2.5 border-b border-border/50 px-2.5 py-1 font-mono text-[11px] last:border-b-0 hover:bg-accent/40"
              >
                <span className="flex items-center gap-1.5 truncate text-muted-foreground">
                  {row.linkable && <Link2 className="h-2.5 w-2.5 shrink-0 text-muted-foreground/60" />}
                  {row.key}
                </span>
                <span className="min-w-0 text-foreground">
                  <AttrValue row={row} traceHref={traceHref} spanHref={spanHref} />
                </span>
                <span className="flex items-center gap-0.5 opacity-0 transition-opacity group-hover/arow:opacity-100">
                  {facetKey && onAddFacetFilter && (
                    <>
                      <button
                        type="button"
                        title="Filter to"
                        onClick={() => onAddFacetFilter(facetKey, row.value, false)}
                        className="grid h-5 w-5 place-items-center rounded-sm text-muted-foreground/70 hover:bg-accent hover:text-foreground"
                      >
                        <Plus className="h-3 w-3" />
                      </button>
                      <button
                        type="button"
                        title="Exclude"
                        onClick={() => onAddFacetFilter(facetKey, row.value, true)}
                        className="grid h-5 w-5 place-items-center rounded-sm text-muted-foreground/70 hover:bg-accent hover:text-foreground"
                      >
                        <Minus className="h-3 w-3" />
                      </button>
                    </>
                  )}
                  <CopyButton value={row.value} label={row.key} className="h-5 w-5" />
                </span>
              </div>
            )
          })}
        </div>
      ))}
    </div>
  )
}

export function AttributesPanel({log, traceHref, spanHref, onAddFacetFilter}: Readonly<AttributesPanelProps>) {
  const [query, setQuery] = useState('')
  const [view, setView] = useState<'table' | 'json'>('table')

  const groups = useMemo(() => groupLogAttributes(log), [log])
  const total = useMemo(() => countLogAttributes(groups), [groups])
  const filtered = useMemo(() => filterAttrGroups(groups, query), [groups, query])

  return (
    <div className="p-3.5">
      <div className="mb-3 flex items-center gap-2">
        <div className="flex h-7 flex-1 items-center gap-2 rounded-md border border-border bg-card px-2.5">
          <Search className="h-3.5 w-3.5 text-muted-foreground/70" />
          <input
            value={query}
            onChange={(e) => setQuery(e.target.value)}
            placeholder={`Filter ${total} attributes by key or value…`}
            className="min-w-0 flex-1 bg-transparent font-mono text-xs text-foreground outline-none placeholder:font-sans placeholder:text-muted-foreground/70"
          />
        </div>
        <div className="flex h-7 overflow-hidden rounded-md border border-border">
          {(['table', 'json'] as const).map((v) => (
            <button
              key={v}
              type="button"
              onClick={() => setView(v)}
              className={cn(
                'px-2.5 text-xs font-medium capitalize transition-colors',
                view === v
                  ? 'bg-primary/10 font-semibold text-primary'
                  : 'text-muted-foreground hover:bg-accent hover:text-foreground',
                v === 'json' && 'border-l border-border'
              )}
            >
              {v}
            </button>
          ))}
        </div>
      </div>

      <AttributeContent
        view={view}
        log={log}
        query={query}
        filtered={filtered}
        traceHref={traceHref}
        spanHref={spanHref}
        onAddFacetFilter={onAddFacetFilter}
      />
    </div>
  )
}
