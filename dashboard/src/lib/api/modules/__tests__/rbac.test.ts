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

describe('RBAC API', () => {
  beforeEach(() => {
    localStorage.clear()
    sessionStorage.clear()
    sessionStorage.setItem('authenticated', 'true')
  })

  it('fetches RBAC roles', async () => {
    const roles = [
      {
        id: 1,
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
          id: 2,
          name: 'Responder',
          permissions: ['workflows:read'],
          created_at: '2026-01-01T00:00:00Z',
          updated_at: '2026-01-01T00:00:00Z',
        })
      })
    )

    const role = await api.createRbacRole({name: 'Responder', permissions: ['workflows:read']})
    expect(role.id).toBe(2)
  })

  it('updates and deletes an RBAC role', async () => {
    server.use(
      http.put(`${API_BASE}/v1/rbac/roles/2`, async ({request}) => {
        const body = (await request.json()) as Record<string, unknown>
        expect(body).toEqual({
          name: 'Responder',
          permissions: ['workflows:read', 'workflows:run'],
        })
        return HttpResponse.json({
          id: 2,
          name: 'Responder',
          permissions: ['workflows:read', 'workflows:run'],
          created_at: '2026-01-01T00:00:00Z',
          updated_at: '2026-01-02T00:00:00Z',
        })
      }),
      http.delete(`${API_BASE}/v1/rbac/roles/2`, () => new HttpResponse(null, {status: 204}))
    )

    await expect(
      api.updateRbacRole(2, {
        name: 'Responder',
        permissions: ['workflows:read', 'workflows:run'],
      })
    ).resolves.toMatchObject({id: 2})
    await expect(api.deleteRbacRole(2)).resolves.toBeUndefined()
  })

  it('manages RBAC role assignments', async () => {
    const assignment = {
      id: 10,
      role_id: 2,
      user_id: 42,
      created_at: '2026-01-01T00:00:00Z',
    }

    server.use(
      http.get(`${API_BASE}/v1/rbac/roles/2/assignments`, () => HttpResponse.json([assignment])),
      http.post(`${API_BASE}/v1/rbac/roles/2/assignments`, async ({request}) => {
        const body = (await request.json()) as Record<string, unknown>
        expect(body).toEqual({user_id: 42})
        return HttpResponse.json(assignment)
      }),
      http.delete(`${API_BASE}/v1/rbac/roles/2/assignments/42`, () => new HttpResponse(null, {status: 204}))
    )

    await expect(api.getRbacRoleAssignments(2)).resolves.toEqual([assignment])
    await expect(api.assignRbacRole(2, 42)).resolves.toEqual(assignment)
    await expect(api.unassignRbacRole(2, 42)).resolves.toBeUndefined()
  })
})
