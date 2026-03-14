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

describe('Debugger API', () => {
  beforeEach(() => {
    localStorage.clear()
    sessionStorage.clear()
    sessionStorage.setItem('authenticated', 'true')
  })

  // ──── getDebuggerProbes ────

  it('fetches debugger probes', async () => {
    const mockResponse = {
      probes: [
        { id: 'probe-1', type: 'LOG', file: 'Main.kt', line: 42, active: true },
      ],
    }

    server.use(
      http.get(`${API_BASE}/v1/infra/debugger/probes`, () => {
        return HttpResponse.json(mockResponse)
      })
    )

    const result = await api.getDebuggerProbes()
    expect(result).toEqual(mockResponse)
  })

  // ──── createDebuggerProbe ────

  it('creates a debugger probe', async () => {
    const request = { type: 'LOG', file: 'Main.kt', line: 42, template: 'x={x}' }
    const mockProbe = { id: 'probe-new', ...request, active: true }

    server.use(
      http.post(`${API_BASE}/v1/infra/debugger/probes`, async ({ request: req }) => {
        const body = (await req.json()) as Record<string, unknown>
        expect(body.type).toBe('LOG')
        expect(body.file).toBe('Main.kt')
        expect(body.line).toBe(42)
        return HttpResponse.json(mockProbe)
      })
    )

    const result = await api.createDebuggerProbe(request)
    expect(result).toEqual(mockProbe)
  })

  // ──── updateDebuggerProbe ────

  it('updates a debugger probe', async () => {
    const updateReq = { active: false }
    const mockProbe = { id: 'probe-1', type: 'LOG', file: 'Main.kt', line: 42, active: false }

    server.use(
      http.put(`${API_BASE}/v1/infra/debugger/probes/probe-1`, async ({ request }) => {
        const body = (await request.json()) as Record<string, unknown>
        expect(body.active).toBe(false)
        return HttpResponse.json(mockProbe)
      })
    )

    const result = await api.updateDebuggerProbe('probe-1', updateReq)
    expect(result).toEqual(mockProbe)
  })

  // ──── deleteDebuggerProbe ────

  it('deletes a debugger probe', async () => {
    server.use(
      http.delete(`${API_BASE}/v1/infra/debugger/probes/probe-1`, () => {
        return new HttpResponse(null, { status: 204 })
      })
    )

    await api.deleteDebuggerProbe('probe-1')
  })
})
