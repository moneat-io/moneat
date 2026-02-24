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

import {useEffect, useRef, useState} from 'react'
import {Prism as SyntaxHighlighter} from 'react-syntax-highlighter'
import {oneDark, oneLight} from 'react-syntax-highlighter/dist/esm/styles/prism'

interface CodeEditorProps {
  value: string
  onChange: (value: string) => void
  language: string
  placeholder?: string
  rows?: number
  className?: string
}

export function CodeEditor({
  value,
  onChange,
  language,
  placeholder,
  rows = 4,
  className = '',
}: CodeEditorProps) {
  const textareaRef = useRef<HTMLTextAreaElement>(null)
  const highlightRef = useRef<HTMLDivElement>(null)
  const [isDark, setIsDark] = useState(() =>
    document.documentElement.classList.contains('dark')
  )

  useEffect(() => {
    const root = document.documentElement
    const observer = new MutationObserver(() =>
      setIsDark(root.classList.contains('dark'))
    )
    observer.observe(root, {attributes: true, attributeFilter: ['class']})
    return () => observer.disconnect()
  }, [])

  const syncScroll = () => {
    if (textareaRef.current && highlightRef.current) {
      highlightRef.current.scrollTop = textareaRef.current.scrollTop
      highlightRef.current.scrollLeft = textareaRef.current.scrollLeft
    }
  }

  const style = isDark ? oneDark : oneLight
  const minHeight = `${rows * 1.5}rem`

  // Map common language aliases
  const prismLang = language === 'promql' ? 'promql' : language

  return (
    <div className={`relative rounded-md border overflow-hidden ${className}`}>
      {/* Syntax highlighted layer */}
      <div
        ref={highlightRef}
        className="absolute inset-0 overflow-hidden pointer-events-none"
        aria-hidden="true"
      >
        <SyntaxHighlighter
          language={prismLang}
          style={style}
          customStyle={{
            margin: 0,
            padding: '0.5rem 0.75rem',
            background: 'transparent',
            fontSize: '0.8125rem',
            lineHeight: '1.5',
            minHeight,
            fontFamily: 'ui-monospace, SFMono-Regular, "SF Mono", Menlo, Consolas, monospace',
          }}
          codeTagProps={{
            style: {
              fontFamily: 'inherit',
            },
          }}
          wrapLongLines
        >
          {value || ' '}
        </SyntaxHighlighter>
      </div>

      {/* Transparent textarea for editing */}
      <textarea
        ref={textareaRef}
        className="relative w-full bg-transparent px-3 py-2 text-[0.8125rem] leading-[1.5] font-mono resize-y text-transparent caret-foreground outline-none"
        style={{minHeight, caretColor: isDark ? '#fff' : '#000'}}
        value={value}
        onChange={(e) => onChange(e.target.value)}
        onScroll={syncScroll}
        placeholder={placeholder}
        spellCheck={false}
      />
    </div>
  )
}
