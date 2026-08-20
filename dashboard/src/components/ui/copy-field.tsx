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

import {useEffect, useId, useRef, useState} from 'react'
import {Check, Copy} from 'lucide-react'
import {Button} from '@/components/ui/button'
import {Input} from '@/components/ui/input'
import {Label} from '@/components/ui/label'
import {cn} from '@/lib/utils'

const COPIED_STATE_RESET_MS = 2000

export interface CopyFieldProps {
  /** Field label. Also the accessible name of the value input. */
  readonly label: string
  readonly value: string
  /** Helper text under the field. */
  readonly hint?: React.ReactNode
  /** Machine data (IDs, DSNs, endpoints) renders monospace. Defaults to true. */
  readonly mono?: boolean
  readonly id?: string
  readonly className?: string
  /** Fired after the value reaches the clipboard — for analytics. */
  readonly onCopied?: () => void
}

/**
 * A read-only value with a copy button — the standard way to hand a user a
 * string they need to paste elsewhere (DSNs, endpoints, slugs). Selecting the
 * input selects the whole value, so keyboard and clipboard both work.
 */
export function CopyField({label, value, hint, mono = true, id, className, onCopied}: CopyFieldProps) {
  const generatedId = useId()
  const fieldId = id ?? generatedId
  const [copied, setCopied] = useState(false)
  const resetId = useRef<ReturnType<typeof globalThis.setTimeout> | null>(null)

  useEffect(() => {
    return () => {
      if (resetId.current !== null) globalThis.clearTimeout(resetId.current)
    }
  }, [])

  const handleCopy = async () => {
    try {
      await navigator.clipboard.writeText(value)
    } catch {
      // No clipboard (insecure context) or the user denied it — leave the
      // button alone rather than reporting a copy that never happened.
      return
    }
    onCopied?.()
    setCopied(true)
    if (resetId.current !== null) globalThis.clearTimeout(resetId.current)
    resetId.current = globalThis.setTimeout(() => {
      setCopied(false)
      resetId.current = null
    }, COPIED_STATE_RESET_MS)
  }

  return (
    <div className={cn('space-y-1.5', className)}>
      <Label htmlFor={fieldId} className="text-xs">
        {label}
      </Label>
      <div className="flex gap-2">
        <Input
          id={fieldId}
          value={value}
          readOnly
          onFocus={(event) => event.target.select()}
          className={cn('h-8 bg-muted', mono && 'font-mono text-xs')}
        />
        <Button
          type="button"
          variant="outline"
          size="icon"
          className="h-8 w-8 shrink-0"
          aria-label={copied ? `${label} copied` : `Copy ${label.toLowerCase()}`}
          onClick={handleCopy}
        >
          {copied ? <Check className="h-3.5 w-3.5" /> : <Copy className="h-3.5 w-3.5" />}
        </Button>
      </div>
      {hint && <p className="text-[11px] text-muted-foreground">{hint}</p>}
    </div>
  )
}
