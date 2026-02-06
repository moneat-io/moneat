const API_BASE = '/api/v1'
const AUTH_PAGE_PATHS = new Set(['/login', '/signup', '/verify-email', '/forgot-password', '/reset-password'])

interface AuthResponse {
  token: string
  user: { 
    id: number
    email: string
    name?: string
    emailVerified: boolean
    onboardingCompleted: boolean
  }
}

interface Project {
  id: number
  name: string
  slug: string
  platform?: string
  dsn: string
  issueCount?: number
}

interface Issue {
  id: string
  title: string
  culprit: string
  level: string
  platform: string
  firstSeen: string
  lastSeen: string
  eventCount: number
  userCount: number
  status: string
}

interface IssueDetail extends Issue {
  fingerprint: string[]
  latestEvent?: Event
}

interface Event {
  eventId: string
  timestamp: string
  message: string
  platform: string
  level: string
  environment?: string
  release?: string
  user?: {
    id: string
    email?: string
    username?: string
  }
  tags: Record<string, string>
  contexts: string
  exception?: string
  breadcrumbs?: string
}

interface TimelinePoint {
  timestamp: string
  count: number
}

interface TransactionSummary {
  name: string
  op: string
  latestEventId?: string
  count: number
  p50: number
  p75: number
  p95: number
  failureRate: number
  tpm: number
}

interface IssueTransaction {
  eventId: string
  name: string
  op: string
  duration: number
  timestamp: string
  status?: string | null
  traceId?: string | null
}

interface TransactionDetail {
  eventId: string
  name: string
  op: string
  startTimestamp: number
  duration: number
  traceId: string
  timestamp: string
  environment?: string
  release?: string
  status?: string
  tags: Record<string, string>
  contexts: string
  breadcrumbs?: string
  request?: string
}

interface Span {
  spanId: string
  parentSpanId?: string | null
  op: string
  description: string
  startTimestamp: number
  endTimestamp: number
  duration: number
  status?: string
  tags: Record<string, string>
}

interface TransactionWithSpans {
  transaction: TransactionDetail
  spans: Span[]
}

interface SlowTransaction {
  eventId: string
  name: string
  op: string
  duration: number
  timestamp: string
}

interface PerformanceStats {
  apdex: number
  throughput: TimelinePoint[]
  slowestTransactions: SlowTransaction[]
  totalTransactions: number
  avgDuration: number
}

interface TopIssue {
  issueId: string
  title: string
  count: number
}

interface ProjectStats {
  totalEvents: number
  totalIssues: number
  unresolvedIssues: number
  affectedUsers: number
  eventsTimeline: TimelinePoint[]
  eventsByLevel: Record<string, number>
  eventsByPlatform: Record<string, number>
  eventsByBrowser: Record<string, number>
  eventsByEnvironment: Record<string, number>
  issuesByStatus: Record<string, number>
  topIssues: TopIssue[]
  usersTimeline: TimelinePoint[]
  releaseMarkers?: { version: string; timestamp: string }[]
}

interface Release {
  version: string
  firstSeen: string
  lastSeen: string
  eventCount: number
  newIssueCount: number
  crashFreeRate: number | null
  userCount: number
}

interface ReleaseStats {
  version: string
  firstSeen: string
  lastSeen: string
  totalEvents: number
  newIssues: number
  resolvedIssues: number
  crashFreeSessionRate: number | null
  crashFreeUserRate: number | null
  userCount: number
  eventsTimeline: TimelinePoint[]
  eventsByLevel: Record<string, number>
  topIssues: TopIssue[]
}

interface Replay {
  replayId: string
  projectId: number
  startedAt: string
  finishedAt: string
  durationMs: number
  urls: string[]
  errorCount: number
  user?: { id?: string; email?: string; username?: string }
  browserName?: string
  browserVersion?: string
  osName?: string
  osVersion?: string
  activity: number
}

interface ReplayDetail extends Replay {
  errorIds: string[]
  traceIds: string[]
  segmentCount: number
  environment?: string
  release?: string
  platform?: string
  tags: Record<string, string>
}

interface ReplayRecordingResponse {
  events: unknown[]
}

interface ReplayTimelineItem {
  id: string
  type: 'error' | 'transaction' | 'span'
  timestamp: string
  offsetMs: number
  title: string
  description?: string
  durationMs?: number
  category?: string
  eventId?: string
  issueId?: string
  traceId?: string
}

interface ReplayTimelineResponse {
  items: ReplayTimelineItem[]
  replayStartMs: number
}

class ApiClient {
  private authRedirectInProgress = false

  private getToken(): string | null {
    return localStorage.getItem('auth_token')
  }

  private async request<T>(endpoint: string, options: RequestInit = {}): Promise<T> {
    const token = this.getToken()
    const headers: Record<string, string> = {
      'Content-Type': 'application/json',
      ...(options.headers as Record<string, string>),
    }
    if (token) headers['Authorization'] = `Bearer ${token}`

    const response = await fetch(endpoint, { ...options, headers })

    if (response.status === 401 && token) {
      this.logout()

      if (
        !this.authRedirectInProgress &&
        typeof window !== 'undefined' &&
        !AUTH_PAGE_PATHS.has(window.location.pathname)
      ) {
        this.authRedirectInProgress = true
        window.location.assign('/login')
      }

      throw new Error('Unauthorized')
    }

    if (!response.ok) throw new Error(`API Error: ${response.status} ${response.statusText}`)
    return response.json()
  }

  async signup(email: string, password: string, name?: string): Promise<AuthResponse> {
    const response = await this.request<AuthResponse>('/auth/signup', {
      method: 'POST',
      body: JSON.stringify({ email, password, name }),
    })
    localStorage.setItem('auth_token', response.token)
    return response
  }

  async login(email: string, password: string): Promise<AuthResponse> {
    const response = await this.request<AuthResponse>('/auth/login', {
      method: 'POST',
      body: JSON.stringify({ email, password }),
    })
    localStorage.setItem('auth_token', response.token)
    return response
  }

  logout() {
    localStorage.removeItem('auth_token')
  }

  isAuthenticated(): boolean {
    return !!this.getToken()
  }

  async getProjects(): Promise<Project[]> {
    return this.request<Project[]>(`${API_BASE}/projects`)
  }

  async getProject(projectId: number): Promise<Project> {
    return this.request<Project>(`${API_BASE}/projects/${projectId}`)
  }

  async createProject(name: string, platform?: string): Promise<Project> {
    return this.request<Project>(`${API_BASE}/projects`, {
      method: 'POST',
      body: JSON.stringify({ name, platform }),
    })
  }

  async updateProject(
    projectId: number,
    updates: { name?: string; platform?: string }
  ): Promise<void> {
    await this.request(`${API_BASE}/projects/${projectId}`, {
      method: 'PUT',
      body: JSON.stringify(updates),
    })
  }

  async deleteProject(projectId: number): Promise<void> {
    await this.request(`${API_BASE}/projects/${projectId}`, {
      method: 'DELETE',
    })
  }

  async getIssues(projectId: number, page = 1, limit = 25): Promise<Issue[]> {
    return this.request<Issue[]>(
      `${API_BASE}/projects/${projectId}/issues?page=${page}&limit=${limit}`
    )
  }

  async getIssue(issueId: string): Promise<IssueDetail> {
    return this.request<IssueDetail>(`${API_BASE}/issues/${issueId}`)
  }

  async getIssueEvents(issueId: string, limit = 50): Promise<Event[]> {
    return this.request<Event[]>(`${API_BASE}/issues/${issueId}/events?limit=${limit}`)
  }

  async getIssueTransactions(issueId: string, limit = 20): Promise<IssueTransaction[]> {
    return this.request<IssueTransaction[]>(
      `${API_BASE}/issues/${issueId}/transactions?limit=${limit}`
    )
  }

  async updateIssue(issueId: string, updates: { status?: string }): Promise<void> {
    await this.request(`${API_BASE}/issues/${issueId}`, {
      method: 'PATCH',
      body: JSON.stringify(updates),
    })
  }

  async completeOnboarding(organizationName: string, companySize: string): Promise<{ id: number; email: string; name?: string; emailVerified: boolean; onboardingCompleted: boolean }> {
    return this.request('/auth/complete-onboarding', {
      method: 'POST',
      body: JSON.stringify({ organizationName, companySize }),
    })
  }

  async getCurrentUser(): Promise<{ id: number; email: string; name?: string; emailVerified: boolean; onboardingCompleted: boolean }> {
    return this.request(`${API_BASE}/user`)
  }

  async resendVerificationEmail(email: string): Promise<{ message: string }> {
    return this.request('/auth/resend-verification', {
      method: 'POST',
      body: JSON.stringify({ email }),
    })
  }

  async verifyEmail(token: string): Promise<{ message: string }> {
    return this.request('/auth/verify-email', {
      method: 'POST',
      body: JSON.stringify({ token }),
    })
  }

  async forgotPassword(email: string): Promise<{ message: string }> {
    return this.request('/auth/forgot-password', {
      method: 'POST',
      body: JSON.stringify({ email }),
    })
  }

  async resetPassword(token: string, newPassword: string): Promise<{ message: string }> {
    return this.request('/auth/reset-password', {
      method: 'POST',
      body: JSON.stringify({ token, newPassword }),
    })
  }

  async getProjectStats(projectId: number, period: '24h' | '7d' | '30d' = '7d'): Promise<ProjectStats> {
    return this.request<ProjectStats>(`${API_BASE}/projects/${projectId}/stats?period=${period}`)
  }

  async getTransactions(
    projectId: number,
    options: { period?: '24h' | '7d' | '30d'; environment?: string; operation?: string } = {}
  ): Promise<TransactionSummary[]> {
    const params = new URLSearchParams()
    params.set('period', options.period || '7d')
    if (options.environment) params.set('environment', options.environment)
    if (options.operation) params.set('operation', options.operation)
    const rows = await this.request<Array<TransactionSummary & {
      latest_event_id?: string
      failure_rate?: number
    }>>(`${API_BASE}/projects/${projectId}/transactions?${params.toString()}`)

    // Normalize wire-format variants to keep routing resilient.
    return rows.map((row) => ({
      ...row,
      latestEventId: row.latestEventId || row.latest_event_id,
      failureRate: row.failureRate ?? row.failure_rate ?? 0,
    }))
  }

  async getPerformanceStats(
    projectId: number,
    options: { period?: '24h' | '7d' | '30d'; environment?: string; operation?: string } = {}
  ): Promise<PerformanceStats> {
    const params = new URLSearchParams()
    params.set('period', options.period || '7d')
    if (options.environment) params.set('environment', options.environment)
    if (options.operation) params.set('operation', options.operation)
    return this.request<PerformanceStats>(
      `${API_BASE}/projects/${projectId}/transactions/stats?${params.toString()}`
    )
  }

  async getTransaction(eventId: string): Promise<TransactionDetail> {
    return this.request<TransactionDetail>(`${API_BASE}/transactions/${encodeURIComponent(eventId)}`)
  }

  async getTransactionSpans(eventId: string): Promise<TransactionWithSpans> {
    return this.request<TransactionWithSpans>(
      `${API_BASE}/transactions/${encodeURIComponent(eventId)}/spans`
    )
  }

  async getRelatedErrors(eventId: string, limit = 20): Promise<Event[]> {
    return this.request<Event[]>(
      `${API_BASE}/transactions/${encodeURIComponent(eventId)}/related-errors?limit=${limit}`
    )
  }

  async getReleases(projectId: number): Promise<Release[]> {
    return this.request<Release[]>(`${API_BASE}/projects/${projectId}/releases`)
  }

  async getReleaseStats(projectId: number, version: string): Promise<ReleaseStats> {
    return this.request<ReleaseStats>(
      `${API_BASE}/projects/${projectId}/releases/${encodeURIComponent(version)}/stats`
    )
  }

  async getReplays(
    projectId: number,
    options: { page?: number; limit?: number; environment?: string; period?: '24h' | '7d' | '30d' } = {}
  ): Promise<Replay[]> {
    const params = new URLSearchParams()
    params.set('page', String(options.page ?? 1))
    params.set('limit', String(options.limit ?? 25))
    params.set('period', options.period ?? '7d')
    if (options.environment) params.set('environment', options.environment)
    return this.request<Replay[]>(`${API_BASE}/projects/${projectId}/replays?${params.toString()}`)
  }

  async getReplay(replayId: string): Promise<ReplayDetail> {
    return this.request<ReplayDetail>(`${API_BASE}/replays/${encodeURIComponent(replayId)}`)
  }

  async getReplayRecording(replayId: string): Promise<ReplayRecordingResponse> {
    return this.request<ReplayRecordingResponse>(
      `${API_BASE}/replays/${encodeURIComponent(replayId)}/recording`
    )
  }

  async getReplayTimeline(replayId: string): Promise<ReplayTimelineResponse> {
    return this.request<ReplayTimelineResponse>(
      `${API_BASE}/replays/${encodeURIComponent(replayId)}/timeline`
    )
  }

  async getIssueIdForEvent(eventId: string): Promise<{ issueId: string } | null> {
    try {
      return await this.request<{ issueId: string }>(
        `${API_BASE}/events/${encodeURIComponent(eventId)}/issue`
      )
    } catch {
      return null
    }
  }

  async getReplaysForIssue(issueId: string, limit = 10): Promise<Replay[]> {
    return this.request<Replay[]>(
      `${API_BASE}/issues/${encodeURIComponent(issueId)}/replays?limit=${limit}`
    )
  }
}

export const api = new ApiClient()
export type {
  AuthResponse,
  Project,
  Issue,
  IssueDetail,
  Event,
  ProjectStats,
  TimelinePoint,
  TopIssue,
  IssueTransaction,
  TransactionSummary,
  TransactionDetail,
  Span,
  TransactionWithSpans,
  SlowTransaction,
  PerformanceStats,
  Release,
  ReleaseStats,
  Replay,
  ReplayDetail,
  ReplayRecordingResponse,
  ReplayTimelineItem,
  ReplayTimelineResponse,
}
