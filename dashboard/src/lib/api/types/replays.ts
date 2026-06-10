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

/**
 * Behavioural signals surfaced as list badges / facets. `error` is derived from
 * errorCount today; the rest are populated once the backend computes them.
 */
export type ReplaySignal = 'error' | 'rage_click' | 'dead_click' | 'bounce' | 'purchase'

export interface Replay {
  replayId: string
  projectId: number
  projectResourceId: string
  startedAt: string
  finishedAt: string
  durationMs: number
  urls: string[]
  errorCount: number
  user?: { id?: string; email?: string; username?: string }
  browserName?: string
  browserVersion?: string
  osName?: string
  osVersion?: string
  activity: number
  // Optional — populated once the backend emits behavioural signals; until then
  // the list derives the `error` badge from errorCount and leaves the rest empty.
  signals?: ReplaySignal[]
  /** First page of the session; falls back to urls[0] when absent. */
  entryUrl?: string
}

export interface ReplayDetail extends Replay {
  errorIds: string[]
  traceIds: string[]
  segmentCount: number
  environment?: string
  release?: string
  platform?: string
  tags: Record<string, string>
  // Optional context cards — backend-pending; rendered only when present.
  ipAddress?: string
  geo?: string
  viewport?: string
  connection?: string
  userSessionCount?: number
}

export interface ReplayRecordingResponse {
  events: unknown[]
}

export interface EventIssueLink {
  issueId: string
  projectResourceId: string
}

export interface Feedback {
  feedbackId: string
  message: string
  contactEmail: string
  name: string
  url: string
  status: string
  timestamp: string
  environment: string
  release: string
  platform: string
  user?: { id?: string; email?: string; username?: string }
  associatedEventId?: string | null
  replayId?: string | null
  sourceType: string
  sourceName: string
  sourceEventName: string
  traceId: string
  spanId: string
  resourceAttributes: Record<string, string>
}

export interface FeedbackDetail extends Feedback {
  tags: Record<string, string>
  sdkName: string
  sdkVersion: string
}

export interface ReplayTimelineItem {
  id: string
  type: 'error' | 'transaction' | 'span'
  timestamp: string
  offsetMs: number
  title: string
  description?: string
  durationMs?: number
  category?: string
  eventId?: string
  issueId?: string
  traceId?: string
  /** HTTP status for network events — drives the inline status badge (backend-pending). */
  statusCode?: number
  /** Marks a rage-click / frustration signal — drives the "rage" badge (backend-pending). */
  rage?: boolean
}

export interface ReplayTimelineResponse {
  items: ReplayTimelineItem[]
  replayStartMs: number
}
