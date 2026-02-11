import { describe, it, expect, beforeEach } from 'vitest'
import { http, HttpResponse } from 'msw'
import { server } from '@/test/mocks/server'
import { api } from '../api'

const API_BASE = 'http://localhost:8080'

describe('ApiClient - Extended Methods', () => {
  beforeEach(() => {
    localStorage.clear()
    sessionStorage.clear()
    localStorage.setItem('auth_token', 'test-token')
  })

  describe('Project Stats and Releases', () => {
    it('fetches project stats', async () => {
      const mockStats = {
        totalEvents: 1000,
        totalIssues: 50,
        unresolvedIssues: 30,
        affectedUsers: 100,
        eventsTimeline: [{ timestamp: '2024-02-11', count: 100 }],
        eventsByLevel: { error: 800, warning: 200 },
        eventsByPlatform: { javascript: 1000 },
        eventsByBrowser: { chrome: 600, firefox: 400 },
        eventsByEnvironment: { production: 800, staging: 200 },
        issuesByStatus: { unresolved: 30, resolved: 20 },
        topIssues: [{ issueId: 'issue-1', title: 'Error', count: 100 }],
        usersTimeline: [{ timestamp: '2024-02-11', count: 50 }],
      }

      server.use(
        http.get(`${API_BASE}/v1/projects/1/stats`, () => {
          return HttpResponse.json(mockStats)
        })
      )

      const stats = await api.getProjectStats(1)
      expect(stats).toEqual(mockStats)
    })

    it('fetches project releases', async () => {
      const mockReleases = [
        {
          version: '1.0.0',
          firstSeen: '2024-02-01T00:00:00Z',
          lastSeen: '2024-02-11T12:00:00Z',
          eventCount: 500,
          newIssueCount: 5,
          crashFreeRate: 99.5,
          userCount: 100,
        },
      ]

      server.use(
        http.get(`${API_BASE}/v1/projects/1/releases`, () => {
          return HttpResponse.json(mockReleases)
        })
      )

      const releases = await api.getReleases(1)
      expect(releases).toEqual(mockReleases)
    })

    it('fetches release stats', async () => {
      const mockReleaseStats = {
        version: '1.0.0',
        firstSeen: '2024-02-01T00:00:00Z',
        lastSeen: '2024-02-11T12:00:00Z',
        totalEvents: 500,
        newIssues: 5,
        resolvedIssues: 3,
        crashFreeSessionRate: 99.5,
        crashFreeUserRate: 99.0,
        userCount: 100,
        eventsTimeline: [{ timestamp: '2024-02-11', count: 50 }],
        eventsByLevel: { error: 400, warning: 100 },
        topIssues: [{ issueId: 'issue-1', title: 'Error', count: 50 }],
      }

      server.use(
        http.get(`${API_BASE}/v1/projects/1/releases/1.0.0/stats`, () => {
          return HttpResponse.json(mockReleaseStats)
        })
      )

      const stats = await api.getReleaseStats(1, '1.0.0')
      expect(stats).toEqual(mockReleaseStats)
    })
  })

  describe('Transactions and Performance', () => {
    it('fetches transaction summary', async () => {
      const mockSummary = [
        {
          name: '/api/users',
          op: 'http.server',
          count: 100,
          p50: 150,
          p75: 200,
          p95: 300,
          failureRate: 0.05,
          tpm: 10,
        },
      ]

      server.use(
        http.get(`${API_BASE}/v1/projects/1/transactions`, ({ request }) => {
          const url = new URL(request.url)
          expect(url.searchParams.get('period')).toBe('7d')
          return HttpResponse.json(mockSummary)
        })
      )

      const summary = await api.getTransactions(1)
      expect(summary).toEqual(mockSummary)
    })

    it('fetches transaction detail with spans', async () => {
      const mockDetail = {
        transaction: {
          eventId: 'txn-1',
          name: '/api/users',
          op: 'http.server',
          startTimestamp: 1707650000,
          duration: 150,
          traceId: 'trace-1',
          timestamp: '2024-02-11T12:00:00Z',
          tags: {},
          contexts: '{}',
        },
        spans: [
          {
            spanId: 'span-1',
            op: 'db.query',
            description: 'SELECT * FROM users',
            startTimestamp: 1707650000.1,
            endTimestamp: 1707650000.2,
            duration: 100,
            tags: {},
          },
        ],
      }

      server.use(
        http.get(`${API_BASE}/v1/transactions/txn-1/spans`, () => {
          return HttpResponse.json(mockDetail)
        })
      )

      const detail = await api.getTransactionSpans('txn-1')
      expect(detail).toEqual(mockDetail)
    })

    it('fetches performance stats', async () => {
      const mockPerfStats = {
        apdex: 0.95,
        throughput: [{ timestamp: '2024-02-11', count: 100 }],
        slowestTransactions: [
          {
            eventId: 'txn-1',
            name: '/api/users',
            op: 'http.server',
            duration: 500,
            timestamp: '2024-02-11T12:00:00Z',
          },
        ],
        totalTransactions: 1000,
        avgDuration: 150,
      }

      server.use(
        http.get(`${API_BASE}/v1/projects/1/transactions/stats`, ({ request }) => {
          const url = new URL(request.url)
          expect(url.searchParams.get('period')).toBe('7d')
          return HttpResponse.json(mockPerfStats)
        })
      )

      const stats = await api.getPerformanceStats(1, { period: '7d' })
      expect(stats).toEqual(mockPerfStats)
    })
  })

  describe('Session Replays', () => {
    it('fetches replays list', async () => {
      const mockReplays = [
        {
          replayId: 'replay-1',
          projectId: 1,
          startedAt: '2024-02-11T10:00:00Z',
          finishedAt: '2024-02-11T10:05:00Z',
          durationMs: 300000,
          urls: ['https://example.com'],
          errorCount: 2,
          user: { id: 'user-1', email: 'user@example.com' },
          browserName: 'Chrome',
          browserVersion: '120',
          activity: 0.8,
        },
      ]

      server.use(
        http.get(`${API_BASE}/v1/projects/1/replays`, () => {
          return HttpResponse.json(mockReplays)
        })
      )

      const replays = await api.getReplays(1)
      expect(replays).toEqual(mockReplays)
    })

    it('fetches replay detail', async () => {
      const mockReplay = {
        replayId: 'replay-1',
        projectId: 1,
        startedAt: '2024-02-11T10:00:00Z',
        finishedAt: '2024-02-11T10:05:00Z',
        durationMs: 300000,
        urls: ['https://example.com'],
        errorCount: 2,
        errorIds: ['error-1', 'error-2'],
        traceIds: ['trace-1'],
        segmentCount: 10,
        activity: 0.8,
        tags: {},
      }

      server.use(
        http.get(`${API_BASE}/v1/replays/replay-1`, () => {
          return HttpResponse.json(mockReplay)
        })
      )

      const replay = await api.getReplay('replay-1')
      expect(replay).toEqual(mockReplay)
    })
  })

  describe('Feedback', () => {
    it('fetches feedback list', async () => {
      const mockFeedback = [
        {
          feedbackId: 'fb-1',
          message: 'Great app!',
          contactEmail: 'user@example.com',
          name: 'User',
          url: 'https://example.com',
          status: 'new',
          timestamp: '2024-02-11T12:00:00Z',
          environment: 'production',
          release: '1.0.0',
          platform: 'javascript',
        },
      ]

      server.use(
        http.get(`${API_BASE}/v1/projects/1/feedback`, () => {
          return HttpResponse.json(mockFeedback)
        })
      )

      const feedback = await api.getFeedback(1)
      expect(feedback).toEqual(mockFeedback)
    })

    it('fetches feedback detail', async () => {
      const mockDetail = {
        feedbackId: 'fb-1',
        message: 'Great app!',
        contactEmail: 'user@example.com',
        name: 'User',
        url: 'https://example.com',
        status: 'new',
        timestamp: '2024-02-11T12:00:00Z',
        environment: 'production',
        release: '1.0.0',
        platform: 'javascript',
        tags: {},
        sdkName: 'sentry.javascript',
        sdkVersion: '7.0.0',
      }

      server.use(
        http.get(`${API_BASE}/v1/feedback/fb-1`, () => {
          return HttpResponse.json(mockDetail)
        })
      )

      const detail = await api.getFeedbackDetail('fb-1')
      expect(detail).toEqual(mockDetail)
    })

    it('updates feedback status', async () => {
      server.use(
        http.patch(`${API_BASE}/v1/feedback/fb-1`, async ({ request }) => {
          const body = await request.json() as any
          expect(body.status).toBe('resolved')
          return new HttpResponse(null, { status: 204 })
        })
      )

      await api.updateFeedback('fb-1', { status: 'resolved' })
    })
  })

  describe('SDK Versions', () => {
    it('fetches SDK versions', async () => {
      const mockVersions = {
        fetchedAt: '2024-02-11T12:00:00Z',
        cacheTtlSeconds: 3600,
        versions: {
          'javascript': '7.100.0',
          'python': '1.40.0',
          'ruby': '5.16.0',
        },
      }

      server.use(
        http.get(`${API_BASE}/v1/sdk-versions`, () => {
          return HttpResponse.json(mockVersions)
        })
      )

      const versions = await api.getSdkVersions()
      expect(versions).toEqual(mockVersions)
    })
  })

  describe('Auth Tokens', () => {
    it('fetches auth tokens', async () => {
      const mockTokens = [
        {
          id: 1,
          name: 'CI Token',
          scopes: ['project:read', 'event:write'],
          createdAt: '2024-02-01T00:00:00Z',
        },
      ]

      server.use(
        http.get(`${API_BASE}/v1/auth-tokens`, () => {
          return HttpResponse.json(mockTokens)
        })
      )

      const tokens = await api.getAuthTokens()
      expect(tokens).toEqual(mockTokens)
    })

    it('creates auth token', async () => {
      const mockToken = {
        id: 2,
        name: 'New Token',
        token: 'token-secret',
        scopes: ['project:read'],
        createdAt: '2024-02-11T12:00:00Z',
      }

      server.use(
        http.post(`${API_BASE}/v1/auth-tokens`, async ({ request }) => {
          const body = await request.json() as any
          expect(body.name).toBe('New Token')
          expect(body.scopes).toEqual(['project:read'])
          return HttpResponse.json(mockToken)
        })
      )

      const token = await api.createAuthToken('New Token', ['project:read'])
      expect(token).toEqual(mockToken)
    })

    it('deletes auth token', async () => {
      server.use(
        http.delete(`${API_BASE}/v1/auth-tokens/1`, () => {
          return new HttpResponse(null, { status: 204 })
        })
      )

      await api.deleteAuthToken(1)
    })
  })

  describe('Notification Preferences', () => {
    it('fetches notification preferences', async () => {
      const mockPrefs = {
        global: {
          issueAlerts: true,
          errorAlerts: true,
          weeklySummary: false,
          alertFrequencyMinutes: 60,
        },
        projects: [
          {
            projectId: 1,
            projectName: 'Test Project',
            issueAlerts: true,
            errorAlerts: true,
            weeklySummary: true,
            alertFrequencyMinutes: 30,
          },
        ],
      }

      server.use(
        http.get(`${API_BASE}/v1/notification-preferences`, () => {
          return HttpResponse.json(mockPrefs)
        })
      )

      const prefs = await api.getNotificationPreferences()
      expect(prefs).toEqual(mockPrefs)
    })

    it('updates notification preferences', async () => {
      server.use(
        http.put(`${API_BASE}/v1/notification-preferences`, async ({ request }) => {
          const body = await request.json() as any
          expect(body.issueAlerts).toBe(false)
          return new HttpResponse(null, { status: 204 })
        })
      )

      await api.updateNotificationPreferences({ issueAlerts: false })
    })
  })

  describe('Logs', () => {
    it('queries logs', async () => {
      const mockLog = {
        logId: 'log-1',
        timestamp: '2024-02-11T12:00:00Z',
        level: 'info',
        message: 'Application started',
        body: 'Full log body',
        service: 'web',
        environment: 'production',
        host: 'server-1',
        source: 'stdout',
        containerName: 'app-1',
        containerId: 'abc123',
        containerImage: 'myapp:latest',
        traceId: 'trace-1',
        spanId: 'span-1',
        tags: {},
        resourceAttributes: {},
      }

      server.use(
        http.get(`${API_BASE}/v1/projects/1/logs`, ({ request }) => {
          const url = new URL(request.url)
          expect(url.searchParams.get('q')).toBe('service:web')
          return HttpResponse.json({ 
            logs: [mockLog],
            hasMore: false,
          })
        })
      )

      const result = await api.getProjectLogs(1, { query: 'service:web' })
      expect(result.logs).toEqual([mockLog])
      expect(result.hasMore).toBe(false)
    })

    it('fetches log filters', async () => {
      const mockFilters = {
        services: ['web', 'api'],
        environments: ['production', 'staging'],
        levels: ['info', 'error', 'warning'],
        tagKeys: ['user_id', 'request_id'],
      }

      server.use(
        http.get(`${API_BASE}/v1/projects/1/logs/filters`, () => {
          return HttpResponse.json(mockFilters)
        })
      )

      const filters = await api.getProjectLogFilters(1)
      expect(filters).toEqual(mockFilters)
    })
  })
})
