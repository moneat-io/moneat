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

  it('getIssue with projectId appends projectId to query', async () => {
    const mockIssue = { id: 'iss-2', projectId: 5, title: 'Test', status: 'unresolved' }

    server.use(
      http.get(`${API_BASE}/v1/issues/iss-2`, ({ request }) => {
        const url = new URL(request.url)
        expect(url.searchParams.get('projectId')).toBe('5')
        return HttpResponse.json(mockIssue)
      })
    )

    const result = await api.getIssue('iss-2', 5)
    expect(result.projectId).toBe(5)
  })

  it('getIssueEvents with projectId appends projectId to query', async () => {
    const mockEvents = [{ eventId: 'evt-1', message: 'Error' }]

    server.use(
      http.get(`${API_BASE}/v1/issues/iss-1/events`, ({ request }) => {
        const url = new URL(request.url)
        expect(url.searchParams.get('projectId')).toBe('3')
        return HttpResponse.json(mockEvents)
      })
    )

    const result = await api.getIssueEvents('iss-1', 50, 3)
    expect(result).toHaveLength(1)
  })

  it('getIssueTransactions with projectId appends projectId to query', async () => {
    const mockTx = [{ eventId: 'tx-1', name: 'GET /api' }]

    server.use(
      http.get(`${API_BASE}/v1/issues/iss-1/transactions`, ({ request }) => {
        const url = new URL(request.url)
        expect(url.searchParams.get('projectId')).toBe('2')
        return HttpResponse.json(mockTx)
      })
    )

    const result = await api.getIssueTransactions('iss-1', 20, 2)
    expect(result).toHaveLength(1)
  })

  it('updateIssue with projectId appends projectId to query', async () => {
    server.use(
      http.patch(`${API_BASE}/v1/issues/iss-1`, ({ request }) => {
        const url = new URL(request.url)
        expect(url.searchParams.get('projectId')).toBe('7')
        return new HttpResponse(null, { status: 204 })
      })
    )

    await api.updateIssue('iss-1', { status: 'resolved' }, 7)
  })
})
