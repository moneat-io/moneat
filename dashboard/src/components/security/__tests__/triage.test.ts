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
import {
  ARCHIVE_REQUIRES_REASON_MESSAGE,
  availableStatusActions,
  buildStatusChangeRequest,
  isTransitionAllowed,
  transitionRequiresReason,
} from '../triage'

describe('availableStatusActions', () => {
  it('offers review and archive from open', () => {
    expect(availableStatusActions('open').map((a) => a.to)).toEqual(['under_review', 'archived'])
  })

  it('offers only archive from under_review', () => {
    expect(availableStatusActions('under_review').map((a) => a.to)).toEqual(['archived'])
  })

  it('offers only reopen from archived', () => {
    const actions = availableStatusActions('archived')
    expect(actions.map((a) => a.to)).toEqual(['open'])
    expect(actions[0].requiresReason).toBe(false)
  })
})

describe('isTransitionAllowed', () => {
  it('allows the forward and reopen moves', () => {
    expect(isTransitionAllowed('open', 'under_review')).toBe(true)
    expect(isTransitionAllowed('open', 'archived')).toBe(true)
    expect(isTransitionAllowed('under_review', 'archived')).toBe(true)
    expect(isTransitionAllowed('archived', 'open')).toBe(true)
  })

  it('rejects illegal and no-op moves', () => {
    expect(isTransitionAllowed('under_review', 'open')).toBe(false)
    expect(isTransitionAllowed('archived', 'under_review')).toBe(false)
    expect(isTransitionAllowed('open', 'open')).toBe(false)
  })
})

describe('transitionRequiresReason', () => {
  it('requires a reason only for archive', () => {
    expect(transitionRequiresReason('archived')).toBe(true)
    expect(transitionRequiresReason('under_review')).toBe(false)
    expect(transitionRequiresReason('open')).toBe(false)
  })
})

describe('buildStatusChangeRequest', () => {
  it('builds a minimal review request', () => {
    const result = buildStatusChangeRequest({current: 'open', target: 'under_review'})
    expect(result).toEqual({ok: true, request: {status: 'under_review'}})
  })

  it('rejects archive without a reason', () => {
    const result = buildStatusChangeRequest({current: 'open', target: 'archived'})
    expect(result).toEqual({ok: false, error: ARCHIVE_REQUIRES_REASON_MESSAGE})
  })

  it('includes the reason and trimmed note when archiving', () => {
    const result = buildStatusChangeRequest({
      current: 'under_review',
      target: 'archived',
      reason: 'false_positive',
      note: '  noisy rule  ',
    })
    expect(result).toEqual({
      ok: true,
      request: {status: 'archived', reason: 'false_positive', note: 'noisy rule'},
    })
  })

  it('omits an empty note', () => {
    const result = buildStatusChangeRequest({current: 'archived', target: 'open', note: '   '})
    expect(result).toEqual({ok: true, request: {status: 'open'}})
  })

  it('rejects an illegal transition before building a request', () => {
    const result = buildStatusChangeRequest({current: 'archived', target: 'under_review'})
    expect(result.ok).toBe(false)
    if (!result.ok) expect(result.error).toMatch(/Cannot move signal/)
  })
})
