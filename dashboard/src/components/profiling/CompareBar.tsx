// Moneat - observability platform
// Copyright (C) 2026 Moneat
//
// This program is free software: you can redistribute it and/or modify
// it under the terms of the GNU Affero General Public License as published by
// the Free Software Foundation, either version 3 of the License, or
// (at your option) any later version.

import {Loader2, X, GitCompare} from 'lucide-react'
import {Button} from '@/components/ui/button'
import {type DiffResult, type FunctionDelta, diffColor} from './frameModel'

export interface CompareProfile {
  profileId: string
  label: string
}

interface Props {
  profiles: CompareProfile[]
  compareId: string | null
  loading: boolean
  diff: DiffResult | null
  onChange: (id: string | null) => void
  onSelect: (name: string) => void
}

export function CompareBar({profiles, compareId, loading, diff, onChange, onSelect}: Props) {
  return (
    <div className="border rounded-lg p-2 space-y-2">
      <div className="flex items-center gap-2 flex-wrap">
        <span className="flex items-center gap-1 text-[11px] text-muted-foreground">
          <GitCompare className="h-3.5 w-3.5" />
          Compare to baseline:
        </span>
        <select
          value={compareId ?? ''}
          onChange={(e) => onChange(e.target.value || null)}
          className="h-7 text-xs rounded-md border bg-background px-2 max-w-xs"
        >
          <option value="">— none —</option>
          {profiles.map((p) => (
            <option key={p.profileId} value={p.profileId}>
              {p.label}
            </option>
          ))}
        </select>
        {loading && <Loader2 className="h-3.5 w-3.5 animate-spin text-muted-foreground" />}
        {compareId && (
          <Button variant="ghost" size="sm" className="h-7 px-2 text-[11px]" onClick={() => onChange(null)}>
            <X className="h-3 w-3 mr-0.5" />
            Clear
          </Button>
        )}
        {diff && (
          <span className="ml-auto flex items-center gap-3 text-[10px] text-muted-foreground">
            <LegendDot color={diffColor(3)} label="hotter now" />
            <LegendDot color={diffColor(-3)} label="cooler now" />
          </span>
        )}
      </div>

      {diff && (
        <div className="grid grid-cols-1 sm:grid-cols-2 gap-2">
          <DeltaList
            title="Top increases"
            entries={diff.topRegressions}
            onSelect={onSelect}
          />
          <DeltaList
            title="Top decreases"
            entries={diff.topImprovements}
            onSelect={onSelect}
          />
        </div>
      )}
    </div>
  )
}

function DeltaList({
  title,
  entries,
  onSelect,
}: {
  title: string
  entries: FunctionDelta[]
  onSelect: (name: string) => void
}) {
  return (
    <div className="border rounded-md overflow-hidden">
      <div className="px-2 py-1 bg-muted/50 text-[10px] font-medium text-muted-foreground border-b">
        {title}
      </div>
      <div className="max-h-[150px] overflow-y-auto">
        {entries.length === 0 && (
          <p className="px-2 py-2 text-[11px] text-muted-foreground">No significant change.</p>
        )}
        {entries.map((d) => (
          <button
            key={d.name}
            type="button"
            onClick={() => onSelect(d.name)}
            className="w-full flex items-center gap-2 px-2 py-1 text-left text-[11px] hover:bg-muted/40"
          >
            <span
              className="w-2 h-2 rounded-sm shrink-0"
              style={{backgroundColor: diffColor(d.deltaPercent)}}
            />
            <span className="font-mono truncate flex-1">{d.name}</span>
            <span className="tabular-nums shrink-0 text-muted-foreground">
              {d.deltaPercent >= 0 ? '+' : ''}
              {d.deltaPercent.toFixed(1)}%
            </span>
          </button>
        ))}
      </div>
    </div>
  )
}

function LegendDot({color, label}: {color: string; label: string}) {
  return (
    <span className="flex items-center gap-1">
      <span className="inline-block w-2 h-2 rounded-sm" style={{backgroundColor: color}} />
      {label}
    </span>
  )
}
