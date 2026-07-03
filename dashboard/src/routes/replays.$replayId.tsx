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

import {createFileRoute, Link, redirect} from '@tanstack/react-router'
import {useQuery} from '@tanstack/react-query'
import {type RefObject, useCallback, useMemo, useRef, useState} from 'react'
import {api, type ReplayDetail, type ReplayTimelineItem} from '@/lib/api'
import {formatRelativeTime} from '@/lib/utils'
import {useTimezone} from '@/hooks/useTimezone'
import {formatDateTime as formatDateTimeUtil, parseDate} from '@/lib/date-format'
import {ReplayPlayer, type ReplayPlayerHandle} from '@/components/ReplayPlayer'
import {MobileReplayViewer, type MobileReplayViewerHandle, type ReplayStatusBarContext} from '@/components/MobileReplayViewer'
import {ReplayTimelinePanel} from '@/components/ReplayTimelinePanel'
import {ReplayTimelineScrubber} from '@/components/ReplayTimelineScrubber'
import {BrowserWindowContainer} from '@/components/replay-containers/BrowserWindowContainer'
import {MobileDeviceContainer} from '@/components/replay-containers/MobileDeviceContainer'
import {Badge} from '@/components/ui/badge'
import {StatusDot} from '@/components/ui/status-dot'
import {
    AlertCircle,
    ArrowUpRight,
    ChevronDown,
    ChevronLeft,
    ChevronUp,
    Clock3,
    DatabaseZap,
    Globe,
    Layers,
    Loader2,
    Monitor,
    Smartphone,
    Tag,
    User,
} from 'lucide-react'

export const Route = createFileRoute('/replays/$replayId')({
  beforeLoad: ({ location }) => {
    if (!api.isAuthenticated()) {
      throw redirect({ to: '/login', search: { redirect: location.href } })
    }
  },
  component: ReplayDetailPage,
})

function formatDuration(ms: number) {
  if (ms >= 1000) return `${(ms / 1000).toFixed(2)}s`
  return `${ms.toFixed(0)}ms`
}

function formatDate(isoString: string, timezone: string) {
  if (!isoString) return 'N/A'
  const date = parseDate(isoString)
  if (Number.isNaN(date.getTime())) return 'Invalid Date'
  return formatDateTimeUtil(date, timezone)
}

function positiveDurationMs(duration: unknown): number | undefined {
  if (typeof duration === 'number' && Number.isFinite(duration) && duration > 0) return duration
  if (typeof duration !== 'string') return undefined

  const parsed = Number(duration)
  return Number.isFinite(parsed) && parsed > 0 ? parsed : undefined
}

const DEFAULT_MOBILE_SEGMENT_DURATION_MS = 5000

interface TimestampedReplayEvent {
  timestamp: number
}

interface MobileReplayEventForTiming {
  type: number
  timestamp: number
  segment_id?: number
  data?: {
    tag?: string
    payload?: Record<string, unknown> & { duration?: number | string }
  }
}

interface MobileVideoSegment {
  type: string
  segment_id?: number
}

function hasNumericTimestamp(event: unknown): event is TimestampedReplayEvent {
  return (
    typeof event === 'object' &&
    event !== null &&
    'timestamp' in event &&
    typeof (event as { timestamp: unknown }).timestamp === 'number'
  )
}

function isMobileReplayTimingEvent(event: unknown): event is MobileReplayEventForTiming {
  return (
    typeof event === 'object' &&
    event !== null &&
    'type' in event &&
    'timestamp' in event &&
    typeof (event as { type: unknown }).type === 'number' &&
    typeof (event as { timestamp: unknown }).timestamp === 'number'
  )
}

function isMobileVideoSegment(event: unknown): event is MobileVideoSegment {
  return typeof event === 'object' && event !== null && (event as { type?: unknown }).type === 'mobile_replay_video'
}

function uniqueMobileVideoSegments(events: unknown[]): MobileVideoSegment[] {
  const seenSegmentIds = new Set<number>()
  return events
    .filter(isMobileVideoSegment)
    .sort((a, b) => (a.segment_id ?? 0) - (b.segment_id ?? 0))
    .filter((segment) => {
      if (typeof segment.segment_id !== 'number') return true
      if (seenSegmentIds.has(segment.segment_id)) return false
      seenSegmentIds.add(segment.segment_id)
      return true
    })
}

function getRrwebRecordingDurationMs(events: unknown[]): number {
  let minTs = Infinity
  let maxTs = -Infinity
  for (const event of events.filter(hasNumericTimestamp)) {
    minTs = Math.min(minTs, event.timestamp)
    maxTs = Math.max(maxTs, event.timestamp)
  }
  return minTs < maxTs ? maxTs - minTs : 0
}

function mobileSegmentDurations(events: unknown[]): Map<number, number> {
  const durations = new Map<number, number>()

  for (const event of events.filter(isMobileReplayTimingEvent)) {
    if (event.type !== 5 || event.data?.tag !== 'video') continue

    const segmentId = typeof event.segment_id === 'number' ? event.segment_id : undefined
    const durationMs = positiveDurationMs(event.data?.payload?.duration)
    if (segmentId !== undefined && durationMs !== undefined) {
      durations.set(segmentId, durationMs)
    }
  }

  return durations
}

function getMobileRecordingDurationMs(events: unknown[]): number {
  const segmentDurations = mobileSegmentDurations(events)
  const videoSegments = uniqueMobileVideoSegments(events)

  return videoSegments.reduce((total, segment, index) => {
    const segmentId = typeof segment.segment_id === 'number' ? segment.segment_id : index
    return total + (segmentDurations.get(segmentId) ?? DEFAULT_MOBILE_SEGMENT_DURATION_MS)
  }, 0)
}

/** Compute actual recording duration from events. Backend replay.durationMs uses session span and can be wrong. */
function getRecordingDurationMs(events: unknown[], isMobileReplay: boolean): number {
  if (!Array.isArray(events) || events.length === 0) return 0
  return isMobileReplay ? getMobileRecordingDurationMs(events) : getRrwebRecordingDurationMs(events)
}

function getRecordingStartMs(events: unknown[]): number | null {
  let minTs = Infinity
  for (const event of events.filter(hasNumericTimestamp)) {
    minTs = Math.min(minTs, event.timestamp)
  }
  return Number.isFinite(minTs) ? minTs : null
}

function isLikelyEpochMs(timestampMs: number | null): boolean {
  if (timestampMs == null) return false
  // 2000-01-01 UTC to 2100-01-01 UTC
  return timestampMs >= 946684800000 && timestampMs <= 4102444800000
}

type MobileReplayOrientation = 'portrait' | 'landscape'
type MobileCompressedTimeMapper = (absoluteTimestampMs: number) => number
type ReplayTimelineItemWithData = ReplayTimelineItem & {
  data?: Record<string, unknown>
}

interface MobileBreadcrumbReplayEvent {
  type: number
  timestamp: number
  data?: {
    tag?: string
    payload?: Record<string, unknown>
  }
}

function isNativeMobilePlatform(platform?: string): platform is 'android' | 'ios' {
  return platform === 'android' || platform === 'ios'
}

function mobilePlatform(platform?: string): 'android' | 'ios' {
  return platform === 'ios' ? 'ios' : 'android'
}

function eventType(event: unknown): unknown {
  if (typeof event !== 'object' || event === null || !('type' in event)) return undefined
  return (event as { type: unknown }).type
}

function hasMobileVideoSegments(events: unknown[]): boolean {
  return events.some((event) => eventType(event) === 'mobile_replay_video')
}

function isMobileUnsupportedRecording(events: unknown[]): boolean {
  return events.length === 1 && eventType(events[0]) === 'mobile_replay_not_supported'
}

function isMobileReplaySession(replay: ReplayDetail | undefined, events: unknown[]): boolean {
  return isNativeMobilePlatform(replay?.platform) || hasMobileVideoSegments(events) || isMobileUnsupportedRecording(events)
}

function replayDurationMs(recordingDurationMs: number, computedDurationMs: number, replayDuration?: number): number {
  if (recordingDurationMs > 0) return recordingDurationMs
  if (computedDurationMs > 0) return computedDurationMs
  return replayDuration ?? 0
}

function replayProjectIdentifier(replay: ReplayDetail | undefined): string | undefined {
  return replay?.projectId
}

function replayPlatformLabel(platform?: string): string | null {
  return platform ? platform.charAt(0).toUpperCase() + platform.slice(1) : null
}

function createMobileCompressedTimeMapper(events: unknown[]): ((absoluteTimestampMs: number) => number) | null {
  const replayEvents = events
    .filter(isMobileReplayTimingEvent)
    .sort((a, b) => a.timestamp - b.timestamp)

  const getEventSegmentId = (event: MobileReplayEventForTiming): number | undefined => {
    if (typeof event.segment_id === 'number') return event.segment_id
    const payloadSegmentId = event.data?.payload?.segmentId
    if (typeof payloadSegmentId === 'number') return payloadSegmentId
    if (typeof payloadSegmentId === 'string') {
      const parsed = Number(payloadSegmentId)
      if (Number.isFinite(parsed)) return parsed
    }
    return undefined
  }

  const getVideoDurationMs = (event: MobileReplayEventForTiming): number | undefined => {
    if (event.type !== 5 || event.data?.tag !== 'video') return undefined
    return positiveDurationMs(event.data?.payload?.duration)
  }

  const isMeaningfulEvent = (event: MobileReplayEventForTiming): boolean => {
    if (event.type === 4) return false
    if (event.type === 5) {
      const tag = event.data?.tag
      if (tag === 'video') return false
      if (tag === 'breadcrumb') {
        const payload = event.data?.payload ?? {}
        const category = payload.category
        const action = payload.action
        if (typeof category === 'string' && category.startsWith('device.')) return false
        if (
          typeof action === 'string' &&
          ['SCREEN_OFF', 'SCREEN_ON', 'DREAMING_STARTED', 'DREAMING_STOPPED'].includes(action)
        ) {
          return false
        }
      }
    }
    return true
  }

  const videoSegments = uniqueMobileVideoSegments(events)

  if (videoSegments.length === 0) return null

  const meaningfulCountsBySegment = new Map<number, number>()
  for (const event of replayEvents) {
    if (!isMeaningfulEvent(event)) continue
    const segmentId = getEventSegmentId(event)
    if (segmentId === undefined) continue
    meaningfulCountsBySegment.set(segmentId, (meaningfulCountsBySegment.get(segmentId) ?? 0) + 1)
  }

  const filteredVideoSegments =
    meaningfulCountsBySegment.size > 0
      ? videoSegments.filter((segment) => {
          const segmentId = typeof segment.segment_id === 'number' ? segment.segment_id : undefined
          return segmentId !== undefined && (meaningfulCountsBySegment.get(segmentId) ?? 0) > 0
        })
      : videoSegments
  const effectiveVideoSegments = filteredVideoSegments.length > 0 ? filteredVideoSegments : videoSegments

  const segmentDurations = new Map<number, number>()
  for (const e of replayEvents) {
    const segmentId = getEventSegmentId(e)
    const durationMs = getVideoDurationMs(e)
    if (segmentId !== undefined && durationMs !== undefined) {
      segmentDurations.set(segmentId, durationMs)
    }
  }

  const segmentStarts = new Map<number, number>()
  for (const e of replayEvents) {
    const segmentId = getEventSegmentId(e)
    if (segmentId === undefined) continue
    const existing = segmentStarts.get(segmentId)
    if (existing === undefined || e.timestamp < existing) {
      segmentStarts.set(segmentId, e.timestamp)
    }
  }

  const segments = effectiveVideoSegments.map((seg, i) => {
    const segmentId = typeof seg.segment_id === 'number' ? seg.segment_id : i
    return {
      segmentId,
      startTimestamp: segmentStarts.get(segmentId),
      durationMs: segmentDurations.get(segmentId) ?? DEFAULT_MOBILE_SEGMENT_DURATION_MS,
    }
  })

  let runningOffset = 0
  const withOffsets = segments.map((seg) => {
    const mapped = { ...seg, globalOffsetMs: runningOffset }
    runningOffset += seg.durationMs
    return mapped
  })

  const byStart = withOffsets
    .filter((seg): seg is (typeof withOffsets)[number] & { startTimestamp: number } => typeof seg.startTimestamp === 'number')
    .sort((a, b) => a.startTimestamp - b.startTimestamp)

  if (byStart.length === 0) return null

  return (absoluteTimestampMs: number) => {
    if (!Number.isFinite(absoluteTimestampMs)) return 0

    let target = byStart[0]
    for (let i = 0; i < byStart.length; i++) {
      const current = byStart[i]
      const next = byStart[i + 1]
      if (absoluteTimestampMs < current.startTimestamp) {
        target = byStart[0]
        break
      }
      if (!next || absoluteTimestampMs < next.startTimestamp) {
        target = current
        break
      }
    }

    const localMs = Math.max(0, Math.min(absoluteTimestampMs - target.startTimestamp, target.durationMs))
    return target.globalOffsetMs + localMs
  }
}

function isMobileBreadcrumbEvent(event: unknown): event is MobileBreadcrumbReplayEvent {
  return (
    typeof event === 'object' &&
    event !== null &&
    'type' in event &&
    'timestamp' in event &&
    (event as { type: unknown }).type === 5 &&
    typeof (event as { timestamp: unknown }).timestamp === 'number' &&
    (event as { data?: { tag?: string } }).data?.tag === 'breadcrumb'
  )
}

function isMeaningfulBreadcrumbPayload(payload: Record<string, unknown>): boolean {
  const category = payload.category
  const action = payload.action
  if (typeof category === 'string' && category.startsWith('device.')) return false
  if (
    typeof action === 'string' &&
    ['SCREEN_OFF', 'SCREEN_ON', 'DREAMING_STARTED', 'DREAMING_STOPPED'].includes(action)
  ) {
    return false
  }
  return true
}

function formatBreadcrumbTitle(payload: Record<string, unknown>): string {
  const category = payload.category ?? payload.type ?? ''
  if (typeof category === 'string' && category.length > 0) {
    return category
      .split(/[._-]/)
      .map((part) => part.charAt(0).toUpperCase() + part.slice(1).toLowerCase())
      .join(' ')
  }

  const message = payload.message
  if (typeof message === 'string' && message.length > 0) return message
  return 'Breadcrumb'
}

function payloadText(value: unknown): string | undefined {
  if (typeof value === 'string') return value
  if (typeof value === 'number' || typeof value === 'boolean') return String(value)
  return undefined
}

function formatLifecycleBreadcrumbDetail(payload: Record<string, unknown>): string {
  const screen = payloadText(payload.screen) ?? 'Screen'
  const state = payloadText(payload.state) ?? ''
  return `${screen}: ${state}`
}

function formatClickBreadcrumbDetail(payload: Record<string, unknown>): string {
  const viewClass = payloadText(payload['view.class'])?.split('.').pop() ?? ''
  const viewId = payloadText(payload['view.id']) ?? ''
  return viewId ? `Clicked ${viewClass} (${viewId})` : `Clicked ${viewClass}`
}

function formatNavigationBreadcrumbDetail(payload: Record<string, unknown>): string {
  const from = payloadText(payload.from) ?? ''
  const to = payloadText(payload.to) ?? ''
  return `${from} → ${to}`
}

function formatHttpBreadcrumbDetail(payload: Record<string, unknown>): string | undefined {
  const parts = [
    payloadText(payload.method),
    payloadText(payload.url),
    payloadText(payload.status_code),
  ].filter((part): part is string => part !== undefined)

  return parts.length > 0 ? parts.join(' ') : undefined
}

function formatBreadcrumbDetail(payload: Record<string, unknown>, category: string): string | undefined {
  const normalizedCategory = category.toLowerCase()
  const message = payloadText(payload.message)
  if (message) return message
  if (normalizedCategory.includes('ui.lifecycle')) {
    return formatLifecycleBreadcrumbDetail(payload)
  }
  if (normalizedCategory.includes('ui.click')) {
    return formatClickBreadcrumbDetail(payload)
  }
  if (normalizedCategory.includes('navigation')) return formatNavigationBreadcrumbDetail(payload)
  if (normalizedCategory.includes('http') || normalizedCategory.includes('network')) return formatHttpBreadcrumbDetail(payload)

  return payloadText(payload.action) ?? payloadText(payload.type)
}

function clampTimelineOffsetMs(offsetMs: number, durationMs: number): number {
  return Math.max(0, durationMs > 0 ? Math.min(offsetMs, durationMs) : offsetMs)
}

interface BreadcrumbTimelineOptions {
  readonly events: unknown[]
  readonly isMobileReplay: boolean
  readonly recordingStartMs: number | null
  readonly durationMs: number
  readonly mobileCompressedTimeMapper: MobileCompressedTimeMapper | null
}

function buildBreadcrumbTimelineItems({
  events,
  isMobileReplay,
  recordingStartMs,
  durationMs,
  mobileCompressedTimeMapper,
}: BreadcrumbTimelineOptions): ReplayTimelineItemWithData[] {
  if (!isMobileReplay || !Array.isArray(events) || events.length === 0) return []
  const startMs = recordingStartMs ?? 0

  return events
    .filter(isMobileBreadcrumbEvent)
    .filter((event) => isMeaningfulBreadcrumbPayload(event.data?.payload ?? {}))
    .sort((a, b) => a.timestamp - b.timestamp)
    .map((event, index) => {
      const payload = event.data?.payload ?? {}
      const category = payload.category ?? payload.type ?? ''
      const categoryText = typeof category === 'string' ? category : ''
      const timestamp = event.timestamp
      let offsetMs = timestamp - startMs
      if (mobileCompressedTimeMapper && Number.isFinite(timestamp)) {
        offsetMs = mobileCompressedTimeMapper(timestamp)
      }

      return {
        id: `breadcrumb-${index}-${timestamp}`,
        type: 'span',
        timestamp: new Date(timestamp).toISOString(),
        offsetMs: clampTimelineOffsetMs(offsetMs, durationMs),
        title: formatBreadcrumbTitle(payload),
        description: formatBreadcrumbDetail(payload, categoryText),
        category: categoryText || undefined,
        data: Object.keys(payload).length > 0 ? payload : undefined,
      }
    })
}

interface NormalizedTimelineOptions {
  readonly items: ReplayTimelineItem[]
  readonly fallbackItems: ReplayTimelineItemWithData[]
  readonly replayStartMs: number
  readonly recordingStartMs: number | null
  readonly durationMs: number
  readonly mobileCompressedTimeMapper: MobileCompressedTimeMapper | null
}

function normalizeTimelineItems({
  items,
  fallbackItems,
  replayStartMs,
  recordingStartMs,
  durationMs,
  mobileCompressedTimeMapper,
}: NormalizedTimelineOptions): ReplayTimelineItemWithData[] {
  if (items.length === 0) return fallbackItems

  const shouldAdjust =
    replayStartMs > 0 &&
    isLikelyEpochMs(replayStartMs) &&
    isLikelyEpochMs(recordingStartMs)
  const shiftMs = shouldAdjust ? (recordingStartMs as number) - replayStartMs : 0
  const out: ReplayTimelineItemWithData[] = []

  for (const item of items) {
    const rawOffset = item.offsetMs - shiftMs
    if (!Number.isFinite(rawOffset)) continue

    let normalizedOffset = Math.max(0, rawOffset)
    if (mobileCompressedTimeMapper) {
      const absoluteMs = parseDate(item.timestamp).getTime()
      if (Number.isFinite(absoluteMs)) {
        normalizedOffset = mobileCompressedTimeMapper(absoluteMs)
      }
    }

    out.push({...item, offsetMs: clampTimelineOffsetMs(normalizedOffset, durationMs)})
  }

  return out
}

export const replayDetailHelperTestHooks = {
  ReplayRecordingStage,
  buildBreadcrumbTimelineItems,
  createMobileCompressedTimeMapper,
  formatDate,
  formatDuration,
  formatPlatformLabel: replayPlatformLabel,
  getRecordingDurationMs,
  getRecordingStartMs,
  hasMobileVideoSegments,
  isLikelyEpochMs,
  isMobileReplayPlaceholder: isMobileUnsupportedRecording,
  isMobileReplayPlatform: isNativeMobilePlatform,
  isReplayMobile: isMobileReplaySession,
  mobileContainerPlatform: mobilePlatform,
  replayEventType: eventType,
  replayProjectId: replayProjectIdentifier,
  normalizeTimelineItems,
  resolveReplayDurationMs: replayDurationMs,
}

interface ReplayRecordingStageProps {
  readonly recordingLoading: boolean
  readonly replay: ReplayDetail
  readonly events: unknown[]
  readonly isMobileReplay: boolean
  readonly hasRecording: boolean
  readonly replayPlayerRef: RefObject<ReplayPlayerHandle | null>
  readonly mobileReplayViewerRef: RefObject<MobileReplayViewerHandle | null>
  readonly mobileReplayOrientation: MobileReplayOrientation
  readonly mobileStatusBarContext: ReplayStatusBarContext
  readonly onTimeUpdate: (offsetMs: number) => void
  readonly onDurationReady: (durationMs: number) => void
  readonly onPlayingChange: (isPlaying: boolean) => void
  readonly onOrientationChange: (orientation: MobileReplayOrientation) => void
  readonly onStatusBarContextChange: (context: ReplayStatusBarContext) => void
}

function ReplayRecordingStage({
  recordingLoading,
  replay,
  events,
  isMobileReplay,
  hasRecording,
  replayPlayerRef,
  mobileReplayViewerRef,
  mobileReplayOrientation,
  mobileStatusBarContext,
  onTimeUpdate,
  onDurationReady,
  onPlayingChange,
  onOrientationChange,
  onStatusBarContextChange,
}: ReplayRecordingStageProps) {
  if (recordingLoading) {
    if (isNativeMobilePlatform(replay.platform)) {
      return (
        <MobileDeviceContainer
          platform={mobilePlatform(replay.platform)}
          orientation="portrait"
          className="py-2"
        >
          <div className="w-full h-full flex flex-col items-center justify-center gap-3 bg-black">
            <Loader2 className="h-8 w-8 animate-spin text-muted-foreground" />
            <p className="text-sm text-muted-foreground">Loading recording...</p>
          </div>
        </MobileDeviceContainer>
      )
    }

    return (
      <BrowserWindowContainer url={replay.urls?.[0]}>
        <div className="w-full h-[450px] flex flex-col items-center justify-center gap-3 bg-black">
          <Loader2 className="h-8 w-8 animate-spin text-muted-foreground" />
          <p className="text-sm text-muted-foreground">Loading recording...</p>
        </div>
      </BrowserWindowContainer>
    )
  }

  if (isMobileReplay) {
    return (
      <MobileDeviceContainer
        platform={mobilePlatform(replay.platform)}
        orientation={mobileReplayOrientation}
        className="py-2"
        statusBarContext={mobileStatusBarContext}
      >
        <MobileReplayViewer
          ref={mobileReplayViewerRef}
          events={events}
          platform={replay.platform || 'mobile'}
          hideControls
          onTimeUpdate={onTimeUpdate}
          onDurationReady={onDurationReady}
          onPlayingChange={onPlayingChange}
          onOrientationChange={onOrientationChange}
          onStatusBarContextChange={onStatusBarContextChange}
        />
      </MobileDeviceContainer>
    )
  }

  if (hasRecording) {
    return (
      <BrowserWindowContainer url={replay.urls?.[0]}>
        <ReplayPlayer
          ref={replayPlayerRef}
          events={events}
          width={800}
          height={450}
          autoPlay={false}
          showController={false}
          className="w-full"
          onTimeUpdate={onTimeUpdate}
          onDurationReady={onDurationReady}
          onPlayingChange={onPlayingChange}
        />
      </BrowserWindowContainer>
    )
  }

  return <p className="text-sm text-muted-foreground">No recording data available for this replay</p>
}

interface ReplayHeaderProps {
  readonly replay: ReplayDetail
  readonly replayId: string
  readonly durationMs: number
  readonly platformLabel: string | null
}

function ReplayHeader({ replay, replayId, durationMs, platformLabel }: ReplayHeaderProps) {
  const userLabel = replay.user?.email || replay.user?.username || replay.user?.id || 'Anonymous'

  return (
    <div className="flex shrink-0 flex-wrap items-center gap-x-2.5 gap-y-1.5 border-b px-3 py-2.5 text-sm lg:px-5">
      <Link
        to="/replays"
        className="inline-flex items-center gap-1 text-muted-foreground hover:text-foreground transition-colors"
      >
        <ChevronLeft className="h-3.5 w-3.5" />
        Replays
      </Link>
      <span className="text-muted-foreground/40">/</span>
      <span className="font-mono text-xs text-muted-foreground" title={replayId}>
        {replayId.slice(0, 8)}...
      </span>
      <span className="mx-0.5 h-4 w-px bg-border" aria-hidden />
      <StatusDot tone={replay.errorCount > 0 ? 'danger' : 'success'} />
      <span className="font-semibold">{userLabel}</span>
      {replay.errorCount > 0 && (
        <Badge variant="destructive" className="flex items-center gap-1 text-[11px]">
          <AlertCircle className="h-3 w-3" />
          {replay.errorCount} error{replay.errorCount === 1 ? '' : 's'}
        </Badge>
      )}
      {platformLabel && (
        <Badge variant="outline" className="flex items-center gap-1 text-[11px]">
          {isNativeMobilePlatform(replay.platform) ? (
            <Smartphone className="h-3 w-3" />
          ) : (
            <Monitor className="h-3 w-3" />
          )}
          {platformLabel}
        </Badge>
      )}
      {replay.environment && (
        <Badge variant="outline" className="text-[11px]">{replay.environment}</Badge>
      )}
      <span className="ml-auto inline-flex items-center gap-1.5 text-xs text-muted-foreground">
        <Clock3 className="h-3 w-3" />
        {formatRelativeTime(replay.startedAt)}
      </span>
      <span className="inline-flex items-center gap-1.5 text-xs">
        <span className="font-semibold text-primary tabular-nums">{formatDuration(durationMs)}</span>
        <span className="text-muted-foreground">duration</span>
      </span>
    </div>
  )
}

interface ReplayDetailsDrawerProps {
  readonly replay: ReplayDetail
  readonly durationMs: number
  readonly timezone: string
  readonly platformLabel: string | null
  readonly replayProjectId: string | undefined
  readonly expanded: boolean
  readonly onToggle: () => void
}

function ReplayDetailsDrawer({
  replay,
  durationMs,
  timezone,
  platformLabel,
  replayProjectId,
  expanded,
  onToggle,
}: ReplayDetailsDrawerProps) {
  return (
    <div className="shrink-0 border-t px-3 py-2 lg:px-5">
      <button
        type="button"
        onClick={onToggle}
        className="flex items-center gap-2 text-sm font-medium text-muted-foreground hover:text-foreground transition-colors w-full"
      >
        {expanded ? <ChevronUp className="h-4 w-4" /> : <ChevronDown className="h-4 w-4" />}
        Session Details
        <span className="flex-1 border-b border-border ml-2" />
      </button>

      {expanded && (
        <div className="mt-3 grid max-h-[42vh] grid-cols-1 gap-3 overflow-y-auto pr-1 sm:grid-cols-2 lg:grid-cols-3 xl:grid-cols-4">
          <ReplaySessionInfoCard
            replay={replay}
            durationMs={durationMs}
            timezone={timezone}
            platformLabel={platformLabel}
          />
          <ReplayUserCard replay={replay} />
          <ReplayBrowserOsCard replay={replay} />
          <ReplayUrlsCard replay={replay} />
          <ReplayErrorsCard replay={replay} replayProjectId={replayProjectId} />
          <ReplayTracesCard replay={replay} />
          <ReplayTagsCard replay={replay} />
        </div>
      )}
    </div>
  )
}

function ReplaySessionInfoCard({
  replay,
  durationMs,
  timezone,
  platformLabel,
}: Pick<ReplayDetailsDrawerProps, 'replay' | 'durationMs' | 'timezone' | 'platformLabel'>) {
  return (
    <div className="rounded-lg border bg-card p-3">
      <div className="text-xs font-medium text-muted-foreground mb-2 flex items-center gap-1.5">
        <Layers className="h-3.5 w-3.5" />
        Session
      </div>
      <div className="space-y-1.5 text-sm">
        <div className="flex justify-between gap-2">
          <span className="text-muted-foreground">Duration</span>
          <span className="font-medium">{formatDuration(durationMs)}</span>
        </div>
        <div className="flex justify-between gap-2">
          <span className="text-muted-foreground">Started</span>
          <span className="text-xs">{formatDate(replay.startedAt, timezone)}</span>
        </div>
        <div className="flex justify-between gap-2">
          <span className="text-muted-foreground">Finished</span>
          <span className="text-xs">{formatDate(replay.finishedAt, timezone)}</span>
        </div>
        {replay.environment && (
          <div className="flex justify-between gap-2">
            <span className="text-muted-foreground">Environment</span>
            <Badge variant="outline" className="text-xs">{replay.environment}</Badge>
          </div>
        )}
        {replay.release && (
          <div className="flex items-start justify-between gap-2">
            <span className="text-muted-foreground">Release</span>
            <span className="text-right min-w-0 max-w-[65%] break-words [overflow-wrap:anywhere] text-xs">
              {replay.release}
            </span>
          </div>
        )}
        {platformLabel && (
          <div className="flex justify-between gap-2">
            <span className="text-muted-foreground">Platform</span>
            <span>{platformLabel}</span>
          </div>
        )}
        {replay.segmentCount > 0 && (
          <div className="flex justify-between gap-2">
            <span className="text-muted-foreground">Segments</span>
            <span>{replay.segmentCount}</span>
          </div>
        )}
      </div>
    </div>
  )
}

function ReplayUserCard({ replay }: Pick<ReplayDetailsDrawerProps, 'replay'>) {
  const hasUser = replay.user?.email || replay.user?.username || replay.user?.id
  const hasContext = replay.geo || replay.ipAddress || replay.userSessionCount != null

  return (
    <div className="rounded-lg border bg-card p-3">
      <div className="text-xs font-medium text-muted-foreground mb-2 flex items-center gap-1.5">
        <User className="h-3.5 w-3.5" />
        User
      </div>
      {hasUser ? (
        <div className="space-y-1 text-sm">
          {replay.user?.email && <div>{replay.user.email}</div>}
          {replay.user?.username && <div className="text-muted-foreground">{replay.user.username}</div>}
          {replay.user?.id && (
            <div className="text-muted-foreground font-mono text-xs">{replay.user.id}</div>
          )}
        </div>
      ) : (
        <p className="text-sm text-muted-foreground">Anonymous</p>
      )}
      {hasContext && (
        <div className="mt-2 space-y-1 border-t pt-2 text-xs text-muted-foreground">
          {replay.geo && <div>{replay.geo}</div>}
          {replay.ipAddress && <div className="font-mono">{replay.ipAddress}</div>}
          {replay.userSessionCount != null && <div>{replay.userSessionCount} prior sessions</div>}
        </div>
      )}
    </div>
  )
}

function ReplayBrowserOsCard({ replay }: Pick<ReplayDetailsDrawerProps, 'replay'>) {
  return (
    <div className="rounded-lg border bg-card p-3">
      <div className="text-xs font-medium text-muted-foreground mb-2 flex items-center gap-1.5">
        <Monitor className="h-3.5 w-3.5" />
        Browser / OS
      </div>
      <div className="space-y-1 text-sm">
        {replay.browserName && (
          <div>
            {replay.browserName}
            {replay.browserVersion && ` ${replay.browserVersion}`}
          </div>
        )}
        {replay.osName && (
          <div className="text-muted-foreground">
            {replay.osName}
            {replay.osVersion && ` ${replay.osVersion}`}
          </div>
        )}
        {!replay.browserName && !replay.osName && (
          <p className="text-muted-foreground">Not recorded</p>
        )}
        {replay.viewport && <div className="font-mono text-xs text-muted-foreground">{replay.viewport}</div>}
        {replay.connection && <div className="text-xs text-muted-foreground">{replay.connection}</div>}
      </div>
    </div>
  )
}

function ReplayUrlsCard({ replay }: Pick<ReplayDetailsDrawerProps, 'replay'>) {
  if (!replay.urls?.length) return null

  return (
    <div className="rounded-lg border bg-card p-3">
      <div className="text-xs font-medium text-muted-foreground mb-2 flex items-center gap-1.5">
        <Globe className="h-3.5 w-3.5" />
        URLs ({replay.urls.length})
      </div>
      <ul className="space-y-1 text-sm max-h-32 overflow-y-auto">
        {replay.urls.map((url) => (
          <li key={url} className="truncate text-muted-foreground text-xs" title={url}>
            {url}
          </li>
        ))}
      </ul>
    </div>
  )
}

function ReplayErrorsCard({
  replay,
  replayProjectId,
}: Pick<ReplayDetailsDrawerProps, 'replay' | 'replayProjectId'>) {
  if (!replay.errorIds?.length) return null

  return (
    <div className="rounded-lg border bg-card p-3">
      <div className="text-xs font-medium text-muted-foreground mb-2 flex items-center gap-1.5">
        <AlertCircle className="h-3.5 w-3.5 text-danger-fg" />
        Errors ({replay.errorIds.length})
      </div>
      <div className="space-y-1.5 max-h-[160px] overflow-auto">
        {replay.errorIds.map((errorId) => (
          <Link
            key={errorId}
            to="/issues/$issueId"
            params={{ issueId: errorId }}
            search={{ projectId: replayProjectId }}
            className="flex items-center justify-between rounded border p-1.5 transition-colors hover:bg-accent group"
          >
            <span className="font-mono text-xs text-muted-foreground truncate min-w-0">{errorId}</span>
            <ArrowUpRight className="h-3 w-3 text-muted-foreground flex-shrink-0 ml-2 opacity-0 group-hover:opacity-100 transition-opacity" />
          </Link>
        ))}
      </div>
    </div>
  )
}

function ReplayTracesCard({ replay }: Pick<ReplayDetailsDrawerProps, 'replay'>) {
  if (!replay.traceIds?.length) return null

  return (
    <div className="rounded-lg border bg-card p-3">
      <div className="text-xs font-medium text-muted-foreground mb-2 flex items-center gap-1.5">
        <DatabaseZap className="h-3.5 w-3.5" />
        Traces ({replay.traceIds.length})
      </div>
      <ul className="space-y-1 font-mono text-xs text-muted-foreground max-h-[160px] overflow-auto">
        {replay.traceIds.map((traceId) => (
          <li key={traceId} className="truncate" title={traceId}>
            {traceId}
          </li>
        ))}
      </ul>
    </div>
  )
}

function ReplayTagsCard({ replay }: Pick<ReplayDetailsDrawerProps, 'replay'>) {
  const entries = Object.entries(replay.tags ?? {})
  if (entries.length === 0) return null

  return (
    <div className="rounded-lg border bg-card p-3">
      <div className="text-xs font-medium text-muted-foreground mb-2 flex items-center gap-1.5">
        <Tag className="h-3.5 w-3.5" />
        Tags ({entries.length})
      </div>
      <div className="flex flex-wrap gap-1.5">
        {entries.map(([key, value]) => (
          <div
            key={key}
            className="inline-flex items-center gap-1 rounded-md border bg-muted/30 px-2 py-0.5 text-[11px]"
          >
            <span className="text-muted-foreground">{key}:</span>
            <span className="font-mono font-medium">{value}</span>
          </div>
        ))}
      </div>
    </div>
  )
}

function ReplayDetailPage() {
  const { replayId } = Route.useParams()
  const { timezone } = useTimezone()

  const { data: replay, isLoading } = useQuery({
    queryKey: ['replay', replayId],
    queryFn: () => api.getReplay(replayId),
    enabled: !!replayId,
  })

  const { data: recording, isLoading: recordingLoading } = useQuery({
    queryKey: ['replay-recording', replayId],
    queryFn: () => api.getReplayRecording(replayId),
    enabled: !!replayId,
  })

  const { data: timeline } = useQuery({
    queryKey: ['replay-timeline', replayId],
    queryFn: () => api.getReplayTimeline(replayId),
    enabled: !!replayId,
  })

  const [currentOffsetMs, setCurrentOffsetMs] = useState(0)
  const [recordingDurationMs, setRecordingDurationMs] = useState(0)
  const [isPlaying, setIsPlaying] = useState(false)
  const [playbackSpeed, setPlaybackSpeed] = useState(1)
  const [detailsExpanded, setDetailsExpanded] = useState(false)
  const [mobileReplayOrientation, setMobileReplayOrientation] = useState<MobileReplayOrientation>('portrait')
  const [mobileStatusBarContext, setMobileStatusBarContext] = useState<ReplayStatusBarContext>({})
  const replayPlayerRef = useRef<ReplayPlayerHandle>(null)
  const mobileReplayViewerRef = useRef<MobileReplayViewerHandle>(null)

  const events = useMemo(() => recording?.events ?? [], [recording?.events])
  const isMobileReplay = isMobileReplaySession(replay, events)

  // Prefer player-reported duration (onDurationReady); fallback to computed or backend
  const computedDurationMs = useMemo(
    () => getRecordingDurationMs(events, isMobileReplay),
    [events, isMobileReplay]
  )
  const recordingStartMs = useMemo(
    () => getRecordingStartMs(events),
    [events]
  )
  const durationMs = replayDurationMs(recordingDurationMs, computedDurationMs, replay?.durationMs)
  const replayProjectId = replayProjectIdentifier(replay)
  const mobileCompressedTimeMapper = useMemo(
    () => (isMobileReplay ? createMobileCompressedTimeMapper(events) : null),
    [events, isMobileReplay]
  )

  const handleSeek = useCallback((offsetMs: number) => {
    // Clamp to recording length so Jump to always seeks to a valid position
    const clampedMs = Math.max(0, Math.min(offsetMs, durationMs))
    replayPlayerRef.current?.seekTo(clampedMs)
    mobileReplayViewerRef.current?.seekTo(clampedMs)
  }, [durationMs])

  const handlePlayPause = useCallback(() => {
    if (isPlaying) {
      replayPlayerRef.current?.pause()
      mobileReplayViewerRef.current?.pause()
      setIsPlaying(false)
    } else {
      replayPlayerRef.current?.play()
      mobileReplayViewerRef.current?.play()
      setIsPlaying(true)
    }
  }, [isPlaying])

  const handleSpeedChange = useCallback((speed: number) => {
    setPlaybackSpeed(speed)
    replayPlayerRef.current?.setSpeed(speed)
  }, [])

  // Derive breadcrumb timeline items from recording events for mobile replays when backend timeline is empty.
  const breadcrumbsFromEvents = useMemo(
    () =>
      buildBreadcrumbTimelineItems({
        events,
        isMobileReplay,
        recordingStartMs,
        durationMs,
        mobileCompressedTimeMapper,
      }),
    [durationMs, events, isMobileReplay, mobileCompressedTimeMapper, recordingStartMs]
  )

  // Align backend offsets (based on replay_start_timestamp) with player offsets (based on recording event start).
  // For mobile replays with no backend timeline, use breadcrumbs derived from recording events.
  const timelineItems = useMemo(
    () =>
      normalizeTimelineItems({
        items: timeline?.items ?? [],
        fallbackItems: breadcrumbsFromEvents,
        replayStartMs: timeline?.replayStartMs ?? 0,
        recordingStartMs,
        durationMs,
        mobileCompressedTimeMapper,
      }),
    [
      breadcrumbsFromEvents,
      durationMs,
      mobileCompressedTimeMapper,
      recordingStartMs,
      timeline?.items,
      timeline?.replayStartMs,
    ]
  )

  if (isLoading || !replay) {
    return (
      <div className="p-6">
        <div className="px-3 py-3 lg:px-5 lg:py-4">Loading replay...</div>
      </div>
    )
  }

  const hasRecording = events.length > 0 && !isMobileReplay
  const platformLabel = replayPlatformLabel(replay.platform)

  return (
    <div className="flex h-full min-h-0 flex-col overflow-hidden">
      <ReplayHeader
        replay={replay}
        replayId={replayId}
        durationMs={durationMs}
        platformLabel={platformLabel}
      />

      {/* ───── Player-hero: recording + scrubber  |  event rail ───── */}
      <div className="grid min-h-0 flex-1 grid-cols-1 gap-3 px-3 pb-3 pt-3 lg:grid-cols-[1.2fr_1fr] lg:px-5">
        {/* Left: recording (hero) on a stage + docked scrubber */}
        <div className="flex min-h-0 min-w-0 flex-col gap-2.5">
          <div className="flex min-h-0 flex-1 items-center justify-center overflow-auto rounded-lg border bg-muted/30 p-3">
            <ReplayRecordingStage
              recordingLoading={recordingLoading}
              replay={replay}
              events={events}
              isMobileReplay={isMobileReplay}
              hasRecording={hasRecording}
              replayPlayerRef={replayPlayerRef}
              mobileReplayViewerRef={mobileReplayViewerRef}
              mobileReplayOrientation={mobileReplayOrientation}
              mobileStatusBarContext={mobileStatusBarContext}
              onTimeUpdate={setCurrentOffsetMs}
              onDurationReady={setRecordingDurationMs}
              onPlayingChange={setIsPlaying}
              onOrientationChange={setMobileReplayOrientation}
              onStatusBarContextChange={setMobileStatusBarContext}
            />
          </div>

          {/* Scrubber docked directly under the recording */}
          <ReplayTimelineScrubber
            currentOffsetMs={currentOffsetMs}
            durationMs={durationMs}
            isPlaying={isPlaying}
            items={timelineItems}
            onSeek={handleSeek}
            onPlayPause={handlePlayPause}
            onSpeedChange={handleSpeedChange}
            speed={playbackSpeed}
            className="shrink-0 rounded-lg border"
          />
        </div>

        {/* Right: event rail */}
        <div className="flex min-h-0 min-w-0 flex-col overflow-hidden rounded-lg border bg-card">
          {timelineItems.length > 0 ? (
            <ReplayTimelinePanel
              items={timelineItems}
              currentOffsetMs={currentOffsetMs}
              projectId={replayProjectId}
              onSeek={handleSeek}
            />
          ) : (
            <div className="flex flex-1 items-center justify-center p-6 text-center text-sm text-muted-foreground">
              No breadcrumb events for this replay.
            </div>
          )}
        </div>
      </div>

      <ReplayDetailsDrawer
        replay={replay}
        durationMs={durationMs}
        timezone={timezone}
        platformLabel={platformLabel}
        replayProjectId={replayProjectId}
        expanded={detailsExpanded}
        onToggle={() => setDetailsExpanded((expanded) => !expanded)}
      />
    </div>
  )
}
