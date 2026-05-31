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

import type {
  CreateDetectionRuleRequest,
  DetectionRuleResponse,
  DetectionRuleType,
  SignalSeverity,
} from '@/lib/api'

// Editable form state. group_by / tags are edited as comma-separated text, then split on submit.
export interface RuleFormState {
  name: string
  description: string
  filter: string
  groupBy: string
  windowSeconds: number
  type: DetectionRuleType
  thresholdCount: string
  severity: SignalSeverity
  signalTitle: string
  signalMessage: string
  tags: string
  enabled: boolean
}

export const DEFAULT_WINDOW_SECONDS = 300

export function emptyRuleForm(): RuleFormState {
  return {
    name: '',
    description: '',
    filter: '',
    groupBy: '',
    windowSeconds: DEFAULT_WINDOW_SECONDS,
    type: 'threshold',
    thresholdCount: '5',
    severity: 'medium',
    signalTitle: '',
    signalMessage: '',
    tags: '',
    enabled: false,
  }
}

/** Hydrates the form from an existing rule for editing. */
export function ruleToForm(rule: DetectionRuleResponse): RuleFormState {
  return {
    name: rule.name,
    description: rule.description,
    filter: rule.filter,
    groupBy: rule.group_by.join(', '),
    windowSeconds: rule.window_seconds,
    type: rule.type ?? 'threshold',
    thresholdCount: rule.threshold_count != null ? String(rule.threshold_count) : '',
    severity: rule.severity ?? 'medium',
    signalTitle: rule.signal_title,
    signalMessage: rule.signal_message,
    tags: rule.tags.join(', '),
    enabled: rule.enabled,
  }
}

function splitList(value: string): string[] {
  return value
    .split(',')
    .map((part) => part.trim())
    .filter((part) => part.length > 0)
}

export type RuleFormValidation =
  | {ok: true; request: CreateDetectionRuleRequest}
  | {ok: false; error: string}

/**
 * Validates the form and builds the create/update body. Threshold rules require a positive
 * threshold_count; new-value rules ignore it. Returns the first validation error otherwise.
 */
export function buildRuleRequest(form: RuleFormState): RuleFormValidation {
  const name = form.name.trim()
  if (!name) return {ok: false, error: 'Name is required'}
  if (!form.filter.trim()) return {ok: false, error: 'Filter is required'}
  // Number(form.windowSeconds) is NaN when the input is cleared; reject non-finite values so a bad
  // window never reaches the API.
  if (!Number.isFinite(form.windowSeconds) || form.windowSeconds <= 0) {
    return {ok: false, error: 'Window must be greater than zero'}
  }

  let thresholdCount: number | null = null
  if (form.type === 'threshold') {
    const parsed = Number(form.thresholdCount)
    if (!Number.isInteger(parsed) || parsed <= 0) {
      return {ok: false, error: 'Threshold count must be a positive integer'}
    }
    thresholdCount = parsed
  }

  return {
    ok: true,
    request: {
      name,
      description: form.description.trim(),
      filter: form.filter.trim(),
      group_by: splitList(form.groupBy),
      window_seconds: form.windowSeconds,
      type: form.type,
      threshold_count: thresholdCount,
      severity: form.severity,
      signal_title: form.signalTitle.trim(),
      signal_message: form.signalMessage.trim(),
      enabled: form.enabled,
      tags: splitList(form.tags),
    },
  }
}
