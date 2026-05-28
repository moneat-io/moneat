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

import {useEffect, useState} from 'react'
import {Check, Copy} from 'lucide-react'
import {Prism as SyntaxHighlighter} from 'react-syntax-highlighter'
import {oneDark, oneLight} from 'react-syntax-highlighter/dist/esm/styles/prism'

function detectLanguage(stacktrace: string): string {
  if (/^\s+at\s+.+\(.+:\d+\)/.test(stacktrace) || /\tat\s/.test(stacktrace)) return 'javastacktrace'
  if (/File ".+", line \d+/.test(stacktrace)) return 'python'
  return 'log'
}

function getIsDarkMode(): boolean {
  return globalThis.document?.documentElement.classList.contains('dark') ?? false
}

interface StackTraceBlockProps {
  stacktrace: string
  language?: string
}

export function StackTraceBlock({stacktrace, language}: StackTraceBlockProps) {
  const [copied, setCopied] = useState(false)
  const [isDark, setIsDark] = useState(getIsDarkMode)

  useEffect(() => {
    const root = globalThis.document?.documentElement
    if (!root || globalThis.MutationObserver === undefined) return

    const observer = new MutationObserver(() => setIsDark(root.classList.contains('dark')))
    observer.observe(root, {attributes: true, attributeFilter: ['class']})
    return () => observer.disconnect()
  }, [])

  const handleCopy = async () => {
    try {
      await navigator.clipboard.writeText(stacktrace)
      setCopied(true)
      setTimeout(() => setCopied(false), 2000)
    } catch {
      // Ignore clipboard errors
    }
  }

  const lang = language ?? detectLanguage(stacktrace)
  const style = isDark ? oneDark : oneLight

  return (
    <div className="group overflow-hidden rounded-lg border border-red-500/20 bg-card">
      <div className="flex items-center justify-between border-b border-red-500/20 bg-red-500/5 px-4 py-2">
        <span className="font-mono text-[11px] text-red-700 dark:text-red-400">Exception</span>
        <button
          type="button"
          onClick={handleCopy}
          className="flex cursor-pointer items-center gap-1.5 rounded-md px-2 py-1 text-[11px] text-muted-foreground opacity-0 transition hover:bg-muted hover:text-foreground group-hover:opacity-100"
        >
          {copied ? (
            <>
              <Check className="h-3 w-3 text-emerald-500" />
              Copied
            </>
          ) : (
            <>
              <Copy className="h-3 w-3" />
              Copy
            </>
          )}
        </button>
      </div>
      <SyntaxHighlighter
        language={lang}
        style={style}
        customStyle={{
          margin: 0,
          padding: '1rem',
          fontSize: '0.75rem',
          lineHeight: 1.6,
          background: undefined,
        }}
        codeTagProps={{
          style: {
            fontFamily: 'ui-monospace, SFMono-Regular, "SF Mono", Menlo, Consolas, monospace',
          },
        }}
        wrapLongLines
      >
        {stacktrace}
      </SyntaxHighlighter>
    </div>
  )
}
