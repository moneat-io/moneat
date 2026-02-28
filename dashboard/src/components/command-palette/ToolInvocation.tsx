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
// MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
// See the GNU Affero General Public License for more details.
//
// You should have received a copy of the GNU Affero General Public License
// along with this program. If not, see <https://www.gnu.org/licenses/>.

import {useId, useState} from 'react'
import {Loader2, CheckCircle2, AlertCircle, ChevronDown} from 'lucide-react'
import {cn} from '@/lib/utils'
import type {AiPaletteToolInvocation} from '@/contexts/CommandPaletteContext'

function humanizeToolName(name: string): string {
  return name.replace(/[_-]/g, ' ').replace(/\b\w/g, (c) => c.toUpperCase())
}

function tryPrettyJson(text: string): string | null {
  const t = text.trim()
  if (!t.startsWith('{') && !t.startsWith('[')) return null
  try {
    return JSON.stringify(JSON.parse(t), null, 2)
  } catch {
    return null
  }
}

interface ToolInvocationProps {
  invocation: AiPaletteToolInvocation
}

export function ToolInvocation({invocation}: ToolInvocationProps) {
  const [expanded, setExpanded] = useState(false)
  const panelId = useId()
  const hasArgs = Boolean(invocation.args && Object.keys(invocation.args).length > 0)
  const prettyJson = invocation.summary ? tryPrettyJson(invocation.summary) : null
  const hasExpandableContent = hasArgs || prettyJson !== null

  const inlineSummary =
    invocation.summary && !prettyJson
      ? invocation.summary.length > 80
        ? `${invocation.summary.slice(0, 77)}…`
        : invocation.summary
      : null

  return (
    <div>
      <button
        type="button"
        onClick={() => hasExpandableContent && setExpanded((p) => !p)}
        className={cn(
          'flex w-full items-center gap-2 px-2.5 py-1 text-xs',
          hasExpandableContent ? 'cursor-pointer hover:bg-muted/50' : 'cursor-default',
        )}
        {...(hasExpandableContent && {
          'aria-expanded': expanded,
          'aria-controls': panelId,
        })}
      >
        {invocation.status === 'invoking' ? (
          <Loader2 className="h-3.5 w-3.5 shrink-0 animate-spin text-muted-foreground" />
        ) : invocation.status === 'completed' ? (
          <CheckCircle2 className="h-3.5 w-3.5 shrink-0 text-emerald-500" />
        ) : (
          <AlertCircle className="h-3.5 w-3.5 shrink-0 text-destructive" />
        )}
        <span className="font-medium text-muted-foreground">
          {humanizeToolName(invocation.tool)}
        </span>
        {inlineSummary && (
          <span className="truncate text-muted-foreground/60">— {inlineSummary}</span>
        )}
        {hasExpandableContent && (
          <ChevronDown
            className={cn(
              'ml-auto h-3 w-3 shrink-0 text-muted-foreground/40 transition-transform',
              expanded && 'rotate-180',
            )}
          />
        )}
      </button>
      {expanded && (
        <div id={panelId} className="mx-2.5 mb-1.5 space-y-1.5">
          {prettyJson && (
            <pre className="overflow-x-auto rounded bg-muted/40 p-2 text-[11px] leading-relaxed text-muted-foreground">
              {prettyJson}
            </pre>
          )}
          {hasArgs && (
            <pre className="overflow-x-auto rounded bg-muted/40 p-2 text-[11px] leading-relaxed text-muted-foreground">
              {JSON.stringify(invocation.args, null, 2)}
            </pre>
          )}
        </div>
      )}
    </div>
  )
}
