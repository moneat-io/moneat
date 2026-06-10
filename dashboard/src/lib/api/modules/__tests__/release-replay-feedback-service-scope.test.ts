import {describe, expect, it, beforeEach} from 'vitest'
import {http, HttpResponse} from 'msw'
import {server} from '@/test/mocks/server'
import {api} from '@/lib/api'

const API_BASE = 'http://localhost:8080'

describe('release replay feedback service scope API', () => {
  beforeEach(() => {
    localStorage.clear()
    sessionStorage.clear()
    sessionStorage.setItem('authenticated', 'true')
  })

  it('fetches organization releases with service scope params', async () => {
    const releases = [{version: '1.0.0'}]

    server.use(
      http.get(`${API_BASE}/v1/releases`, ({request}) => {
        const url = new URL(request.url)
        expect(url.searchParams.getAll('services')).toEqual(['API', 'Worker'])
        expect(url.searchParams.getAll('serviceIds')).toEqual(['1', 'svc-worker'])
        expect(url.searchParams.get('projectId')).toBeNull()
        return HttpResponse.json(releases)
      })
    )

    const result = await api.getOrganizationReleases({
      services: ['API', 'Worker'],
      serviceIds: ['svc-api', 'svc-worker'],
    })

    expect(result).toEqual(releases)
  })

  it('fetches organization release stats with service scope params', async () => {
    const stats = {version: '1.0.0', totalEvents: 10}

    server.use(
      http.get(`${API_BASE}/v1/releases/1.0.0/stats`, ({request}) => {
        const url = new URL(request.url)
        expect(url.searchParams.getAll('services')).toEqual(['API'])
        expect(url.searchParams.get('projectId')).toBeNull()
        return HttpResponse.json(stats)
      })
    )

    const result = await api.getOrganizationReleaseStats('1.0.0', {services: ['API']})
    expect(result).toEqual(stats)
  })

  it('fetches organization replays with paging and service scope params', async () => {
    const replays = [{replayId: 'replay-1'}]

    server.use(
      http.get(`${API_BASE}/v1/replays`, ({request}) => {
        const url = new URL(request.url)
        expect(url.searchParams.get('page')).toBe('2')
        expect(url.searchParams.get('limit')).toBe('5')
        expect(url.searchParams.get('period')).toBe('24h')
        expect(url.searchParams.get('environment')).toBe('production')
        expect(url.searchParams.getAll('services')).toEqual(['API'])
        expect(url.searchParams.get('projectId')).toBeNull()
        return HttpResponse.json(replays)
      })
    )

    const result = await api.getOrganizationReplays({
      page: 2,
      limit: 5,
      period: '24h',
      environment: 'production',
      services: ['API'],
    })

    expect(result).toEqual(replays)
  })

  it('fetches organization feedback with status and service scope params', async () => {
    const feedback = [{feedbackId: 'fb-1'}]

    server.use(
      http.get(`${API_BASE}/v1/feedback`, ({request}) => {
        const url = new URL(request.url)
        expect(url.searchParams.get('page')).toBe('3')
        expect(url.searchParams.get('limit')).toBe('10')
        expect(url.searchParams.get('status')).toBe('resolved')
        expect(url.searchParams.getAll('services')).toEqual(['API'])
        expect(url.searchParams.get('projectId')).toBeNull()
        return HttpResponse.json(feedback)
      })
    )

    const result = await api.getOrganizationFeedback({
      page: 3,
      limit: 10,
      status: 'resolved',
      services: ['API'],
    })

    expect(result).toEqual(feedback)
  })
})
