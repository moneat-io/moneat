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

import {beforeEach, describe, expect, it} from 'vitest'
import {http, HttpResponse} from 'msw'
import {api} from '@/lib/api'
import {server} from '@/test/mocks/server'

const API_BASE = 'http://localhost:8080'
const ROLE_ID_OPERATOR = '22222222-2222-4222-8222-222222222222'
const ROLE_ID_RESPONDER = '33333333-3333-4333-8333-333333333333'
const ASSIGNMENT_ID = 'aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa'
const USER_ID = '11111111-1111-4111-8111-111111111111'

describe('RBAC API', () => {
  beforeEach(() => {
    localStorage.clear()
    sessionStorage.clear()
    sessionStorage.setItem('authenticated', 'true')
  })

  it('fetches RBAC roles', async () => {
    const roles = [
      {
        id: ROLE_ID_OPERATOR,
        name: 'Workflow operator',
        permissions: ['workflows:read', 'workflows:run'],
        created_at: '2026-01-01T00:00:00Z',
        updated_at: '2026-01-02T00:00:00Z',
      },
    ]

    server.use(
      http.get(`${API_BASE}/v1/rbac/roles`, () => HttpResponse.json(roles))
    )

    await expect(api.getRbacRoles()).resolves.toEqual(roles)
  })

  it('creates an RBAC role', async () => {
    server.use(
      http.post(`${API_BASE}/v1/rbac/roles`, async ({request}) => {
        const body = (await request.json()) as Record<string, unknown>
        expect(body).toEqual({
          name: 'Responder',
          permissions: ['workflows:read'],
        })
        return HttpResponse.json({
          id: ROLE_ID_RESPONDER,
          name: 'Responder',
          permissions: ['workflows:read'],
          created_at: '2026-01-01T00:00:00Z',
          updated_at: '2026-01-01T00:00:00Z',
        })
      })
    )

    const role = await api.createRbacRole({name: 'Responder', permissions: ['workflows:read']})
    expect(role.id).toBe(ROLE_ID_RESPONDER)
  })

  it('updates and deletes an RBAC role', async () => {
    server.use(
      http.put(`${API_BASE}/v1/rbac/roles/${ROLE_ID_RESPONDER}`, async ({request}) => {
        const body = (await request.json()) as Record<string, unknown>
        expect(body).toEqual({
          name: 'Responder',
          permissions: ['workflows:read', 'workflows:run'],
        })
        return HttpResponse.json({
          id: ROLE_ID_RESPONDER,
          name: 'Responder',
          permissions: ['workflows:read', 'workflows:run'],
          created_at: '2026-01-01T00:00:00Z',
          updated_at: '2026-01-02T00:00:00Z',
        })
      }),
      http.delete(`${API_BASE}/v1/rbac/roles/${ROLE_ID_RESPONDER}`, () => new HttpResponse(null, {status: 204}))
    )

    await expect(
      api.updateRbacRole(ROLE_ID_RESPONDER, {
        name: 'Responder',
        permissions: ['workflows:read', 'workflows:run'],
      })
    ).resolves.toMatchObject({id: ROLE_ID_RESPONDER})
    await expect(api.deleteRbacRole(ROLE_ID_RESPONDER)).resolves.toBeUndefined()
  })

  it('manages RBAC role assignments', async () => {
    const assignment = {
      id: ASSIGNMENT_ID,
      role_id: ROLE_ID_RESPONDER,
      user_id: USER_ID,
      created_at: '2026-01-01T00:00:00Z',
    }

    server.use(
      http.get(`${API_BASE}/v1/rbac/roles/${ROLE_ID_RESPONDER}/assignments`, () => HttpResponse.json([assignment])),
      http.post(`${API_BASE}/v1/rbac/roles/${ROLE_ID_RESPONDER}/assignments`, async ({request}) => {
        const body = (await request.json()) as Record<string, unknown>
        expect(body).toEqual({user_id: USER_ID})
        return HttpResponse.json(assignment)
      }),
      http.delete(
        `${API_BASE}/v1/rbac/roles/${ROLE_ID_RESPONDER}/assignments/${USER_ID}`,
        () => new HttpResponse(null, {status: 204})
      )
    )

    await expect(api.getRbacRoleAssignments(ROLE_ID_RESPONDER)).resolves.toEqual([assignment])
    await expect(api.assignRbacRole(ROLE_ID_RESPONDER, USER_ID)).resolves.toEqual(assignment)
    await expect(api.unassignRbacRole(ROLE_ID_RESPONDER, USER_ID)).resolves.toBeUndefined()
  })
})
