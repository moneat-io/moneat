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

// Shared log-level palette for the context viewer. Mirrors LogTable/LogDetail
// so severity reads consistently across the logs surfaces.

export function normalizeLevel(level: string | undefined): string {
  const l = (level || 'info').toLowerCase()
  if (l === 'warning') return 'warn'
  if (l === 'err') return 'error'
  return l
}

/** Outline-badge styles (severity pill, level chips). */
export const levelBadgeStyles: Record<string, string> = {
  trace: 'bg-zinc-500/15 text-zinc-700 dark:text-zinc-300 border-zinc-500/30',
  debug: 'bg-teal-500/15 text-teal-700 dark:text-teal-300 border-teal-500/30',
  info: 'bg-indigo-500/15 text-indigo-700 dark:text-indigo-300 border-indigo-500/30',
  warn: 'bg-amber-500/15 text-amber-700 dark:text-amber-300 border-amber-500/30',
  error: 'bg-red-500/15 text-red-700 dark:text-red-300 border-red-500/30',
  fatal: 'bg-rose-500/20 text-rose-700 dark:text-rose-300 border-rose-500/40',
}

/** Solid dot background, used in the surrounding-logs stream and legends. */
export const levelDotStyles: Record<string, string> = {
  trace: 'bg-zinc-400',
  debug: 'bg-teal-500',
  info: 'bg-indigo-500',
  warn: 'bg-amber-500',
  error: 'bg-red-500',
  fatal: 'bg-rose-500',
}

/** Foreground text colour for a level. */
export const levelTextStyles: Record<string, string> = {
  trace: 'text-zinc-600 dark:text-zinc-400',
  debug: 'text-teal-600 dark:text-teal-400',
  info: 'text-indigo-600 dark:text-indigo-400',
  warn: 'text-amber-600 dark:text-amber-400',
  error: 'text-red-600 dark:text-red-400',
  fatal: 'text-rose-600 dark:text-rose-400',
}

/** Left accent border, matching the log table's per-level rail. */
export const levelBorderStyles: Record<string, string> = {
  trace: 'border-l-zinc-400/60',
  debug: 'border-l-teal-400/60',
  info: 'border-l-indigo-400/60',
  warn: 'border-l-amber-400/70',
  error: 'border-l-red-500/80',
  fatal: 'border-l-rose-500/90',
}

export function levelBadge(level: string): string {
  return levelBadgeStyles[normalizeLevel(level)] || levelBadgeStyles.info
}
export function levelDot(level: string): string {
  return levelDotStyles[normalizeLevel(level)] || levelDotStyles.info
}
export function levelText(level: string): string {
  return levelTextStyles[normalizeLevel(level)] || levelTextStyles.info
}
