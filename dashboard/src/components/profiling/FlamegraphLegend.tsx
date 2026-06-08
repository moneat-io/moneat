// Moneat - observability platform
// Copyright (C) 2026 Moneat
//
// This program is free software: you can redistribute it and/or modify
// it under the terms of the GNU Affero General Public License as published by
// the Free Software Foundation, either version 3 of the License, or
// (at your option) any later version.

import {
  type ColorMode,
  type NamespaceStat,
  kindColor,
  packageColor,
} from './frameModel'

interface Props {
  mode: ColorMode
  namespaces: NamespaceStat[]
  appPrefixes: string[]
}

interface LegendEntry {
  label: string
  color: string
}

export function FlamegraphLegend({mode, namespaces, appPrefixes}: Props) {
  const entries = mode === 'kind'
    ? kindEntries()
    : packageEntries(namespaces, appPrefixes)

  return (
    <div className="flex flex-wrap gap-x-3 gap-y-1 text-[10px] text-muted-foreground pt-1 shrink-0">
      {entries.map((entry) => (
        <span key={entry.label} className="flex items-center gap-1">
          <span
            className="inline-block w-2 h-2 rounded-sm"
            style={{backgroundColor: entry.color}}
          />
          {entry.label}
        </span>
      ))}
    </div>
  )
}

function kindEntries(): LegendEntry[] {
  return [
    {label: 'your code', color: kindColor('app')},
    {label: 'library', color: kindColor('library')},
    {label: 'runtime / system', color: kindColor('runtime')},
  ]
}

function packageEntries(
  namespaces: NamespaceStat[],
  appPrefixes: string[],
): LegendEntry[] {
  const top = namespaces.slice(0, 6).map((ns) => ({
    label: appPrefixes.includes(ns.namespace)
      ? `${ns.namespace} (you)`
      : ns.namespace,
    color: packageColor(`${ns.namespace}.`),
  }))
  if (namespaces.length > 6) {
    top.push({label: 'other', color: packageColor('zzz.unmatched')})
  }
  return top
}
