// Moneat - observability platform
// Copyright (C) 2026 Moneat
//
// This program is free software: you can redistribute it and/or modify
// it under the terms of the GNU Affero General Public License as published by
// the Free Software Foundation, either version 3 of the License, or
// (at your option) any later version.

import {ArrowDown, Copy} from 'lucide-react'
import {Button} from '@/components/ui/button'
import type {
  TopFunction,
  TopFunctionScope,
  TopFunctionSort,
} from './frameModel'

interface Props {
  functions: TopFunction[]
  scope: TopFunctionScope
  sortBy: TopFunctionSort
  hasAppPrefixes: boolean
  onScopeChange: (scope: TopFunctionScope) => void
  onSortChange: (sort: TopFunctionSort) => void
  onSelect: (name: string) => void
  onCopy: (name: string) => void
  colorOf: (name: string) => string
}

export function TopFunctionsPanel({
  functions,
  scope,
  sortBy,
  hasAppPrefixes,
  onScopeChange,
  onSortChange,
  onSelect,
  onCopy,
  colorOf,
}: Props) {
  return (
    <div className="border rounded-lg overflow-hidden">
      <div className="flex items-center justify-between gap-2 px-3 py-1.5 bg-muted/50 border-b">
        <div className="flex items-center gap-1">
          <ScopeButton
            label="All"
            active={scope === 'all'}
            onClick={() => onScopeChange('all')}
          />
          <ScopeButton
            label="My code"
            active={scope === 'app'}
            disabled={!hasAppPrefixes}
            onClick={() => onScopeChange('app')}
          />
        </div>
        <span className="text-[10px] text-muted-foreground">
          {functions.length} function{functions.length !== 1 ? 's' : ''}
        </span>
      </div>
      <div className="max-h-[260px] overflow-y-auto">
        <table className="w-full text-xs">
          <thead className="bg-muted sticky top-0 z-10">
            <tr className="border-b">
              <th className="text-left py-1.5 px-3 font-medium text-muted-foreground">
                Function
              </th>
              <SortHeader
                label="Self"
                active={sortBy === 'self'}
                onClick={() => onSortChange('self')}
              />
              <SortHeader
                label="Total"
                active={sortBy === 'total'}
                onClick={() => onSortChange('total')}
              />
            </tr>
          </thead>
          <tbody>
            {functions.length === 0 && (
              <tr>
                <td
                  colSpan={3}
                  className="py-4 px-3 text-center text-muted-foreground"
                >
                  No functions match this scope.
                </td>
              </tr>
            )}
            {functions.map((fn) => (
              <tr
                key={fn.name}
                className="border-b last:border-0 hover:bg-muted/40 cursor-pointer"
                onClick={() => onSelect(fn.name)}
                title={`${fn.name}\nClick to highlight in the flamegraph`}
              >
                <td className="py-1 px-3">
                  <div className="flex items-center gap-2 group/row">
                    <span
                      className="w-2 h-2 rounded-sm shrink-0"
                      style={{backgroundColor: colorOf(fn.name)}}
                    />
                    <span className="font-mono truncate">{fn.name}</span>
                    <button
                      type="button"
                      title="Copy function name"
                      aria-label="Copy function name"
                      className="ml-auto shrink-0 opacity-0 group-hover/row:opacity-100 text-muted-foreground hover:text-foreground transition-opacity"
                      onClick={(e) => {
                        e.stopPropagation()
                        onCopy(fn.name)
                      }}
                    >
                      <Copy className="h-3 w-3" />
                    </button>
                  </div>
                </td>
                <ValueCell percent={fn.selfPercent} value={fn.selfValue} />
                <ValueCell percent={fn.totalPercent} value={fn.totalValue} />
              </tr>
            ))}
          </tbody>
        </table>
      </div>
    </div>
  )
}

function ScopeButton({
  label,
  active,
  disabled,
  onClick,
}: {
  label: string
  active: boolean
  disabled?: boolean
  onClick: () => void
}) {
  return (
    <Button
      variant={active ? 'secondary' : 'ghost'}
      size="sm"
      className="h-6 px-2 text-[11px]"
      disabled={disabled}
      onClick={onClick}
    >
      {label}
    </Button>
  )
}

function SortHeader({
  label,
  active,
  onClick,
}: {
  label: string
  active: boolean
  onClick: () => void
}) {
  return (
    <th className="text-right py-1.5 px-3 font-medium text-muted-foreground w-28">
      <button
        type="button"
        onClick={onClick}
        className={`inline-flex items-center gap-1 hover:text-foreground transition-colors ${
          active ? 'text-foreground' : ''
        }`}
      >
        {label}
        <ArrowDown
          className={`h-3 w-3 transition-opacity ${active ? 'opacity-100' : 'opacity-0'}`}
        />
      </button>
    </th>
  )
}

function ValueCell({percent, value}: {percent: number; value: number}) {
  return (
    <td className="py-1 px-3 text-right font-mono tabular-nums">
      <span className="text-foreground">{percent.toFixed(1)}%</span>
      <span className="text-muted-foreground ml-1">
        ({value.toLocaleString()})
      </span>
    </td>
  )
}
