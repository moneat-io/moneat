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

// Profile types are categorical, so each maps to a distinct chart hue.
const TYPE_COLORS: Record<string, string> = {
  cpu: 'bg-chart-6/15 text-chart-6 border-chart-6/30',
  wall: 'bg-chart-2/15 text-chart-2 border-chart-2/30',
  heap: 'bg-chart-4/15 text-chart-4 border-chart-4/30',
  alloc: 'bg-chart-9/15 text-chart-9 border-chart-9/30',
  goroutine: 'bg-chart-7/15 text-chart-7 border-chart-7/30',
  mutex: 'bg-chart-8/15 text-chart-8 border-chart-8/30',
  block: 'bg-chart-5/15 text-chart-5 border-chart-5/30',
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
