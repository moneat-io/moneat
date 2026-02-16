// Moneat - Mobile-First Error Monitoring Platform
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

import {Badge} from '@/components/ui/badge'
import {Button} from '@/components/ui/button'
import {Sheet, SheetContent, SheetDescription, SheetHeader, SheetTitle} from '@/components/ui/sheet'
import {Separator} from '@/components/ui/separator'
import type {LogEntry} from '@/lib/api'
import {cn} from '@/lib/utils'
import {stripAnsi} from '@/lib/ansi'
import {Check, Copy, ExternalLink, Eye} from 'lucide-react'
import {useCallback, useState} from 'react'
import {useParams} from '@tanstack/react-router'

interface LogDetailProps {
  log: LogEntry | null
  open: boolean
  onClose: () => void
  onViewInContext?: (log: LogEntry) => void
}

const levelStyles: Record<string, string> = {
  trace: 'bg-zinc-500/15 text-zinc-700 dark:text-zinc-300 border-zinc-500/25',
  debug: 'bg-teal-500/15 text-teal-700 dark:text-teal-300 border-teal-500/25',
  info: 'bg-indigo-500/15 text-indigo-700 dark:text-indigo-300 border-indigo-500/25',
  warn: 'bg-amber-500/15 text-amber-700 dark:text-amber-300 border-amber-500/25',
  error: 'bg-red-500/15 text-red-700 dark:text-red-300 border-red-500/25',
  fatal: 'bg-rose-500/20 text-rose-700 dark:text-rose-300 border-rose-500/30',
}

function CopyButton({value, label}: {value: string; label?: string}) {
  const [copied, setCopied] = useState(false)

  const handleCopy = useCallback(async () => {
    try {
      await navigator.clipboard.writeText(value)
      setCopied(true)
      setTimeout(() => setCopied(false), 2000)
    } catch {
      // Ignore clipboard errors
    }
  }, [value])

  if (!value || value === '-') return null

  return (
    <button
      type="button"
      onClick={handleCopy}
      className="inline-flex shrink-0 items-center gap-1 rounded p-1 text-muted-foreground transition-colors hover:bg-muted hover:text-foreground"
      title={label ? `Copy ${label}` : 'Copy to clipboard'}
    >
      {copied ? <Check className="h-3 w-3 text-emerald-500" /> : <Copy className="h-3 w-3" />}
    </button>
  )
}

function DetailField({label, value, mono = false, copyable = false}: {label: string; value?: string; mono?: boolean; copyable?: boolean}) {
  if (!value) return null

  return (
    <div className="space-y-1">
      <span className="text-[11px] font-semibold uppercase tracking-wider text-muted-foreground">{label}</span>
      <div className="flex items-start gap-1.5">
        <span className={cn('flex-1 break-all text-xs', mono && 'font-mono')}>{value}</span>
        {copyable && <CopyButton value={value} label={label} />}
      </div>
    </div>
  )
}

function LinkableField({label, value, to, mono = true}: {label: string; value?: string; to: string; mono?: boolean}) {
  if (!value) return null

  return (
    <div className="space-y-1">
      <span className="text-[11px] font-semibold uppercase tracking-wider text-muted-foreground">{label}</span>
      <div className="flex items-start gap-1.5">
        <a
          href={to}
          target="_blank"
          rel="noopener noreferrer"
          className={cn(
            'flex-1 break-all text-xs text-primary hover:underline inline-flex items-center gap-1',
            mono && 'font-mono'
          )}
        >
          {value}
          <ExternalLink className="h-3 w-3 inline shrink-0" />
        </a>
        <CopyButton value={value} label={label} />
      </div>
    </div>
  )
}

function MapSection({label, map}: {label: string; map: Record<string, string>}) {
  const entries = Object.entries(map)
  if (entries.length === 0) return null

  return (
    <div className="space-y-2">
      <span className="text-[11px] font-semibold uppercase tracking-wider text-muted-foreground">{label}</span>
      <div className="rounded-md border bg-muted/30 divide-y divide-border/50">
        {entries.map(([key, value]) => (
          <div key={key} className="flex items-start gap-3 px-3 py-2">
            <span className="min-w-[100px] shrink-0 font-mono text-[11px] text-muted-foreground">{key}</span>
            <span className="flex-1 break-all font-mono text-[11px]">{value}</span>
            <CopyButton value={value} label={key} />
          </div>
        ))}
      </div>
    </div>
  )
}

function tryFormatJson(value: string): {isJson: boolean; formatted: string} {
  try {
    const parsed = JSON.parse(value)
    return {isJson: true, formatted: JSON.stringify(parsed, null, 2)}
  } catch {
    return {isJson: false, formatted: value}
  }
}

function formatTimestamp(value: string): string {
  const date = new Date(value)
  if (Number.isNaN(date.getTime())) return value
  return date.toLocaleString(undefined, {
    weekday: 'short',
    year: 'numeric',
    month: 'short',
    day: 'numeric',
    hour: '2-digit',
    minute: '2-digit',
    second: '2-digit',
    timeZoneName: 'short',
  })
}

export function LogDetail({log, open, onClose, onViewInContext}: LogDetailProps) {
  if (!log) return null
  
  const {projectId} = useParams({strict: false})

  const normalizedLevel = (log.level || 'info').toLowerCase()
  const body = stripAnsi(log.body || '')
  const message = stripAnsi(log.message || '')
  const {isJson, formatted: formattedBody} = body ? tryFormatJson(body) : {isJson: false, formatted: ''}
  const hasTracing = log.traceId || log.spanId
  const hasContainer = log.containerName || log.containerId || log.containerImage

  return (
    <Sheet open={open} onOpenChange={(isOpen) => !isOpen && onClose()}>
      <SheetContent side="right" className="w-[32.5vw] max-w-[32.5vw] sm:max-w-[32.5vw] overflow-y-auto">
        <SheetHeader className="space-y-3 pb-4">
          <div className="flex flex-wrap items-center gap-2">
            <Badge
              variant="outline"
              className={cn('font-mono text-xs uppercase', levelStyles[normalizedLevel] || levelStyles.info)}
            >
              {normalizedLevel}
            </Badge>
            {log.service && (
              <Badge variant="secondary" className="font-mono text-xs bg-violet-500/10 text-violet-700 dark:text-violet-300 border border-violet-500/20">
                {log.service}
              </Badge>
            )}
            {log.environment && (
              <Badge variant="secondary" className="font-mono text-xs bg-emerald-500/10 text-emerald-700 dark:text-emerald-300 border border-emerald-500/20">
                {log.environment}
              </Badge>
            )}
            {log.source && log.source !== 'sdk' && (
              <Badge variant="outline" className="font-mono text-[10px]">{log.source}</Badge>
            )}
          </div>
          <SheetTitle className="text-sm font-normal leading-relaxed text-foreground/90">
            {formatTimestamp(log.timestamp)}
          </SheetTitle>
          {onViewInContext && (
            <Button
              variant="outline"
              size="sm"
              onClick={() => onViewInContext(log)}
              className="w-full gap-2"
            >
              <Eye className="h-4 w-4" />
              View in Context
            </Button>
          )}
          <SheetDescription className="sr-only">Log entry details</SheetDescription>
        </SheetHeader>

        <div className="space-y-5">
          {/* Message */}
          <div className="space-y-1.5">
            <span className="text-[11px] font-semibold uppercase tracking-wider text-muted-foreground">Message</span>
            <div className="relative">
              <pre className="overflow-x-auto rounded-lg border bg-muted/40 p-3 font-mono text-xs leading-relaxed whitespace-pre-wrap break-words">
                {message || '-'}
              </pre>
              {message && <div className="absolute right-2 top-2"><CopyButton value={message} label="message" /></div>}
            </div>
          </div>

          {/* Body */}
          {body && body !== message && (
            <div className="space-y-1.5">
              <div className="flex items-center gap-2">
                <span className="text-[11px] font-semibold uppercase tracking-wider text-muted-foreground">Body</span>
                {isJson && (
                  <Badge variant="outline" className="text-[9px] font-mono py-0 px-1">JSON</Badge>
                )}
              </div>
              <div className="relative">
                <pre className="max-h-[280px] overflow-auto rounded-lg border bg-muted/40 p-3 font-mono text-xs leading-relaxed whitespace-pre-wrap break-words">
                  {formattedBody}
                </pre>
                <div className="absolute right-2 top-2"><CopyButton value={body} label="body" /></div>
              </div>
            </div>
          )}

          <Separator />

          {/* Tracing */}
          {hasTracing && (
            <>
              <div className="grid gap-3 sm:grid-cols-2">
                {log.traceId && projectId && (
                  <LinkableField
                    label="Trace ID"
                    value={log.traceId}
                    to={`/projects/${projectId}/traces/${log.traceId}`}
                  />
                )}
                {log.spanId && projectId && (
                  <LinkableField
                    label="Span ID"
                    value={log.spanId}
                    to={`/projects/${projectId}/spans/${log.spanId}`}
                  />
                )}
              </div>
              <Separator />
            </>
          )}

          {/* Infrastructure */}
          <div className="grid gap-3 sm:grid-cols-2">
            <DetailField label="Host" value={log.host} mono copyable />
            <DetailField label="Source" value={log.source} mono />
          </div>

          {hasContainer && (
            <div className="grid gap-3 sm:grid-cols-2">
              <DetailField label="Container" value={log.containerName} mono copyable />
              <DetailField label="Container ID" value={log.containerId} mono copyable />
              {log.containerImage && (
                <div className="sm:col-span-2">
                  <DetailField label="Container Image" value={log.containerImage} mono copyable />
                </div>
              )}
            </div>
          )}

          <Separator />

          {/* Tags */}
          <MapSection label="Tags" map={log.tags} />

          {/* Resource Attributes */}
          <MapSection label="Resource Attributes" map={log.resourceAttributes} />

          {/* Log ID */}
          <div className="pt-2">
            <DetailField label="Log ID" value={log.logId} mono copyable />
          </div>
        </div>
      </SheetContent>
    </Sheet>
  )
}
