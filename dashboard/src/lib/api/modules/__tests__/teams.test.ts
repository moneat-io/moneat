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

import { describe, it, expect, beforeEach } from 'vitest'
import { http, HttpResponse } from 'msw'
import { server } from '@/test/mocks/server'
import { api } from '@/lib/api'

const API_BASE = 'http://localhost:8080/v1'
const TEAM_ID = '11111111-1111-4111-8111-111111111111'
const SCHEDULE_ID = '22222222-2222-4222-8222-222222222222'
const POLICY_ID = '33333333-3333-4333-8333-333333333333'
const MEMBER_ID = '44444444-4444-4444-8444-444444444444'

describe('Organization Teams API', () => {
  beforeEach(() => {
    localStorage.clear()
    sessionStorage.clear()
    sessionStorage.setItem('authenticated', 'true')
  })

  it('fetches organization teams', async () => {
    const mockTeams = [
      {
        id: TEAM_ID,
        name: 'Payments',
        slug: 'payments',
        description: 'Owns checkout',
        slack: '#payments',
        repo: 'moneat-io/payments',
        onCallScheduleId: SCHEDULE_ID,
        escalationPolicyId: POLICY_ID,
        currentOnCall: { userId: MEMBER_ID, userName: 'Dana' },
        members: [{ userId: MEMBER_ID, email: 'dana@example.com', name: 'Dana' }],
      },
    ]

    server.use(
      http.get(`${API_BASE}/org/teams`, () => HttpResponse.json(mockTeams))
    )

    const result = await api.getOrganizationTeams()
    expect(result).toEqual(mockTeams)
  })

  it('creates an organization team', async () => {
    const mockTeam = {
      id: TEAM_ID,
      name: 'Payments',
      slug: 'payments',
      members: [],
    }

    server.use(
      http.post(`${API_BASE}/org/teams`, async ({ request }) => {
        const body = (await request.json()) as Record<string, unknown>
        expect(body.name).toBe('Payments')
        expect(body.slack).toBe('#payments')
        expect(body.onCallScheduleId).toBe(SCHEDULE_ID)
        expect(body.memberIds).toEqual([MEMBER_ID])
        return HttpResponse.json(mockTeam, { status: 201 })
      })
    )

    const result = await api.createOrganizationTeam({
      name: 'Payments',
      slack: '#payments',
      onCallScheduleId: SCHEDULE_ID,
      escalationPolicyId: POLICY_ID,
      memberIds: [MEMBER_ID],
    })
    expect(result).toEqual(mockTeam)
  })

  it('updates an organization team', async () => {
    const mockTeam = {
      id: TEAM_ID,
      name: 'Payments Core',
      slug: 'payments',
      members: [],
    }

    server.use(
      http.put(`${API_BASE}/org/teams/${TEAM_ID}`, async ({ request }) => {
        const body = (await request.json()) as Record<string, unknown>
        expect(body.name).toBe('Payments Core')
        expect(body.escalationPolicyId).toBeNull()
        return HttpResponse.json(mockTeam)
      })
    )

    const result = await api.updateOrganizationTeam(TEAM_ID, {
      name: 'Payments Core',
      escalationPolicyId: null,
    })
    expect(result).toEqual(mockTeam)
  })

  it('deletes an organization team', async () => {
    let called = false
    server.use(
      http.delete(`${API_BASE}/org/teams/${TEAM_ID}`, () => {
        called = true
        return new HttpResponse(null, { status: 204 })
      })
    )

    await api.deleteOrganizationTeam(TEAM_ID)
    expect(called).toBe(true)
  })
})
