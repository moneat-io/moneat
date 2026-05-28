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
import type { Feedback, FeedbackDetail } from '../types'

export function feedbackMethods(core: ApiClientCore) {
  const base = core.API_BASE

  return {
    getFeedback: (
      projectId: string | number,
      options: { page?: number; limit?: number; status?: string } = {}
    ) => {
      const params = new URLSearchParams()
      params.set('page', String(options.page ?? 1))
      params.set('limit', String(options.limit ?? 25))
      if (options.status) params.set('status', options.status)
      return core.request<Feedback[]>(
        `${base}/projects/${projectId}/feedback?${params.toString()}`
      )
    },

    getFeedbackDetail: (feedbackId: string) =>
      core.request<FeedbackDetail>(
        `${base}/feedback/${encodeURIComponent(feedbackId)}`
      ),

    updateFeedback: (feedbackId: string, updates: { status?: string }) =>
      core.request<void>(`${base}/feedback/${encodeURIComponent(feedbackId)}`, {
        method: 'PATCH',
        body: JSON.stringify(updates),
      }),
  }
}
