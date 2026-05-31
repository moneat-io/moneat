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

import {describe, expect, it} from 'vitest'
import type {DetectionRuleResponse} from '@/lib/api'
import {buildRuleRequest, emptyRuleForm, ruleToForm} from '../ruleFormState'

function makeForm(overrides = {}) {
  return {
    ...emptyRuleForm(),
    name: 'Failed logins',
    filter: 'status:failed',
    ...overrides,
  }
}

describe('emptyRuleForm', () => {
  it('defaults to a disabled threshold rule with a 300s window', () => {
    const form = emptyRuleForm()
    expect(form.type).toBe('threshold')
    expect(form.windowSeconds).toBe(300)
    expect(form.enabled).toBe(false)
  })
})

describe('buildRuleRequest', () => {
  it('requires a name', () => {
    const result = buildRuleRequest(makeForm({name: '  '}))
    expect(result).toEqual({ok: false, error: 'Name is required'})
  })

  it('requires a filter', () => {
    const result = buildRuleRequest(makeForm({filter: ''}))
    expect(result).toEqual({ok: false, error: 'Filter is required'})
  })

  it('requires a positive window', () => {
    const result = buildRuleRequest(makeForm({windowSeconds: 0}))
    expect(result).toEqual({ok: false, error: 'Window must be greater than zero'})
  })

  it('rejects a non-finite window (cleared input yields NaN)', () => {
    const result = buildRuleRequest(makeForm({windowSeconds: Number.NaN}))
    expect(result).toEqual({ok: false, error: 'Window must be greater than zero'})
  })

  it('requires a positive integer threshold for threshold rules', () => {
    expect(buildRuleRequest(makeForm({thresholdCount: '0'})).ok).toBe(false)
    expect(buildRuleRequest(makeForm({thresholdCount: 'abc'})).ok).toBe(false)
  })

  it('splits comma-separated group_by and tags', () => {
    const result = buildRuleRequest(
      makeForm({groupBy: 'user.id, source.ip ,', tags: 'auth, brute-force', thresholdCount: '5'})
    )
    expect(result.ok).toBe(true)
    if (result.ok) {
      expect(result.request.group_by).toEqual(['user.id', 'source.ip'])
      expect(result.request.tags).toEqual(['auth', 'brute-force'])
      expect(result.request.threshold_count).toBe(5)
    }
  })

  it('omits threshold_count for new_value rules', () => {
    const result = buildRuleRequest(makeForm({type: 'new_value', thresholdCount: ''}))
    expect(result.ok).toBe(true)
    if (result.ok) {
      expect(result.request.type).toBe('new_value')
      expect(result.request.threshold_count).toBeNull()
    }
  })
})

describe('ruleToForm', () => {
  it('hydrates editable text fields from a rule', () => {
    const rule: DetectionRuleResponse = {
      id: 1,
      name: 'Brute force',
      description: 'desc',
      source: 'logs',
      filter: 'status:failed',
      group_by: ['user.id', 'source.ip'],
      window_seconds: 600,
      type: 'threshold',
      threshold_count: 10,
      severity: 'high',
      signal_title: 'title',
      signal_message: 'message',
      suppressions: [],
      enabled: true,
      tags: ['auth'],
      created_at: '2026-05-30T00:00:00Z',
      updated_at: '2026-05-30T00:00:00Z',
    }
    const form = ruleToForm(rule)
    expect(form.groupBy).toBe('user.id, source.ip')
    expect(form.thresholdCount).toBe('10')
    expect(form.tags).toBe('auth')
    expect(form.enabled).toBe(true)

    // Round-trips back into a request unchanged.
    const built = buildRuleRequest(form)
    expect(built.ok).toBe(true)
    if (built.ok) expect(built.request.group_by).toEqual(['user.id', 'source.ip'])
  })
})
