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

import type {Replay, ReplaySignal} from '@/lib/api'
import type {BadgeProps} from '@/components/ui/badge'
import type {StatusTone} from '@/components/ui/status-dot'

type BadgeVariant = NonNullable<BadgeProps['variant']>
type ReplayUser = { id?: string; email?: string; username?: string }

/** Row status dot: errors dominate, then friction signals, then liveliness. */
export function replayStatusTone(
  replay: Pick<Replay, 'errorCount' | 'activity' | 'signals'>
): StatusTone {
  if (replay.errorCount > 0) return 'danger'
  const signals = replay.signals ?? []
  if (signals.includes('rage_click') || signals.includes('dead_click')) return 'warning'
  if (replay.activity >= 80) return 'success'
  return 'neutral'
}

/** Clock formatting (mm:ss / h:mm:ss) shared by the list, header, and scrubber. */
export function formatReplayClock(ms: number): string {
  const totalSeconds = Math.max(0, Math.floor(ms / 1000))
  const hours = Math.floor(totalSeconds / 3600)
  const minutes = Math.floor((totalSeconds % 3600) / 60)
  const seconds = totalSeconds % 60
  if (hours > 0) {
    return `${hours}:${String(minutes).padStart(2, '0')}:${String(seconds).padStart(2, '0')}`
  }
  return `${minutes}:${String(seconds).padStart(2, '0')}`
}

/** Display name precedence used everywhere a session is labelled. */
export function replayDisplayName(user?: ReplayUser): string {
  return user?.email || user?.username || user?.id || 'Anonymous'
}

export function isAnonymous(user?: ReplayUser): boolean {
  return !user?.email && !user?.username && !user?.id
}

export function replayInitials(user?: ReplayUser): string {
  if (user?.username) {
    const parts = user.username.trim().split(/\s+/)
    if (parts.length >= 2) return (parts[0][0] + parts[1][0]).toUpperCase()
    return user.username.slice(0, 2).toUpperCase()
  }
  if (user?.email) return user.email.slice(0, 2).toUpperCase()
  if (user?.id) return user.id.slice(0, 2).toUpperCase()
  return '?'
}

// Deterministic avatar tint from the categorical chart palette (literal classes
// so Tailwind emits them); encodes identity, not status.
const AVATAR_TINTS = [
  'bg-chart-1/20 text-chart-1',
  'bg-chart-2/20 text-chart-2',
  'bg-chart-3/20 text-chart-3',
  'bg-chart-4/20 text-chart-4',
  'bg-chart-5/20 text-chart-5',
  'bg-chart-6/20 text-chart-6',
  'bg-chart-7/20 text-chart-7',
  'bg-chart-8/20 text-chart-8',
] as const

export function replayAvatarClass(user?: ReplayUser): string {
  if (isAnonymous(user)) return 'bg-muted text-muted-foreground'
  const str = user?.username || user?.email || user?.id || ''
  let hash = 0
  for (const char of str) {
    hash = (char.codePointAt(0) ?? 0) + ((hash << 5) - hash)
  }
  return AVATAR_TINTS[Math.abs(hash) % AVATAR_TINTS.length]
}

export interface ActivityLevel {
  readonly label: string
  /** Tailwind class for the activity bar fill. */
  readonly barClass: string
}

export function getActivityLevel(activity: number): ActivityLevel {
  if (activity >= 80) return {label: 'High', barClass: 'bg-success-solid'}
  if (activity >= 40) return {label: 'Medium', barClass: 'bg-success-solid'}
  if (activity > 0) return {label: 'Low', barClass: 'bg-warning-solid'}
  return {label: 'Idle', barClass: 'bg-muted-foreground/60'}
}

export interface ReplaySignalBadge {
  readonly key: string
  readonly label: string
  readonly variant: BadgeVariant
}

const SIGNAL_BADGES: Record<Exclude<ReplaySignal, 'error'>, ReplaySignalBadge> = {
  rage_click: {key: 'rage', label: 'rage', variant: 'warning'},
  dead_click: {key: 'dead', label: 'dead', variant: 'warning'},
  bounce: {key: 'bounce', label: 'bounce', variant: 'neutral'},
  purchase: {key: 'purchase', label: 'purchase', variant: 'success'},
}

/**
 * Badges shown in the list's Signals column. The error count is derived from
 * `errorCount`; behavioural signals come from the optional `signals` array.
 */
export function deriveReplaySignals(
  replay: Pick<Replay, 'errorCount' | 'signals'>
): ReplaySignalBadge[] {
  const out: ReplaySignalBadge[] = []
  if (replay.errorCount > 0) {
    out.push({key: 'error', label: String(replay.errorCount), variant: 'danger'})
  }
  for (const signal of replay.signals ?? []) {
    if (signal === 'error') continue
    const badge = SIGNAL_BADGES[signal]
    if (badge) out.push(badge)
  }
  return out
}

/** Strip protocol + host so the table shows a compact "/path". */
export function replayEntryPath(replay: Pick<Replay, 'entryUrl' | 'urls'>): string {
  const raw = replay.entryUrl || replay.urls?.[0]
  if (!raw) return '—'
  try {
    const url = new URL(raw)
    return (url.pathname || '/') + (url.search || '')
  } catch {
    // Already a bare path, or unparseable — strip any leading scheme/host best-effort.
    return raw.replace(/^https?:\/\/[^/]+/i, '') || raw
  }
}

export function replayExtraPageCount(replay: Pick<Replay, 'urls'>): number {
  return Math.max(0, (replay.urls?.length ?? 0) - 1)
}

export function browserOsLabel(
  replay: Pick<Replay, 'browserName' | 'osName'>
): string | null {
  const label = [replay.browserName, replay.osName].filter(Boolean).join(' · ')
  return label.length > 0 ? label : null
}

const MOBILE_HINTS = ['ios', 'android', 'iphone', 'ipad', 'mobile']

export function isMobileOs(osName?: string): boolean {
  if (!osName) return false
  const lower = osName.toLowerCase()
  return MOBILE_HINTS.some((hint) => lower.includes(hint))
}
