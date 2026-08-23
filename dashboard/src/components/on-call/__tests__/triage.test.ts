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
import type {OnCallIncident, OnCallIncidentStatus} from '@/lib/api/types'
import {
  buildAcceptInput,
  buildDeclineInput,
  buildMergeInput,
  canAcceptIncident,
  canDeclineIncident,
  canMergeIncident,
  describeIncidentActionError,
  eligibleMergeTargets,
} from '../triage'

const incident = (id: string, status: OnCallIncidentStatus, version = 1): OnCallIncident => ({
  id,
  organizationId: 'organization',
  title: id,
  status,
  version,
  declaredBy: 'user',
  declaredAt: '2026-01-01T00:00:00Z',
  alertCount: 0,
  createdAt: '2026-01-01T00:00:00Z',
  updatedAt: '2026-01-01T00:00:00Z',
})

describe('incident triage commands', () => {
  it('exposes only lifecycle-valid actions and merge targets', () => {
    expect(canAcceptIncident('TRIAGE')).toBe(true)
    expect(canDeclineIncident('ACTIVE')).toBe(false)
    expect(canMergeIncident('ACTIVE')).toBe(true)
    expect(eligibleMergeTargets([
      incident('source', 'TRIAGE'),
      incident('active', 'ACTIVE'),
      incident('declined', 'DECLINED'),
    ], 'source').map((item) => item.id)).toEqual(['active'])
  })

  it('preserves optimistic-concurrency versions and trims optional input', () => {
    expect(buildAcceptInput({severity: ' SEV-1 ', incidentTypeId: ' ', expectedVersion: 4}))
      .toEqual({severity: 'SEV-1', expectedVersion: 4})
    expect(buildDeclineInput({reason: ' false positive ', expectedVersion: 5}))
      .toEqual({reason: 'false positive', expectedVersion: 5})
    expect(buildMergeInput(incident('target', 'ACTIVE', 6), incident('source', 'TRIAGE')))
      .toEqual({sourceIncidentId: 'source', expectedVersion: 6})
  })

  it('turns stale conflicts into actionable responder guidance', () => {
    const error = Object.assign(new Error(''), {status: 409})
    expect(describeIncidentActionError(error)).toEqual({
      isConflict: true,
      message: expect.stringMatching(/refreshed.*try again/i),
    })
  })
})
