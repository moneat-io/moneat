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
import {stripAnsi} from '@/lib/ansi'
import {AlertTriangle} from 'lucide-react'
import {CodeBox, JsonHighlight} from './CodeBox'
import {SecLabel} from './ViewerPrimitives'

function isJson(value: string): boolean {
  const t = value.trim()
  if (!(t.startsWith('{') || t.startsWith('['))) return false
  try {
    JSON.parse(t)
    return true
  } catch {
    return false
  }
}

export function ContentPanel({log}: {log: LogEntry}) {
  const message = stripAnsi(log.message || '')
  const body = stripAnsi(log.body || '')
  const showBody = body && body !== message
  const bodyIsJson = showBody && isJson(body)

  const stacktrace = log.tags?.['exception.stacktrace'] || log.tags?.['exception.stack_trace']
  const exceptionType = log.tags?.['exception.type']
  const exceptionMessage = log.tags?.['exception.message']

  return (
    <div className="p-3.5">
      <section className="mb-3.5">
        <SecLabel>Message</SecLabel>
        <CodeBox copyValue={message} copyLabel="message">
          {message || '—'}
        </CodeBox>
      </section>

      {showBody && (
        <section className="mb-3.5">
          <SecLabel>
            Body
            {bodyIsJson && (
              <span className="ml-1 inline-flex h-[15px] items-center rounded-sm border border-border bg-muted px-1 font-mono text-[9px] font-semibold text-muted-foreground">
                JSON
              </span>
            )}
          </SecLabel>
          <CodeBox copyValue={body} copyLabel="body" className="max-h-[320px]">
            {bodyIsJson ? <JsonHighlight value={body} /> : body}
          </CodeBox>
        </section>
      )}

      {stacktrace && (
        <section>
          <SecLabel>
            <AlertTriangle className="h-3 w-3 text-red-500" />
            Exception
          </SecLabel>
          {(exceptionType || exceptionMessage) && (
            <div className="mb-2 rounded-md border border-red-500/30 bg-red-500/5 px-3 py-2 font-mono text-xs">
              {exceptionType && <span className="font-semibold text-red-600 dark:text-red-400">{exceptionType}</span>}
              {exceptionType && exceptionMessage && <span className="text-muted-foreground">: </span>}
              {exceptionMessage && <span className="text-foreground/90">{exceptionMessage}</span>}
            </div>
          )}
          <CodeBox copyValue={stacktrace} copyLabel="stack trace" variant="danger">
            {stacktrace}
          </CodeBox>
        </section>
      )}
    </div>
  )
}
