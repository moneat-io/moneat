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

import { describe, it, expect } from 'vitest'
import { http, HttpResponse } from 'msw'
import { server } from '@/test/mocks/server'
import { api } from '@/lib/api'

const API_BASE = 'http://localhost:8080'

describe('Contact API', () => {
  it('posts an enterprise sales inquiry', async () => {
    const mockResponse = { message: 'Thanks — our sales team will be in touch shortly.' }

    server.use(
      http.post(`${API_BASE}/v1/contact/sales`, async ({ request }) => {
        const body = (await request.json()) as Record<string, unknown>
        expect(body.name).toBe('Ada Lovelace')
        expect(body.email).toBe('ada@acme.com')
        expect(body.company).toBe('Acme Corp')
        expect(body.message).toBe('We need a dedicated SLA.')
        return HttpResponse.json(mockResponse)
      })
    )

    const result = await api.createSalesInquiry({
      name: 'Ada Lovelace',
      email: 'ada@acme.com',
      company: 'Acme Corp',
      message: 'We need a dedicated SLA.',
    })
    expect(result).toEqual(mockResponse)
  })
})
