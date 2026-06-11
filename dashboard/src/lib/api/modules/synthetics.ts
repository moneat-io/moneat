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
  SyntheticTestResponse,
  SyntheticResultListResponse,
  SyntheticTestSummary,
  SyntheticVariableResponse,
  SyntheticVariableRequest,
  CreateSyntheticTestPayload,
  UpdateSyntheticTestPayload,
  SyntheticRunResponse,
  LocationSummary,
  SyntheticLocationResponse,
  CreatePrivateLocationRequest,
  CreatePrivateLocationResponse,
} from '../types'

export function syntheticsMethods(core: ApiClientCore) {
  const base = core.API_BASE

  return {
    listSyntheticTests: () =>
      core.request<SyntheticTestResponse[]>(`${base}/synthetics/tests`),

    createSyntheticTest: (request: CreateSyntheticTestPayload) =>
      core.request<SyntheticTestResponse>(`${base}/synthetics/tests`, {
        method: 'POST',
        body: JSON.stringify(request),
      }),

    updateSyntheticTest: (
      testId: string,
      request: UpdateSyntheticTestPayload
    ) =>
      core.request<SyntheticTestResponse>(
        `${base}/synthetics/tests/${testId}`,
        {
          method: 'PUT',
          body: JSON.stringify(request),
        }
      ),

    deleteSyntheticTest: (testId: string) =>
      core.request<void>(`${base}/synthetics/tests/${testId}`, {
        method: 'DELETE',
      }),

    runSyntheticTest: (testId: string) =>
      core.request<void>(`${base}/synthetics/tests/${testId}/run`, {
        method: 'POST',
      }),

    listSyntheticResults: (limit = 50) =>
      core.request<SyntheticResultListResponse>(
        `${base}/synthetics/results?limit=${limit}`
      ),

    getSyntheticTest: (testId: string) =>
      core.request<SyntheticTestResponse>(`${base}/synthetics/tests/${testId}`),

    getSyntheticTestResults: (testId: string, limit = 100) =>
      core.request<SyntheticResultListResponse>(
        `${base}/synthetics/tests/${testId}/results?limit=${limit}`
      ),

    getSyntheticTestSummary: (testId: string) =>
      core.request<SyntheticTestSummary>(
        `${base}/synthetics/tests/${testId}/summary`
      ),

    listSyntheticVariables: () =>
      core.request<SyntheticVariableResponse[]>(`${base}/synthetics/variables`),

    createSyntheticVariable: (request: SyntheticVariableRequest) =>
      core.request<SyntheticVariableResponse>(`${base}/synthetics/variables`, {
        method: 'POST',
        body: JSON.stringify(request),
      }),

    updateSyntheticVariable: (
      id: string,
      request: SyntheticVariableRequest
    ) =>
      core.request<SyntheticVariableResponse>(
        `${base}/synthetics/variables/${encodeURIComponent(id)}`,
        {
          method: 'PUT',
          body: JSON.stringify(request),
        }
      ),

    deleteSyntheticVariable: (id: string) =>
      core.request<void>(
        `${base}/synthetics/variables/${encodeURIComponent(id)}`,
        {
          method: 'DELETE',
        }
      ),

    getSyntheticRunDetail: (testId: string, resultId: string) =>
      core.request<SyntheticRunResponse>(
        `${base}/synthetics/tests/${testId}/results/${resultId}`
      ),

    getSyntheticLocationSummaries: (testId: string) =>
      core.request<LocationSummary[]>(
        `${base}/synthetics/tests/${testId}/locations/summary`
      ),

    previewSyntheticTest: (
      request: CreateSyntheticTestPayload,
      location = ''
    ) =>
      core.request<SyntheticRunResponse>(
        `${base}/synthetics/preview?location=${encodeURIComponent(location)}`,
        {
          method: 'POST',
          body: JSON.stringify(request),
        }
      ),

    listSyntheticLocations: () =>
      core.request<SyntheticLocationResponse[]>(`${base}/synthetics/locations`),

    createSyntheticLocation: (request: CreatePrivateLocationRequest) =>
      core.request<CreatePrivateLocationResponse>(
        `${base}/synthetics/locations`,
        {
          method: 'POST',
          body: JSON.stringify(request),
        }
      ),

    deleteSyntheticLocation: (id: string) =>
      core.request<void>(`${base}/synthetics/locations/${id}`, {
        method: 'DELETE',
      }),

    /** URL for a captured browser-step screenshot (org-scoped by key prefix). */
    syntheticScreenshotUrl: (key: string) =>
      `${base}/synthetics/screenshots/${key}`,
  }
}
