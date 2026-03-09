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
  Event,
  Issue,
  IssueDetail,
  IssueTransaction,
} from '../types'

export function issuesMethods(core: ApiClientCore) {
  const base = core.API_BASE

  return {
    getIssues: (
      projectId: number,
      page = 1,
      limit = 25,
      status?: string
    ) => {
      const params = new URLSearchParams({
        page: String(page),
        limit: String(limit),
      })
      if (status) params.set('status', status)
      return core.request<Issue[]>(
        `${base}/projects/${projectId}/issues?${params.toString()}`
      )
    },

    getIssue: (issueId: string) =>
      core.request<IssueDetail>(`${base}/issues/${issueId}`),

    getIssueEvents: (issueId: string, limit = 50) =>
      core.request<Event[]>(
        `${base}/issues/${issueId}/events?limit=${limit}`
      ),

    getIssueTransactions: (issueId: string, limit = 20) =>
      core.request<IssueTransaction[]>(
        `${base}/issues/${issueId}/transactions?limit=${limit}`
      ),

    updateIssue: (
      issueId: string,
      updates: {
        status?: string
        substatus?: string
        statusDetail?: Record<string, string>
      }
    ) =>
      core.request(`${base}/issues/${issueId}`, {
        method: 'PATCH',
        body: JSON.stringify(updates),
      }),

    getIssueIdForEvent: (eventId: string) =>
      core.request<{ issueId: string } | null>(
        `${base}/events/${encodeURIComponent(eventId)}/issue-id`
      ),
  }
}
