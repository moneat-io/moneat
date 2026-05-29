// Moneat - observability platform
// Copyright (C) 2026 Moneat
//
// This program is free software: you can redistribute it and/or modify
// it under the terms of the GNU Affero General Public License as published by
// the Free Software Foundation, either version 3 of the License, or
// (at your option) any later version.

// Shared formatting + classification helpers for the profiling pages.

export function formatBytes(bytes: number): string {
  if (bytes < 1024) return `${bytes} B`
  if (bytes < 1024 * 1024) return `${(bytes / 1024).toFixed(1)} KB`
  if (bytes < 1024 * 1024 * 1024) return `${(bytes / (1024 * 1024)).toFixed(1)} MB`
  return `${(bytes / (1024 * 1024 * 1024)).toFixed(2)} GB`
}

export function formatDuration(ns: number): string {
  if (ns <= 0) return '—'
  if (ns < 1_000_000_000) return `${(ns / 1_000_000).toFixed(0)}ms`
  return `${(ns / 1_000_000_000).toFixed(1)}s`
}

/** ClickHouse `toString(DateTime64)` yields "2026-05-28 19:52:00.000" (UTC). */
export function parseUtcDate(value: string): Date {
  if (!value) return new Date(NaN)
  if (value.includes('T')) return new Date(value)
  // Normalize the space-separated UTC timestamp to ISO so it parses everywhere.
  return new Date(`${value.replace(' ', 'T')}Z`)
}

const compactFormatter = new Intl.NumberFormat('en', {
  notation: 'compact',
  maximumFractionDigits: 1,
})

/** Compact count, e.g. 41_000 -> "41K". */
export function formatCompact(value: number): string {
  return compactFormatter.format(value)
}

const TYPE_COLORS: Record<string, string> = {
  cpu: 'bg-orange-500/15 text-orange-400 border-orange-500/30',
  wall: 'bg-blue-500/15 text-blue-400 border-blue-500/30',
  heap: 'bg-green-500/15 text-green-400 border-green-500/30',
  alloc: 'bg-emerald-500/15 text-emerald-400 border-emerald-500/30',
  goroutine: 'bg-purple-500/15 text-purple-400 border-purple-500/30',
  mutex: 'bg-red-500/15 text-red-400 border-red-500/30',
  block: 'bg-yellow-500/15 text-yellow-400 border-yellow-500/30',
}

export function profileTypeBadgeClass(type: string): string {
  const key = type.toLowerCase()
  for (const [k, v] of Object.entries(TYPE_COLORS)) {
    if (key.includes(k)) return v
  }
  return 'bg-secondary text-secondary-foreground'
}

/** A profile is "live" if its most recent profile arrived within ~5 minutes. */
export const LIVE_THRESHOLD_MS = 5 * 60 * 1000
