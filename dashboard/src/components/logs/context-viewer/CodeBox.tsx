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

import {cn} from '@/lib/utils'
import {Fragment, type ReactNode} from 'react'
import {CopyButton} from './CopyButton'
import {type JsonKind, type JsonToken, tokenizeJson} from './logContextHelpers'

interface CodeBoxProps {
  /** Value placed on the clipboard by the floating copy button. */
  copyValue?: string
  copyLabel?: string
  variant?: 'default' | 'danger'
  className?: string
  children: ReactNode
}

/** Bordered monospace block with a hover-revealed floating copy button. */
export function CodeBox({copyValue, copyLabel, variant = 'default', className, children}: Readonly<CodeBoxProps>) {
  return (
    <div
      className={cn(
        'group/code relative overflow-auto rounded-md border p-3 font-mono text-xs leading-relaxed',
        variant === 'danger'
          ? 'border-red-500/30 bg-red-500/5 text-foreground'
          : 'border-border bg-muted/40 text-foreground',
        className
      )}
    >
      <pre className="whitespace-pre-wrap break-words">{children}</pre>
      {copyValue && (
        <div className="absolute right-1.5 top-1.5 opacity-0 transition-opacity group-hover/code:opacity-100">
          <CopyButton
            value={copyValue}
            label={copyLabel}
            className="h-6 w-6 border border-border bg-card"
          />
        </div>
      )}
    </div>
  )
}

const kindClass: Record<JsonKind, string> = {
  key: 'text-sky-600 dark:text-sky-300',
  string: 'text-emerald-600 dark:text-emerald-400',
  number: 'text-amber-600 dark:text-amber-400',
  boolean: 'text-violet-600 dark:text-violet-400',
  plain: '',
}

interface KeyedJsonToken {
  key: string
  token: JsonToken
}

function keyJsonTokens(tokens: JsonToken[]): KeyedJsonToken[] {
  let offset = 0
  return tokens.map((token) => {
    const key = tokenKey(token, offset)
    offset += token.text.length
    return {key, token}
  })
}

/** Render a JSON string (or value) with light syntax highlighting. */
export function JsonHighlight({value}: Readonly<{value: unknown}>) {
  let json: string
  if (typeof value === 'string') {
    try {
      json = JSON.stringify(JSON.parse(value), null, 2)
    } catch {
      json = value
    }
  } else {
    json = JSON.stringify(value, null, 2)
  }
  const tokens = keyJsonTokens(tokenizeJson(json))
  return (
    <>
      {tokens.map(({key, token}) => {
        return (
          <Fragment key={key}>
            {token.kind === 'plain' ? token.text : <span className={kindClass[token.kind]}>{token.text}</span>}
          </Fragment>
        )
      })}
    </>
  )
}

function tokenKey(token: JsonToken, offset: number): string {
  return `${offset}:${token.kind}`
}
