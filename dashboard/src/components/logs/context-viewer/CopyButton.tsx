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
import {Check, Copy} from 'lucide-react'
import {useCallback, useRef, useState} from 'react'

interface CopyButtonProps {
  value: string
  label?: string
  className?: string
  iconClassName?: string
}

/** Small clipboard button that flashes a check on success. */
export function CopyButton({value, label, className, iconClassName}: CopyButtonProps) {
  const [copied, setCopied] = useState(false)
  const timeoutRef = useRef<ReturnType<typeof setTimeout> | null>(null)

  const handleCopy = useCallback(
    async (e: React.MouseEvent) => {
      e.stopPropagation()
      try {
        await navigator.clipboard.writeText(value)
        setCopied(true)
        if (timeoutRef.current) clearTimeout(timeoutRef.current)
        timeoutRef.current = setTimeout(() => setCopied(false), 1100)
      } catch {
        // Clipboard can reject in insecure contexts; nothing actionable here.
      }
    },
    [value]
  )

  if (!value || value === '-') return null

  return (
    <button
      type="button"
      onClick={handleCopy}
      title={label ? `Copy ${label}` : 'Copy'}
      className={cn(
        'inline-grid shrink-0 place-items-center rounded text-muted-foreground transition-colors hover:bg-accent hover:text-foreground',
        className
      )}
    >
      {copied ? (
        <Check className={cn('h-3 w-3 text-emerald-500', iconClassName)} />
      ) : (
        <Copy className={cn('h-3 w-3', iconClassName)} />
      )}
    </button>
  )
}
