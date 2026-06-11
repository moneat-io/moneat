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

const API_BASE = 'http://localhost:8080'
const MEMBER_ID = '11111111-1111-4111-8111-111111111111'
const INVITATION_ID_MEMBER = '22222222-2222-4222-8222-222222222222'
const INVITATION_ID_ADMIN = '33333333-3333-4333-8333-333333333333'
const INVITATION_ID_ACTION = '44444444-4444-4444-8444-444444444444'

describe('Org Members API', () => {
  beforeEach(() => {
    localStorage.clear()
    sessionStorage.clear()
    sessionStorage.setItem('authenticated', 'true')
  })

  // ──── getOrgMembers ────

  it('fetches org members', async () => {
    const mockResponse = {
      members: [{ id: MEMBER_ID, email: 'user@example.com', role: 'admin' }],
      invitations: [],
    }

    server.use(
      http.get(`${API_BASE}/v1/org/members`, () => {
        return HttpResponse.json(mockResponse)
      })
    )

    const result = await api.getOrgMembers()
    expect(result).toEqual(mockResponse)
  })

  // ──── inviteMember ────

  it('invites a member with default role', async () => {
    const mockInvitation = { id: INVITATION_ID_MEMBER, email: 'new@example.com', role: 'member' }

    server.use(
      http.post(`${API_BASE}/v1/org/invitations`, async ({ request }) => {
        const body = (await request.json()) as Record<string, unknown>
        expect(body.email).toBe('new@example.com')
        expect(body.role).toBe('member')
        return HttpResponse.json(mockInvitation)
      })
    )

    const result = await api.inviteMember('new@example.com')
    expect(result).toEqual(mockInvitation)
  })

  it('invites a member with explicit role', async () => {
    const mockInvitation = { id: INVITATION_ID_ADMIN, email: 'admin@example.com', role: 'admin' }

    server.use(
      http.post(`${API_BASE}/v1/org/invitations`, async ({ request }) => {
        const body = (await request.json()) as Record<string, unknown>
        expect(body.email).toBe('admin@example.com')
        expect(body.role).toBe('admin')
        return HttpResponse.json(mockInvitation)
      })
    )

    const result = await api.inviteMember('admin@example.com', 'admin')
    expect(result).toEqual(mockInvitation)
  })

  // ──── bulkInviteMembers ────

  it('bulk invites members', async () => {
    const mockResult = { succeeded: 2, failed: 0, results: [] }

    server.use(
      http.post(`${API_BASE}/v1/org/invitations/bulk`, async ({ request }) => {
        const body = (await request.json()) as Record<string, unknown>
        expect(body.emails).toEqual(['a@example.com', 'b@example.com'])
        expect(body.role).toBe('member')
        return HttpResponse.json(mockResult)
      })
    )

    const result = await api.bulkInviteMembers(['a@example.com', 'b@example.com'])
    expect(result).toEqual(mockResult)
  })

  // ──── updateMemberRole ────

  it('updates a member role', async () => {
    server.use(
      http.put(`${API_BASE}/v1/org/members/${MEMBER_ID}/role`, async ({ request }) => {
        const body = (await request.json()) as Record<string, unknown>
        expect(body.role).toBe('admin')
        return HttpResponse.json({ success: true })
      })
    )

    const result = await api.updateMemberRole(MEMBER_ID, 'admin')
    expect(result.success).toBe(true)
  })

  // ──── removeMember ────

  it('removes a member', async () => {
    server.use(
      http.delete(`${API_BASE}/v1/org/members/${MEMBER_ID}`, () => {
        return HttpResponse.json({ success: true })
      })
    )

    const result = await api.removeMember(MEMBER_ID)
    expect(result.success).toBe(true)
  })

  // ──── revokeInvitation ────

  it('revokes an invitation', async () => {
    server.use(
      http.delete(`${API_BASE}/v1/org/invitations/${INVITATION_ID_ACTION}`, () => {
        return HttpResponse.json({ success: true })
      })
    )

    const result = await api.revokeInvitation(INVITATION_ID_ACTION)
    expect(result.success).toBe(true)
  })

  // ──── resendInvitation ────

  it('resends an invitation', async () => {
    server.use(
      http.post(`${API_BASE}/v1/org/invitations/${INVITATION_ID_ACTION}/resend`, () => {
        return HttpResponse.json({ success: true })
      })
    )

    const result = await api.resendInvitation(INVITATION_ID_ACTION)
    expect(result.success).toBe(true)
  })

  // ──── getInvitationDetails ────

  it('fetches invitation details by token', async () => {
    const mockDetails = {
      email: 'invited@example.com',
      organizationName: 'Acme',
      inviterName: 'Admin',
    }

    server.use(
      http.get(`${API_BASE}/v1/org/invitations/details`, ({ request }) => {
        const url = new URL(request.url)
        expect(url.searchParams.get('token')).toBe('abc-token')
        return HttpResponse.json(mockDetails)
      })
    )

    const result = await api.getInvitationDetails('abc-token')
    expect(result).toEqual(mockDetails)
  })

  // ──── acceptInvitation ────

  it('accepts an invitation', async () => {
    server.use(
      http.post(`${API_BASE}/v1/org/invitations/accept`, async ({ request }) => {
        const body = (await request.json()) as Record<string, unknown>
        expect(body.token).toBe('abc-token')
        return HttpResponse.json({ success: true })
      })
    )

    const result = await api.acceptInvitation('abc-token')
    expect(result.success).toBe(true)
  })
})
