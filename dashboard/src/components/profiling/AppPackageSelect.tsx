// Moneat - observability platform
// Copyright (C) 2026 Moneat
//
// This program is free software: you can redistribute it and/or modify
// it under the terms of the GNU Affero General Public License as published by
// the Free Software Foundation, either version 3 of the License, or
// (at your option) any later version.

import {ChevronsUpDown} from 'lucide-react'
import {Button} from '@/components/ui/button'
import {
  DropdownMenu,
  DropdownMenuTrigger,
  DropdownMenuContent,
  DropdownMenuCheckboxItem,
  DropdownMenuLabel,
  DropdownMenuSeparator,
  DropdownMenuItem,
} from '@/components/ui/dropdown-menu'
import type {NamespaceStat} from './frameModel'

interface Props {
  namespaces: NamespaceStat[]
  /** Raw comma-separated prefixes the user has chosen (empty = auto-detect). */
  value: string
  /** Prefixes actually applied (used to show the auto-detected default). */
  effective: string[]
  onChange: (csv: string) => void
}

function parse(value: string): string[] {
  return value.split(',').map((s) => s.trim()).filter(Boolean)
}

export function AppPackageSelect({namespaces, value, effective, onChange}: Props) {
  const selected = parse(value)
  const isAuto = selected.length === 0

  const toggle = (ns: string) => {
    const next = selected.includes(ns)
      ? selected.filter((s) => s !== ns)
      : [...selected, ns]
    onChange(next.join(','))
  }

  let label: string
  if (!isAuto) {
    label = selected.length === 1 ? selected[0] : `${selected.length} selected`
  } else if (effective[0]) {
    label = `Auto · ${effective[0]}`
  } else {
    label = 'Auto-detect'
  }

  return (
    <DropdownMenu>
      <DropdownMenuTrigger asChild>
        <Button
          variant="outline"
          size="sm"
          className="h-7 w-full justify-between text-[11px] font-mono font-normal"
        >
          <span className="truncate">{label}</span>
          <ChevronsUpDown className="h-3.5 w-3.5 opacity-50 shrink-0" />
        </Button>
      </DropdownMenuTrigger>
      <DropdownMenuContent
        align="start"
        className="w-[var(--radix-popper-anchor-width)] min-w-52 max-h-72"
      >
        <DropdownMenuLabel className="text-[10px] uppercase tracking-wide text-muted-foreground">
          Detected namespaces
        </DropdownMenuLabel>
        {namespaces.length === 0 && (
          <div className="px-2 py-1.5 text-[11px] text-muted-foreground">
            None detected — type a prefix below.
          </div>
        )}
        {namespaces.map((ns) => (
          <DropdownMenuCheckboxItem
            key={ns.namespace}
            checked={selected.includes(ns.namespace)}
            onCheckedChange={() => toggle(ns.namespace)}
            onSelect={(e) => e.preventDefault()}
            className="text-[11px] font-mono"
          >
            <span className="truncate">{ns.namespace}</span>
          </DropdownMenuCheckboxItem>
        ))}
        {!isAuto && (
          <>
            <DropdownMenuSeparator />
            <DropdownMenuItem onClick={() => onChange('')} className="text-[11px]">
              Reset to auto-detect
            </DropdownMenuItem>
          </>
        )}
      </DropdownMenuContent>
    </DropdownMenu>
  )
}
