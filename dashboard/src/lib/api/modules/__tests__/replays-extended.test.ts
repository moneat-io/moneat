import { describe, it, expect, beforeEach } from 'vitest'
import { http, HttpResponse } from 'msw'
import { server } from '@/test/mocks/server'
import { api } from '@/lib/api'

const API_BASE = 'http://localhost:8080'

describe('Replays API - extended coverage', () => {
  beforeEach(() => {
    localStorage.clear()
    sessionStorage.clear()
    sessionStorage.setItem('authenticated', 'true')
  })

  it('fetches replay recording', async () => {
    const mockRecording = { events: [{ type: 1, data: {} }] }

    server.use(
      http.get(`${API_BASE}/v1/replays/replay-1/recording`, () => {
        return HttpResponse.json(mockRecording)
      })
    )

    const result = await api.getReplayRecording('replay-1')
    expect(result).toEqual(mockRecording)
  })

  it('fetches replay timeline', async () => {
    const mockTimeline = { segments: [{ start: 0, end: 1000 }] }

    server.use(
      http.get(`${API_BASE}/v1/replays/replay-1/timeline`, () => {
        return HttpResponse.json(mockTimeline)
      })
    )

    const result = await api.getReplayTimeline('replay-1')
    expect(result).toEqual(mockTimeline)
  })

  it('resolves issue ID for event', async () => {
    server.use(
      http.get(`${API_BASE}/v1/events/evt-1/issue`, () => {
        return HttpResponse.json({ issueId: 'iss-42', projectResourceId: 'proj-1' })
      })
    )

    const result = await api.getIssueIdForEvent('evt-1')
    expect(result).toBe('iss-42')
  })

  it('resolves scoped issue links for event', async () => {
    server.use(
      http.get(`${API_BASE}/v1/events/evt-scoped/issue`, () => {
        return HttpResponse.json({ issueId: 'iss-42', projectResourceId: 'proj-1' })
      })
    )

    const result = await api.getIssueLinkForEvent('evt-scoped')
    expect(result).toEqual({ issueId: 'iss-42', projectResourceId: 'proj-1' })
  })

  it('returns null when issue lookup fails', async () => {
    server.use(
      http.get(`${API_BASE}/v1/events/evt-bad/issue`, () => {
        return new HttpResponse(null, { status: 404 })
      })
    )

    const result = await api.getIssueIdForEvent('evt-bad')
    expect(result).toBeNull()
  })

  it('fetches replays for issue', async () => {
    const mockReplays = [{ replayId: 'r1' }, { replayId: 'r2' }]

    server.use(
      http.get(`${API_BASE}/v1/issues/iss-1/replays`, ({ request }) => {
        const url = new URL(request.url)
        expect(url.searchParams.get('limit')).toBe('5')
        return HttpResponse.json(mockReplays)
      })
    )

    const result = await api.getReplaysForIssue('iss-1', 5)
    expect(result).toEqual(mockReplays)
  })
})
