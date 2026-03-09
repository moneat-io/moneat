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
  LogIndex,
  CreateLogIndexRequest,
  UpdateLogIndexRequest,
  LogIndexTestResult,
} from '../types'

export function logIndexesMethods(core: ApiClientCore) {
  const base = core.API_BASE

  return {
    getLogIndexes: () =>
      core.request<{ indexes: LogIndex[] }>(`${base}/logs/indexes`),

    createLogIndex: (request: CreateLogIndexRequest) =>
      core.request<LogIndex>(`${base}/logs/indexes`, {
        method: 'POST',
        body: JSON.stringify(request),
      }),

    updateLogIndex: (id: number, request: UpdateLogIndexRequest) =>
      core.request<LogIndex>(`${base}/logs/indexes/${id}`, {
        method: 'PUT',
        body: JSON.stringify(request),
      }),

    deleteLogIndex: (id: number) =>
      core.request<void>(`${base}/logs/indexes/${id}`, {
        method: 'DELETE',
      }),

    testLogIndexFilter: (filterQuery: string) =>
      core.request<LogIndexTestResult>(`${base}/logs/indexes/test`, {
        method: 'POST',
        body: JSON.stringify({ filter_query: filterQuery }),
      }),
  }
}
