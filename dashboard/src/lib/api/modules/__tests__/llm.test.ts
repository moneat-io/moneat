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

describe('LLM API', () => {
  beforeEach(() => {
    localStorage.clear()
    sessionStorage.clear()
    sessionStorage.setItem('authenticated', 'true')
  })

  // ──── getLlmOverview ────

  it('fetches LLM overview with default range', async () => {
    const mockOverview = { totalGenerations: 50, totalTokens: 10000 }

    server.use(
      http.get(`${API_BASE}/v1/llm/overview`, ({ request }) => {
        const url = new URL(request.url)
        expect(url.searchParams.get('projectId')).toBeNull()
        expect(url.searchParams.get('range')).toBe('24h')
        return HttpResponse.json(mockOverview)
      })
    )

    const result = await api.getLlmOverview()
    expect(result).toEqual(mockOverview)
  })

  it('fetches LLM overview with custom range', async () => {
    const mockOverview = { totalGenerations: 200, totalTokens: 50000 }

    server.use(
      http.get(`${API_BASE}/v1/llm/overview`, ({ request }) => {
        const url = new URL(request.url)
        expect(url.searchParams.get('range')).toBe('7d')
        return HttpResponse.json(mockOverview)
      })
    )

    const result = await api.getLlmOverview({range: '7d'})
    expect(result).toEqual(mockOverview)
  })

  it('fetches LLM overview with service filters', async () => {
    const mockOverview = { totalGenerations: 10, totalTokens: 2000 }

    server.use(
      http.get(`${API_BASE}/v1/llm/overview`, ({ request }) => {
        const url = new URL(request.url)
        expect(url.searchParams.getAll('services')).toEqual(['API', 'Worker'])
        expect(url.searchParams.getAll('serviceIds')).toEqual(['svc-api', 'svc-worker'])
        return HttpResponse.json(mockOverview)
      })
    )

    const result = await api.getLlmOverview({
      services: ['API', 'Worker'],
      serviceIds: ['svc-api', 'svc-worker'],
    })
    expect(result).toEqual(mockOverview)
  })

  // ──── getLlmGenerations ────

  it('fetches LLM generations with filters', async () => {
    const mockGenerations = { generations: [], total: 0 }

    server.use(
      http.get(`${API_BASE}/v1/llm/generations`, ({ request }) => {
        const url = new URL(request.url)
        expect(url.searchParams.get('projectId')).toBeNull()
        expect(url.searchParams.get('model')).toBe('gpt-4')
        expect(url.searchParams.get('provider')).toBe('openai')
        expect(url.searchParams.get('type')).toBe('chat')
        expect(url.searchParams.get('status')).toBe('success')
        expect(url.searchParams.get('page')).toBe('2')
        expect(url.searchParams.get('pageSize')).toBe('25')
        expect(url.searchParams.getAll('services')).toEqual(['API'])
        return HttpResponse.json(mockGenerations)
      })
    )

    const result = await api.getLlmGenerations({
      model: 'gpt-4',
      provider: 'openai',
      type: 'chat',
      status: 'success',
      page: 2,
      pageSize: 25,
      services: ['API'],
    })
    expect(result).toEqual(mockGenerations)
  })

  it('fetches LLM generations with no params', async () => {
    const mockGenerations = { generations: [{ id: 'gen-1' }], total: 1 }

    server.use(
      http.get(`${API_BASE}/v1/llm/generations`, ({ request }) => {
        const url = new URL(request.url)
        expect(url.searchParams.toString()).toBe('')
        return HttpResponse.json(mockGenerations)
      })
    )

    const result = await api.getLlmGenerations()
    expect(result).toEqual(mockGenerations)
  })

  // ──── getLlmGenerationDetail ────

  it('fetches LLM generation detail', async () => {
    const mockDetail = { id: 'gen-1', model: 'gpt-4', prompt: 'Hello' }

    server.use(
      http.get(`${API_BASE}/v1/llm/generations/gen-1`, ({ request }) => {
        const url = new URL(request.url)
        expect(url.searchParams.getAll('services')).toEqual(['API'])
        return HttpResponse.json(mockDetail)
      })
    )

    const result = await api.getLlmGenerationDetail('gen-1', {services: ['API']})
    expect(result).toEqual(mockDetail)
  })

  // ──── getLlmTrace ────

  it('fetches LLM trace', async () => {
    const mockTrace = { traceId: 'trace-1', spans: [] }

    server.use(
      http.get(`${API_BASE}/v1/llm/traces/trace-1`, ({ request }) => {
        const url = new URL(request.url)
        expect(url.searchParams.get('projectId')).toBeNull()
        return HttpResponse.json(mockTrace)
      })
    )

    const result = await api.getLlmTrace('trace-1')
    expect(result).toEqual(mockTrace)
  })

  // ──── getLlmModels ────

  it('fetches LLM models with default range', async () => {
    const mockModels = [{ model: 'gpt-4', count: 100 }]

    server.use(
      http.get(`${API_BASE}/v1/llm/models`, ({ request }) => {
        const url = new URL(request.url)
        expect(url.searchParams.get('range')).toBe('24h')
        return HttpResponse.json(mockModels)
      })
    )

    const result = await api.getLlmModels()
    expect(result).toEqual(mockModels)
  })

  // ──── getLlmCosts ────

  it('fetches LLM costs with default range', async () => {
    const mockCosts = { totalCost: 42.5, breakdown: [] }

    server.use(
      http.get(`${API_BASE}/v1/llm/costs`, ({ request }) => {
        const url = new URL(request.url)
        expect(url.searchParams.get('range')).toBe('24h')
        return HttpResponse.json(mockCosts)
      })
    )

    const result = await api.getLlmCosts()
    expect(result).toEqual(mockCosts)
  })

  it('fetches LLM costs with custom range', async () => {
    const mockCosts = { totalCost: 200, breakdown: [] }

    server.use(
      http.get(`${API_BASE}/v1/llm/costs`, ({ request }) => {
        const url = new URL(request.url)
        expect(url.searchParams.get('range')).toBe('30d')
        return HttpResponse.json(mockCosts)
      })
    )

    const result = await api.getLlmCosts({range: '30d'})
    expect(result).toEqual(mockCosts)
  })
})
