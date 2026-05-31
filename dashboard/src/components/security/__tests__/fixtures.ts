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

import type {DetectionRuleResponse, SignalDetailResponse, SignalResponse} from '@/lib/api'

export function makeSignal(overrides: Partial<SignalResponse> = {}): SignalResponse {
  return {
    id: 1,
    source: 'agent_runtime',
    rule_id: 'rule-1',
    rule_name: 'Repeated failed logins',
    severity: 'high',
    status: 'open',
    archive_reason: null,
    dedup_key: 'k',
    entities: {'user.id': 'alice'},
    sample_count: 7,
    assignee_user_id: null,
    tags: ['auth'],
    first_seen: '2026-05-30T00:00:00Z',
    last_seen: '2026-05-30T01:00:00Z',
    created_at: '2026-05-30T00:00:00Z',
    updated_at: '2026-05-30T01:00:00Z',
    ...overrides,
  }
}

export function makeSignalDetail(overrides: Partial<SignalDetailResponse> = {}): SignalDetailResponse {
  return {
    signal: makeSignal(overrides.signal),
    evidence: [{id: 1, evidence_type: 'logs', reference: 'ref', created_at: '2026-05-30T00:00:00Z'}],
    audit: [],
    sample_events: [{message: 'login failed', user: 'alice'}],
    ...overrides,
  }
}

export function makeRule(overrides: Partial<DetectionRuleResponse> = {}): DetectionRuleResponse {
  return {
    id: 1,
    name: 'Brute force',
    description: '',
    source: 'logs',
    filter: 'status:failed',
    group_by: ['user.id'],
    window_seconds: 300,
    type: 'threshold',
    threshold_count: 5,
    severity: 'high',
    signal_title: 'Brute force',
    signal_message: '',
    suppressions: [],
    enabled: false,
    tags: [],
    created_at: '2026-05-30T00:00:00Z',
    updated_at: '2026-05-30T02:00:00Z',
    ...overrides,
  }
}
