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
  IssueListParams,
  IssueTransaction,
} from '../types'
import { urlWithQuery } from '../utils'

function setCsvParam(
  params: URLSearchParams,
  key: string,
  values: readonly string[] | undefined
) {
  const csv = values
    ?.map((value) => String(value).trim())
    .filter((value) => value.length > 0)
    .join(',')
  if (csv) params.set(key, csv)
}

export function issuesMethods(core: ApiClientCore) {
  const base = core.API_BASE

  return {
    getOrganizationIssues: (query: IssueListParams = {}) => {
      const params = new URLSearchParams({
        page: String(query.page ?? 1),
        limit: String(query.limit ?? 25),
      })
      if (query.status) params.set('status', query.status)
      setCsvParam(params, 'services', query.services)
      setCsvParam(params, 'serviceIds', query.serviceIds)
      return core.request<Issue[]>(urlWithQuery(`${base}/issues`, params.toString()))
    },

    getIssues: (
      projectId: string,
      page = 1,
      limit = 25,
      status?: string
    ) => {
      const params = new URLSearchParams({
        page: String(page),
        limit: String(limit),
      })
      if (status) params.set('status', status)
      const path = `${base}/projects/${encodeURIComponent(String(projectId))}/issues`
      return core.request<Issue[]>(
        urlWithQuery(path, params.toString())
      )
    },

    getIssue: (issueId: string, projectId?: string | null) => {
      const params = new URLSearchParams()
      if (projectId != null) params.set('projectId', String(projectId))
      const path = `${base}/issues/${encodeURIComponent(issueId)}`
      return core.request<IssueDetail>(urlWithQuery(path, params.toString()))
    },

    getIssueEvents: (issueId: string, limit = 50, projectId?: string | null) => {
      const params = new URLSearchParams({ limit: String(limit) })
      if (projectId != null) params.set('projectId', String(projectId))
      return core.request<Event[]>(
        `${base}/issues/${encodeURIComponent(issueId)}/events?${params.toString()}`
      )
    },

    getIssueTransactions: (issueId: string, limit = 20, projectId?: string | null) => {
      const params = new URLSearchParams({ limit: String(limit) })
      if (projectId != null) params.set('projectId', String(projectId))
      return core.request<IssueTransaction[]>(
        `${base}/issues/${encodeURIComponent(issueId)}/transactions?${params.toString()}`
      )
    },

    updateIssue: (
      issueId: string,
      updates: {
        status?: string
        substatus?: string
        statusDetail?: Record<string, string>
      },
      projectId?: string | null
    ) => {
      const params = new URLSearchParams()
      if (projectId != null) params.set('projectId', String(projectId))
      const path = `${base}/issues/${encodeURIComponent(issueId)}`
      return core.request(urlWithQuery(path, params.toString()), {
        method: 'PATCH',
        body: JSON.stringify(updates),
      })
    },

    getIssueIdForEvent: (eventId: string) =>
      core.request<{ issueId: string } | null>(
        `${base}/events/${encodeURIComponent(eventId)}/issue-id`
      ),
  }
}
