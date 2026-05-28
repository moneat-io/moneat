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

import {useCallback, useState} from 'react'
import {Check, Copy} from 'lucide-react'

function CopyButton({text}: {text: string}) {
  const [copied, setCopied] = useState(false)

  const handleCopy = useCallback(async () => {
    try {
      await navigator.clipboard.writeText(text)
      setCopied(true)
      setTimeout(() => setCopied(false), 2000)
    } catch {
      // Ignore clipboard errors
    }
  }, [text])

  return (
    <button
      type="button"
      onClick={handleCopy}
      className="cursor-pointer rounded p-0.5 transition-colors hover:bg-muted"
      title="Copy to clipboard"
      aria-label="Copy to clipboard"
    >
      {copied ? (
        <Check className="h-3 w-3 text-emerald-500" />
      ) : (
        <Copy className="h-3 w-3 text-muted-foreground" />
      )}
    </button>
  )
}

interface AttributeTableProps {
  label?: string
  attributes: Record<string, string>
  scroll?: boolean
}

export function AttributeTable({label, attributes, scroll = false}: AttributeTableProps) {
  const entries = Object.entries(attributes ?? {})
  if (entries.length === 0) return null

  const table = (
    <table className="w-full text-xs">
      <tbody className="[&_tr:not(:last-child)_td]:border-b [&_tr:not(:last-child)_td]:border-border/50">
        {entries.map(([key, value]) => (
          <tr key={key} className="group">
            <td className="py-0.5 pr-3 align-top font-mono text-[11px] whitespace-nowrap text-muted-foreground">
              {key}
            </td>
            <td className="w-full break-all py-0.5 align-top text-right font-mono text-[11px]">{value}</td>
            <td className="py-0.5 align-top">
              <span className="opacity-0 transition-opacity group-hover:opacity-100">
                <CopyButton text={value} />
              </span>
            </td>
          </tr>
        ))}
      </tbody>
    </table>
  )

  return (
    <div className="space-y-1.5">
      {label && (
        <span className="text-[11px] font-semibold uppercase tracking-wider text-muted-foreground">
          {label} ({entries.length})
        </span>
      )}
      {scroll ? <div className="max-h-[200px] overflow-y-auto">{table}</div> : table}
    </div>
  )
}
