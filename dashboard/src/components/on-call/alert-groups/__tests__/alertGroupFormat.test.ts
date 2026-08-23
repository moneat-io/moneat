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
import type {AlertGroupMember} from '@/lib/api/types'
import {activeMembers, groupIncidentLinkage, relativeWindow} from '../alertGroupFormat'

const member = (state: AlertGroupMember['state']): AlertGroupMember => ({
  id: `${state}-member`,
  episodeId: `${state}-episode`,
  state,
  version: 1,
  pagingState: 'NOT_REQUIRED',
  firstJoinedAt: '2026-01-01T00:00:00Z',
  lastSeenAt: '2026-01-01T00:00:00Z',
})

describe('alert group presentation helpers', () => {
  it('counts only active members', () => {
    expect(activeMembers({members: [member('ACTIVE'), member('REMOVED'), member('UNRELATED')]}))
      .toHaveLength(1)
  })

  it('prefers a confirmed incident over a candidate', () => {
    expect(groupIncidentLinkage({incidentId: 'confirmed', candidateIncidentId: 'candidate'}))
      .toEqual({kind: 'incident', incidentId: 'confirmed'})
    expect(groupIncidentLinkage({incidentId: null, candidateIncidentId: 'candidate'}))
      .toEqual({kind: 'candidate', incidentId: 'candidate'})
  })

  it('describes both open and elapsed grouping windows', () => {
    const now = Date.parse('2026-01-01T00:00:00Z')
    expect(relativeWindow('2026-01-01T00:05:00Z', now)).toBe('closes in 5m')
    expect(relativeWindow('2025-12-31T23:59:30Z', now)).toBe('closed 30s ago')
  })
})
