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
import { urlWithQuery } from '../utils'
import type {
  CreateDetectionRuleRequest,
  DetectionPreviewResponse,
  DetectionRuleListResponse,
  DetectionRuleResponse,
  SignalDetailResponse,
  SignalListParams,
  SignalListResponse,
  TriageRequest,
  UpdateDetectionRuleRequest,
} from '../types'

function signalsQuery(params: SignalListParams): string {
  const qs = new URLSearchParams()
  if (params.status) qs.set('status', params.status)
  if (params.severity) qs.set('severity', params.severity)
  if (params.source) qs.set('source', params.source)
  if (params.limit !== undefined) qs.set('limit', String(params.limit))
  if (params.offset !== undefined) qs.set('offset', String(params.offset))
  return qs.toString()
}

export function securityMethods(core: ApiClientCore) {
  const base = core.API_BASE
  const signals = `${base}/security/signals`
  const rules = `${base}/security/detection/rules`

  return {
    // --- Signals (triage surface) ---
    listSignals: (params: SignalListParams = {}) =>
      core.request<SignalListResponse>(urlWithQuery(signals, signalsQuery(params))),

    getSignal: (signalId: number) =>
      core.request<SignalDetailResponse>(`${signals}/${signalId}`),

    triageSignal: (signalId: number, request: TriageRequest) =>
      core.request<SignalDetailResponse['signal']>(`${signals}/${signalId}`, {
        method: 'PATCH',
        body: JSON.stringify(request),
      }),

    // --- Detection rules ---
    listDetectionRules: () =>
      core.request<DetectionRuleListResponse>(rules),

    getDetectionRule: (ruleId: number) =>
      core.request<DetectionRuleResponse>(`${rules}/${ruleId}`),

    createDetectionRule: (request: CreateDetectionRuleRequest) =>
      core.request<DetectionRuleResponse>(rules, {
        method: 'POST',
        body: JSON.stringify(request),
      }),

    updateDetectionRule: (ruleId: number, request: UpdateDetectionRuleRequest) =>
      core.request<DetectionRuleResponse>(`${rules}/${ruleId}`, {
        method: 'PUT',
        body: JSON.stringify(request),
      }),

    deleteDetectionRule: (ruleId: number) =>
      core.request<void>(`${rules}/${ruleId}`, {
        method: 'DELETE',
      }),

    previewDetectionRule: (ruleId: number) =>
      core.request<DetectionPreviewResponse>(`${rules}/${ruleId}/preview`, {
        method: 'POST',
      }),
  }
}
