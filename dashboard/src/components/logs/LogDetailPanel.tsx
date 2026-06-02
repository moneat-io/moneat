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

import {Badge} from '@/components/ui/badge'
import {Button} from '@/components/ui/button'
import {Sheet, SheetContent, SheetDescription, SheetHeader, SheetTitle} from '@/components/ui/sheet'
import {Tabs, TabsContent, TabsList, TabsTrigger} from '@/components/ui/tabs'
import type {LogEntry} from '@/lib/api'
import {cn} from '@/lib/utils'
import {logLevelBadgeClass} from '@/lib/severity'
import {Check, Copy, ExternalLink, Eye} from 'lucide-react'
import {useCallback, useState} from 'react'
import {useTimezone} from '@/hooks/useTimezone'
import {formatDateTimeFull} from '@/lib/date-format'
import {LogOverviewTab} from '@/components/logs/tabs/LogOverviewTab'
import {LogTraceTab} from '@/components/logs/tabs/LogTraceTab'
import {LogContextTab} from '@/components/logs/tabs/LogContextTab'
import {LogAttributesTab} from '@/components/logs/tabs/LogAttributesTab'

interface LogDetailPanelProps {
  log: LogEntry | null
  open: boolean
  onClose: () => void
  onViewInContext?: (log: LogEntry) => void
  projectId?: string | number
}

// --- Shared helpers exported for use by tab components ---

export function CopyButton({value, label}: {value: string; label?: string}) {
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
      className="inline-flex shrink-0 cursor-pointer items-center gap-1 rounded p-1 text-muted-foreground transition-colors hover:bg-muted hover:text-foreground"
      title={label ? `Copy ${label}` : 'Copy to clipboard'}
      aria-label={label ? `Copy ${label}` : 'Copy to clipboard'}
    >
      {copied ? <Check className="h-3 w-3 text-success-fg" /> : <Copy className="h-3 w-3" />}
    </button>
  )
}

export function DetailField({label, value, mono = false, copyable = false}: {label: string; value?: string; mono?: boolean; copyable?: boolean}) {
  if (!value) return null

  return (
    <div className="group space-y-0.5">
      <span className="text-[11px] font-semibold uppercase tracking-wider text-muted-foreground">{label}</span>
      <div className="flex items-start gap-1.5">
        <span className={cn('flex-1 break-all text-xs', mono && 'font-mono')}>{value}</span>
        {copyable && (
          <span className="shrink-0 opacity-0 transition-opacity group-hover:opacity-100">
            <CopyButton value={value} label={label} />
          </span>
        )}
      </div>
    </div>
  )
}

export function LinkableField({label, value, to, mono = true}: {label: string; value?: string; to: string; mono?: boolean}) {
  if (!value) return null

  return (
    <div className="group space-y-1">
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
        <span className="shrink-0 opacity-0 transition-opacity group-hover:opacity-100">
          <CopyButton value={value} label={label} />
        </span>
      </div>
    </div>
  )
}

export function LogDetailPanel({log, open, onClose, onViewInContext, projectId}: LogDetailPanelProps) {
  const {timezone} = useTimezone()
  const [activeTab, setActiveTab] = useState('overview')
  const hasTrace = !!log?.traceId
  const visibleActiveTab = activeTab === 'trace' && !hasTrace ? 'overview' : activeTab

  if (!log) return null
  const normalizedLevel = (log.level || 'info').toLowerCase()

  return (
    <Sheet open={open} onOpenChange={(isOpen) => !isOpen && onClose()}>
      <SheetContent side="right" className="w-full sm:w-[65vw] sm:max-w-[65vw] overflow-y-auto p-0">
        <SheetHeader className="space-y-3 p-4 pb-0 sm:p-6 sm:pb-0">
          <div className="flex flex-wrap items-center gap-2">
            <Badge
              variant="outline"
              className={cn('font-mono text-xs uppercase', logLevelBadgeClass(normalizedLevel))}
            >
              {normalizedLevel}
            </Badge>
            {log.service && (
              <Badge variant="accent" className="font-mono text-xs">
                {log.service}
              </Badge>
            )}
            {log.environment && (
              <Badge variant="success" className="font-mono text-xs">
                {log.environment}
              </Badge>
            )}
            {log.source && log.source !== 'sdk' && (
              <Badge variant="outline" className="font-mono text-[10px]">{log.source}</Badge>
            )}
          </div>
          <SheetTitle className="text-sm font-normal leading-relaxed text-foreground/90">
            {formatDateTimeFull(log.timestamp, timezone)}
          </SheetTitle>
          {onViewInContext && (
            <Button
              variant="outline"
              size="sm"
              onClick={() => onViewInContext(log)}
              className="w-fit gap-2"
            >
              <Eye className="h-4 w-4" />
              View in Context
            </Button>
          )}
          <SheetDescription className="sr-only">Log entry details</SheetDescription>
        </SheetHeader>

        <Tabs value={visibleActiveTab} onValueChange={setActiveTab} className="w-full">
          <div className="border-b px-4 sm:px-6">
            <TabsList className="h-9 bg-transparent p-0 gap-4">
              <TabsTrigger
                value="overview"
                className="rounded-none border-b-2 border-transparent px-0 pb-2 pt-1 data-[state=active]:border-primary data-[state=active]:bg-transparent data-[state=active]:shadow-none"
              >
                Overview
              </TabsTrigger>
              {hasTrace && (
                <TabsTrigger
                  value="trace"
                  className="rounded-none border-b-2 border-transparent px-0 pb-2 pt-1 data-[state=active]:border-primary data-[state=active]:bg-transparent data-[state=active]:shadow-none"
                >
                  Trace
                </TabsTrigger>
              )}
              <TabsTrigger
                value="context"
                className="rounded-none border-b-2 border-transparent px-0 pb-2 pt-1 data-[state=active]:border-primary data-[state=active]:bg-transparent data-[state=active]:shadow-none"
              >
                Context
              </TabsTrigger>
              <TabsTrigger
                value="attributes"
                className="rounded-none border-b-2 border-transparent px-0 pb-2 pt-1 data-[state=active]:border-primary data-[state=active]:bg-transparent data-[state=active]:shadow-none"
              >
                Attributes
              </TabsTrigger>
            </TabsList>
          </div>

          <div className="p-4 sm:p-6">
            <TabsContent value="overview" className="mt-0">
              <LogOverviewTab log={log} projectId={projectId} />
            </TabsContent>
            {hasTrace && (
              <TabsContent value="trace" className="mt-0">
                <LogTraceTab log={log} active={visibleActiveTab === 'trace'} />
              </TabsContent>
            )}
            <TabsContent value="context" className="mt-0">
              <LogContextTab log={log} active={visibleActiveTab === 'context'} />
            </TabsContent>
            <TabsContent value="attributes" className="mt-0">
              <LogAttributesTab log={log} />
            </TabsContent>
          </div>
        </Tabs>
      </SheetContent>
    </Sheet>
  )
}
