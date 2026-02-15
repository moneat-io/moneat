import { describe, it, expect, beforeEach } from 'vitest'
import { http, HttpResponse } from 'msw'
import { server } from '@/test/mocks/server'
import { api } from '../api'

const API_BASE = 'http://localhost:8080'

describe('ApiClient - Projects and Issues', () => {
  beforeEach(() => {
    localStorage.clear()
    sessionStorage.clear()
    // Auth is now cookie-based; set session flag for isAuthenticated() checks
    sessionStorage.setItem('authenticated', 'true')
  })

  describe('Projects', () => {
    it('fetches projects list', async () => {
      const mockProjects = [
        {
          id: 1,
          name: 'Test Project',
          slug: 'test-project',
          framework: 'react',
          keys: [],
          dsn: 'http://key@localhost/1',
        },
      ]

      server.use(
        http.get(`${API_BASE}/v1/projects`, () => {
          return HttpResponse.json(mockProjects)
        })
      )

      const projects = await api.getProjects()
      expect(projects).toEqual(mockProjects)
    })

    it('fetches single project', async () => {
      const mockProject = {
        id: 1,
        name: 'Test Project',
        slug: 'test-project',
        framework: 'react',
        keys: [],
        dsn: 'http://key@localhost/1',
      }

      server.use(
        http.get(`${API_BASE}/v1/projects/1`, () => {
          return HttpResponse.json(mockProject)
        })
      )

      const project = await api.getProject(1)
      expect(project).toEqual(mockProject)
    })

    it('creates new project', async () => {
      const mockProject = {
        id: 2,
        name: 'New Project',
        slug: 'new-project',
        framework: 'vue',
        keys: [],
        dsn: 'http://key@localhost/2',
      }

      server.use(
        http.post(`${API_BASE}/v1/projects`, async ({ request }) => {
          const body = await request.json() as any
          expect(body.name).toBe('New Project')
          expect(body.framework).toBe('vue')
          return HttpResponse.json(mockProject)
        })
      )

      const project = await api.createProject('New Project', 'vue')
      expect(project).toEqual(mockProject)
    })

    it('updates project', async () => {
      server.use(
        http.put(`${API_BASE}/v1/projects/1`, async ({ request }) => {
          const body = await request.json() as any
          expect(body.name).toBe('Updated Name')
          return new HttpResponse(null, { status: 204 })
        })
      )

      await api.updateProject(1, { name: 'Updated Name' })
    })

    it('deletes project', async () => {
      server.use(
        http.delete(`${API_BASE}/v1/projects/1`, () => {
          return new HttpResponse(null, { status: 204 })
        })
      )

      await api.deleteProject(1)
    })
  })

  describe('Issues', () => {
    it('fetches issues list with pagination', async () => {
      const mockIssues = [
        {
          id: 'issue-1',
          projectId: 1,
          title: 'TypeError: undefined is not a function',
          culprit: 'app.js',
          level: 'error',
          platform: 'javascript',
          firstSeen: '2024-02-11T10:00:00Z',
          lastSeen: '2024-02-11T12:00:00Z',
          eventCount: 10,
          userCount: 5,
          status: 'unresolved',
        },
      ]

      server.use(
        http.get(`${API_BASE}/v1/projects/1/issues`, ({ request }) => {
          const url = new URL(request.url)
          expect(url.searchParams.get('page')).toBe('1')
          expect(url.searchParams.get('limit')).toBe('25')
          return HttpResponse.json(mockIssues)
        })
      )

      const issues = await api.getIssues(1, 1, 25)
      expect(issues).toEqual(mockIssues)
    })

    it('fetches single issue detail', async () => {
      const mockIssue = {
        id: 'issue-1',
        projectId: 1,
        title: 'TypeError',
        culprit: 'app.js',
        level: 'error',
        platform: 'javascript',
        firstSeen: '2024-02-11T10:00:00Z',
        lastSeen: '2024-02-11T12:00:00Z',
        eventCount: 10,
        userCount: 5,
        status: 'unresolved',
        fingerprint: ['{{ default }}'],
        latestEvent: {
          eventId: 'event-1',
          timestamp: '2024-02-11T12:00:00Z',
          message: 'Error occurred',
          platform: 'javascript',
          level: 'error',
          tags: {},
          contexts: '{}',
        },
      }

      server.use(
        http.get(`${API_BASE}/v1/issues/issue-1`, () => {
          return HttpResponse.json(mockIssue)
        })
      )

      const issue = await api.getIssue('issue-1')
      expect(issue).toEqual(mockIssue)
    })

    it('updates issue status', async () => {
      server.use(
        http.patch(`${API_BASE}/v1/issues/issue-1`, async ({ request }) => {
          const body = await request.json() as any
          expect(body.status).toBe('resolved')
          return new HttpResponse(null, { status: 204 })
        })
      )

      await api.updateIssue('issue-1', { status: 'resolved' })
    })

    it('fetches issue events', async () => {
      const mockEvents = [
        {
          eventId: 'event-1',
          timestamp: '2024-02-11T12:00:00Z',
          message: 'Error occurred',
          platform: 'javascript',
          level: 'error',
          tags: {},
          contexts: '{}',
        },
      ]

      server.use(
        http.get(`${API_BASE}/v1/issues/issue-1/events`, ({ request }) => {
          const url = new URL(request.url)
          expect(url.searchParams.get('limit')).toBe('50')
          return HttpResponse.json(mockEvents)
        })
      )

      const events = await api.getIssueEvents('issue-1', 50)
      expect(events).toEqual(mockEvents)
    })
  })

  describe('Billing', () => {
    it('fetches billing usage', async () => {
      const mockUsage = {
        organizationId: 1,
        periodStart: '2024-02-01T00:00:00Z',
        periodEnd: '2024-03-01T00:00:00Z',
        retentionDays: 90,
        usedUnits: 1000,
        usedErrors: 800,
        errorLimit: 10000,
        usedTransactions: 200,
        transactionLimit: 5000,
        usedReplays: 0,
        replayLimit: 1000,
        usedFeedback: 0,
        feedbackLimit: 500,
        usedBytes: 1073741824,
        bytesLimit: 10737418240,
        baseLimitUnits: 10000,
        paygLimitUnits: 0,
        totalLimitUnits: 10000,
        paygBudgetCents: 0,
        paygUsedUnits: 0,
        paygUsedCentsEstimate: 0,
        plan: 'PRO',
        status: 'active',
        withinQuota: true,
      }

      server.use(
        http.get(`${API_BASE}/v1/billing/usage`, () => {
          return HttpResponse.json(mockUsage)
        })
      )

      const usage = await api.getBillingUsage()
      expect(usage).toEqual(mockUsage)
    })

    it('fetches billing plans', async () => {
      const mockPlans = {
        plans: [
          {
            tier: {
              id: 1,
              tierName: 'PRO',
              version: 1,
              monthlyPriceCents: 2900,
              yearlyPriceCents: 29000,
              monthlyGbLimit: 10737418240,
              retentionDays: 90,
              monthlyUnitLimit: 10000,
              monthlyErrorLimit: 8000,
              monthlyTransactionLimit: 5000,
              monthlyReplayLimit: 1000,
              monthlyFeedbackLimit: 500,
              logRetentionDays: 30,
              statusPagesEnabled: true,
              statusPageCustomDomainEnabled: false,
              sessionReplayEnabled: true,
              slackEnabled: true,
              incidentIoEnabled: false,
              samlEnabled: false,
              oidcEnabled: false,
              prioritySupportEnabled: false,
              slaEnabled: false,
              customRetentionEnabled: false,
              maxProjects: 10,
              maxSystems: 50,
              monitorIntervalSeconds: 60,
              trialDays: 14,
              paygEnabled: false,
              paygRateMicrosPerUnit: 0,
              isCurrent: true,
            },
            trialDays: 14,
          },
        ],
        stripeEnabled: true,
        publishableKey: 'pk_test_xxx',
      }

      server.use(
        http.get(`${API_BASE}/v1/billing/plans`, () => {
          return HttpResponse.json(mockPlans)
        })
      )

      const plans = await api.getBillingPlans()
      expect(plans).toEqual(mockPlans)
    })

    it('updates PAYG budget', async () => {
      server.use(
        http.put(`${API_BASE}/v1/billing/payg-budget`, async ({ request }) => {
          const body = await request.json() as any
          expect(body.paygBudgetCents).toBe(10000)
          return HttpResponse.json({ paygBudgetCents: 10000 })
        })
      )

      const result = await api.updatePaygBudget(10000)
      expect(result.paygBudgetCents).toBe(10000)
    })
  })

  describe('Auth and Onboarding', () => {
    it('completes onboarding', async () => {
      const mockUser = {
        id: 1,
        email: 'test@example.com',
        name: 'Test User',
        emailVerified: true,
        onboardingCompleted: true,
      }

      server.use(
        http.post(`${API_BASE}/auth/complete-onboarding`, async ({ request }) => {
          const body = await request.json() as any
          expect(body.organizationName).toBe('Acme Corp')
          expect(body.companySize).toBe('10-50')
          return HttpResponse.json(mockUser)
        })
      )

      const user = await api.completeOnboarding('Acme Corp', '10-50', 'acme-corp', 'search')
      expect(user).toEqual(mockUser)
    })

    it('checks SSO required', async () => {
      server.use(
        http.post(`${API_BASE}/v1/sso/check-required`, async ({ request }) => {
          const body = await request.json() as any
          expect(body.email).toBe('sso@example.com')
          return HttpResponse.json({ required: true })
        })
      )

      const result = await api.checkSsoRequired('sso@example.com')
      expect(result.required).toBe(true)
    })

    it('initiates SSO login', async () => {
      server.use(
        http.post(`${API_BASE}/auth/sso/init`, async ({ request }) => {
          const body = await request.json() as any
          expect(body.email).toBe('sso@example.com')
          return HttpResponse.json({
            redirectUrl: 'https://sso.example.com/login',
            providerType: 'saml',
            state: 'random-state',
          })
        })
      )

      const result = await api.initSso('sso@example.com')
      expect(result.redirectUrl).toBe('https://sso.example.com/login')
      expect(result.providerType).toBe('saml')
    })
  })
})
