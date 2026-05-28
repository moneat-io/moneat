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

import type { ApiClientCore } from '../client'
import type {
  Replay,
  ReplayDetail,
  ReplayRecordingResponse,
  ReplayTimelineResponse,
} from '../types'

export function replaysMethods(core: ApiClientCore) {
  const base = core.API_BASE

  return {
    getReplays: (
      projectId: string | number,
      options: {
        page?: number
        limit?: number
        environment?: string
        period?: '24h' | '7d' | '30d' | '90d'
      } = {}
    ) => {
      const params = new URLSearchParams()
      params.set('page', String(options.page ?? 1))
      params.set('limit', String(options.limit ?? 25))
      params.set('period', options.period ?? '7d')
      if (options.environment) params.set('environment', options.environment)
      return core.request<Replay[]>(`${base}/projects/${projectId}/replays?${params.toString()}`)
    },

    getReplay: (replayId: string) =>
      core.request<ReplayDetail>(`${base}/replays/${encodeURIComponent(replayId)}`),

    getReplayRecording: (replayId: string) =>
      core.request<ReplayRecordingResponse>(
        `${base}/replays/${encodeURIComponent(replayId)}/recording`
      ),

    getReplayTimeline: (replayId: string) =>
      core.request<ReplayTimelineResponse>(
        `${base}/replays/${encodeURIComponent(replayId)}/timeline`
      ),

    getIssueIdForEvent: async (eventId: string): Promise<string | null> => {
      try {
        const response = await core.request<{ issueId: string }>(
          `${base}/events/${encodeURIComponent(eventId)}/issue`
        )
        return response.issueId
      } catch {
        return null
      }
    },

    getReplaysForIssue: (issueId: string, limit = 10, projectId?: string | number | null) => {
      const params = new URLSearchParams({ limit: String(limit) })
      if (projectId != null) params.set('projectId', String(projectId))
      return core.request<Replay[]>(
        `${base}/issues/${encodeURIComponent(issueId)}/replays?${params.toString()}`
      )
    },
  }
}
