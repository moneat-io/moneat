import { describe, it, expect, beforeEach } from 'vitest'
import { http, HttpResponse } from 'msw'
import { server } from '@/test/mocks/server'
import { api } from '@/lib/api'

const API_BASE = 'http://localhost:8080'

describe('Issues API - extended coverage', () => {
  beforeEach(() => {
    localStorage.clear()
    sessionStorage.clear()
    sessionStorage.setItem('authenticated', 'true')
  })

  it('fetches issue transactions with custom limit', async () => {
    const mockTransactions = [{ eventId: 'tx-1', name: 'GET /api' }]

    server.use(
      http.get(`${API_BASE}/v1/issues/iss-1/transactions`, ({ request }) => {
        const url = new URL(request.url)
        expect(url.searchParams.get('limit')).toBe('50')
        return HttpResponse.json(mockTransactions)
      })
    )

    const result = await api.getIssueTransactions('iss-1', 50)
    expect(result).toEqual(mockTransactions)
  })
})
