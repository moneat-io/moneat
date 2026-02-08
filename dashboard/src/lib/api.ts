const API_BASE = `${import.meta.env.VITE_BACKEND_URL || 'https://api.moneat.io'}/v1`
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

interface SignupLegalConsent {
  acceptTerms: boolean
  acceptPrivacy: boolean
  termsVersion: string
  privacyVersion: string
}

interface ProjectKey {
  platformTarget: string | null
  dsn: string
}

interface Project {
  id: number
  name: string
  slug: string
  framework?: string
  keys: ProjectKey[]
  dsn: string  // First key's DSN for backward compatibility
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

export interface Feedback {
  feedbackId: string
  message: string
  contactEmail: string
  name: string
  url: string
  status: string
  timestamp: string
  environment: string
  release: string
  platform: string
  user?: { id?: string; email?: string; username?: string }
  associatedEventId?: string | null
  replayId?: string | null
}

export interface FeedbackDetail extends Feedback {
  tags: Record<string, string>
  sdkName: string
  sdkVersion: string
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

interface NotificationPreference {
  issueAlerts: boolean
  errorAlerts: boolean
  weeklySummary: boolean
  alertFrequencyMinutes: number
}

interface ProjectNotificationPreference extends NotificationPreference {
  projectId: number
  projectName: string
}

interface NotificationPreferences {
  global: NotificationPreference
  projects: ProjectNotificationPreference[]
}

interface ReplayTimelineResponse {
  items: ReplayTimelineItem[]
  replayStartMs: number
}

interface AuthToken {
  id: number
  name: string
  token?: string | null
  scopes: string[]
  lastUsedAt?: string | null
  expiresAt?: string | null
  createdAt: string
}

interface BillingTierConfig {
  id: number
  tierName: string
  version: number
  monthlyUnitLimit: number
  monthlyErrorLimit: number
  monthlyTransactionLimit: number
  monthlyReplayLimit: number
  monthlyFeedbackLimit: number
  retentionDays: number
  maxProjects: number | null
  maxSystems: number
  monitorIntervalSeconds: number
  monthlyPriceCents: number
  paygEnabled: boolean
  paygRateMicrosPerUnit: number
  stripeBasePriceId?: string | null
  stripeOveragePriceId?: string | null
  isCurrent: boolean
}

interface BillingPlan {
  tier: BillingTierConfig
  trialDays: number
}

interface BillingPlansResponse {
  plans: BillingPlan[]
  stripeEnabled: boolean
  publishableKey?: string | null
}

interface BillingUsage {
  organizationId: number
  periodStart: string
  periodEnd: string
  retentionDays: number
  usedUnits: number
  usedErrors: number
  errorLimit: number
  usedTransactions: number
  transactionLimit: number
  usedReplays: number
  replayLimit: number
  usedFeedback: number
  feedbackLimit: number
  baseLimitUnits: number
  paygLimitUnits: number
  totalLimitUnits: number
  paygBudgetCents: number
  paygUsedUnits: number
  paygUsedCentsEstimate: number
  plan: string
  status: string
  withinQuota: boolean
}

interface CheckoutSessionRequest {
  tierName: string
  successUrl: string
  cancelUrl: string
}

interface CheckoutSessionResponse {
  sessionId: string
  url: string
}

interface Invoice {
  id: string
  date: string
  amountCents: number
  status: string
  pdfUrl?: string | null
}

interface PaymentMethod {
  brand?: string | null
  last4?: string | null
  expMonth?: number | null
  expYear?: number | null
}

interface SetupIntentResponse {
  clientSecret: string
}

interface CancelSubscriptionResponse {
  status: string
  cancelAtPeriodEnd: boolean
  currentPeriodEnd?: string | null
}

interface AdminBillingSubscription {
  subscriptionId: number
  organizationId: number
  organizationName: string
  plan: string
  status: string
  pricingTierConfigId?: number | null
  paygBudgetCents: number
  paygUsedUnits: number
  paygUsedMicros: number
  pendingMeterUnits: number
  currentPeriodStart?: string | null
  currentPeriodEnd?: string | null
}

interface CreateTierVersionRequest {
  monthlyUnitLimit: number
  monthlyErrorLimit: number
  monthlyTransactionLimit: number
  monthlyReplayLimit: number
  monthlyFeedbackLimit: number
  retentionDays: number
  maxProjects?: number | null
  maxSystems: number
  monitorIntervalSeconds: number
  monthlyPriceCents: number
  paygEnabled: boolean
  paygRateMicrosPerUnit: number
  stripeBasePriceId?: string | null
  stripeOveragePriceId?: string | null
}

interface TierMigrationResponse {
  tierName: string
  targetVersion: number
  affectedSubscriptions: number
  dryRun: boolean
}

// Monitoring types
interface MonitorSystem {
  id: string
  name: string
  host?: string
  status: 'up' | 'down' | 'pending'
  lastSeenAt?: string | number
  agentVersion?: string
  os?: string
  arch?: string
  createdAt?: string | number
  updatedAt?: string | number
}

interface LatestMetrics {
  cpu_percent: number
  mem_total: number
  mem_used: number
  mem_percent: number
  disk_total: number
  disk_used: number
  disk_percent: number
  net_recv_bytes: number
  net_sent_bytes: number
  net_recv_mbps?: number | null
  net_sent_mbps?: number | null
  load_1: number
  temp_max?: number | null
  gpu_percent?: number | null
  battery_percent?: number | null
}

interface MonitorSystemWithMetrics extends MonitorSystem {
  cpuPercent?: number
  memTotal?: number
  memUsed?: number
  memAvailable?: number
  diskTotal?: number
  diskUsed?: number
  load1?: number
  load5?: number
  load15?: number
  netRecvBytes?: number
  netSentBytes?: number
  tempMax?: number
  gpuPercent?: number
  batteryPercent?: number
  latest_metrics?: LatestMetrics
}

interface MonitorSystemDetail extends MonitorSystemWithMetrics {
  agentKey?: string
}

interface CreateMonitorSystemResponse {
  system: {
    id: string
    name: string
    host?: string
    status: string
    last_seen_at?: number
    agent_version?: string
    os?: string
    arch?: string
    created_at: number
    latest_metrics?: any
  }
  agent_key: string
  docker_command: string
}

interface HistoricalDataPoint {
  timestamp: number
  cpu_percent?: number
  mem_percent?: number
  disk_percent?: number
  net_recv_bytes?: number
  net_sent_bytes?: number
  load_1?: number
  load_5?: number
  load_15?: number
  temp_max?: number
  gpu_percent?: number
  battery_percent?: number
}

interface SystemMetricsHistory {
  data_points: HistoricalDataPoint[]
}

interface ContainerStats {
  name: string
  id: string
  image: string
  status: string
  cpuPercent?: number
  memUsed?: number
  memLimit?: number
  netRecvBytes?: number
  netSentBytes?: number
}

interface ContainerHistoricalDataPoint {
  timestamp: number
  cpu_percent: number
  mem_used: number
  mem_limit: number
  net_recv_bytes: number
  net_sent_bytes: number
}

interface ContainerMetricsHistory {
  data_points: ContainerHistoricalDataPoint[]
}

interface SystemAlert {
  id: number
  systemId?: string
  scope: 'global' | 'system'
  metric: string
  condition: string
  threshold: number
  durationSeconds: number
  enabled: boolean
  lastTriggeredAt?: number
  createdAt: number
}

interface SystemAlertConfig {
  scope: 'global' | 'system'
  globalAlerts: SystemAlert[]
  systemAlerts: SystemAlert[]
  effectiveAlerts: SystemAlert[]
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
    if (response.status === 204) return undefined as T
    return response.json()
  }

  private mapMonitorSystem(row: any): MonitorSystemWithMetrics {
    const latest = row.latest_metrics || {}
    return {
      id: row.id,
      name: row.name,
      host: row.host,
      status: row.status,
      lastSeenAt: row.lastSeenAt ?? row.last_seen_at,
      agentVersion: row.agentVersion ?? row.agent_version,
      os: row.os,
      arch: row.arch,
      createdAt: row.createdAt ?? row.created_at,
      updatedAt: row.updatedAt ?? row.updated_at,
      cpuPercent: row.cpuPercent ?? latest.cpu_percent,
      memTotal: row.memTotal ?? latest.mem_total,
      memUsed: row.memUsed ?? latest.mem_used,
      memAvailable: row.memAvailable ?? latest.mem_available,
      diskTotal: row.diskTotal ?? latest.disk_total,
      diskUsed: row.diskUsed ?? latest.disk_used,
      load1: row.load1 ?? latest.load_1,
      load5: row.load5 ?? latest.load_5,
      load15: row.load15 ?? latest.load_15,
      netRecvBytes: row.netRecvBytes ?? latest.net_recv_bytes,
      netSentBytes: row.netSentBytes ?? latest.net_sent_bytes,
      tempMax: row.tempMax ?? latest.temp_max,
      gpuPercent: row.gpuPercent ?? latest.gpu_percent,
      batteryPercent: row.batteryPercent ?? latest.battery_percent,
      latest_metrics: row.latest_metrics,
    }
  }

  private mapSystemAlert(row: any): SystemAlert {
    return {
      id: row.id,
      systemId: row.systemId ?? row.system_id,
      scope: (row.scope ?? 'system') as 'global' | 'system',
      metric: row.metric,
      condition: row.condition,
      threshold: row.threshold,
      durationSeconds: row.durationSeconds ?? row.duration_seconds ?? 0,
      enabled: row.enabled === true,
      lastTriggeredAt: row.lastTriggeredAt ?? row.last_triggered_at,
      createdAt: row.createdAt ?? row.created_at,
    }
  }

  async signup(email: string, password: string, name: string | undefined, legalConsent: SignupLegalConsent): Promise<AuthResponse> {
    const response = await this.request<AuthResponse>(`${API_BASE.replace('/v1', '')}/auth/signup`, {
      method: 'POST',
      body: JSON.stringify({ email, password, name, ...legalConsent }),
    })
    localStorage.setItem('auth_token', response.token)
    return response
  }

  async login(email: string, password: string): Promise<AuthResponse> {
    const response = await this.request<AuthResponse>(`${API_BASE.replace('/v1', '')}/auth/login`, {
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

  async createProject(name: string, framework?: string, targets?: string[]): Promise<Project> {
    return this.request<Project>(`${API_BASE}/projects`, {
      method: 'POST',
      body: JSON.stringify({ name, framework, targets }),
    })
  }

  async addProjectTarget(projectId: number, target: string): Promise<ProjectKey> {
    return this.request<ProjectKey>(`${API_BASE}/projects/${projectId}/targets`, {
      method: 'POST',
      body: JSON.stringify({ target }),
    })
  }

  async updateProject(
    projectId: number,
    updates: { name?: string; framework?: string }
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
    return this.request(`${API_BASE.replace('/v1', '')}/auth/complete-onboarding`, {
      method: 'POST',
      body: JSON.stringify({ organizationName, companySize }),
    })
  }

  async getCurrentUser(): Promise<{ id: number; email: string; name?: string; emailVerified: boolean; onboardingCompleted: boolean; isAdmin?: boolean }> {
    return this.request(`${API_BASE}/user`)
  }

  async resendVerificationEmail(email: string): Promise<{ message: string }> {
    return this.request(`${API_BASE.replace('/v1', '')}/auth/resend-verification`, {
      method: 'POST',
      body: JSON.stringify({ email }),
    })
  }

  async verifyEmail(token: string): Promise<{ message: string }> {
    return this.request(`${API_BASE.replace('/v1', '')}/auth/verify-email`, {
      method: 'POST',
      body: JSON.stringify({ token }),
    })
  }

  async forgotPassword(email: string): Promise<{ message: string }> {
    return this.request(`${API_BASE.replace('/v1', '')}/auth/forgot-password`, {
      method: 'POST',
      body: JSON.stringify({ email }),
    })
  }

  async resetPassword(token: string, newPassword: string): Promise<{ message: string }> {
    return this.request(`${API_BASE.replace('/v1', '')}/auth/reset-password`, {
      method: 'POST',
      body: JSON.stringify({ token, newPassword }),
    })
  }

  async getProjectStats(projectId: number, period: '24h' | '7d' | '30d' | '90d' = '7d'): Promise<ProjectStats> {
    return this.request<ProjectStats>(`${API_BASE}/projects/${projectId}/stats?period=${period}`)
  }

  async getTransactions(
    projectId: number,
    options: { period?: '24h' | '7d' | '30d' | '90d'; environment?: string; operation?: string } = {}
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
    options: { period?: '24h' | '7d' | '30d' | '90d'; environment?: string; operation?: string } = {}
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
    options: { page?: number; limit?: number; environment?: string; period?: '24h' | '7d' | '30d' | '90d' } = {}
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

  async getFeedback(
    projectId: number,
    options: { page?: number; limit?: number; status?: string } = {}
  ): Promise<Feedback[]> {
    const params = new URLSearchParams()
    params.set('page', String(options.page ?? 1))
    params.set('limit', String(options.limit ?? 25))
    if (options.status) params.set('status', options.status)
    return this.request<Feedback[]>(
      `${API_BASE}/projects/${projectId}/feedback?${params.toString()}`
    )
  }

  async getFeedbackDetail(feedbackId: string): Promise<FeedbackDetail> {
    return this.request<FeedbackDetail>(
      `${API_BASE}/feedback/${encodeURIComponent(feedbackId)}`
    )
  }

  async updateFeedback(feedbackId: string, updates: { status?: string }): Promise<void> {
    await this.request(`${API_BASE}/feedback/${encodeURIComponent(feedbackId)}`, {
      method: 'PATCH',
      body: JSON.stringify(updates),
    })
  }

  async getAuthTokens(): Promise<AuthToken[]> {
    return this.request<AuthToken[]>(`${API_BASE}/auth-tokens`)
  }

  async createAuthToken(
    name: string,
    scopes: string[],
    expiresInDays?: number
  ): Promise<AuthToken> {
    return this.request<AuthToken>(`${API_BASE}/auth-tokens`, {
      method: 'POST',
      body: JSON.stringify({ name, scopes, expiresInDays: expiresInDays ?? null }),
    })
  }

  async updateAuthToken(
    tokenId: number,
    updates: { name?: string; scopes?: string[] }
  ): Promise<void> {
    await this.request<void>(`${API_BASE}/auth-tokens/${tokenId}`, {
      method: 'PUT',
      body: JSON.stringify(updates),
    })
  }

  async deleteAuthToken(tokenId: number): Promise<void> {
    await this.request<void>(`${API_BASE}/auth-tokens/${tokenId}`, {
      method: 'DELETE',
    })
  }

  // Billing API
  async getBillingPlans() {
    return this.request<BillingPlansResponse>(`${API_BASE}/billing/plans`)
  }

  async getBillingUsage() {
    return this.request<BillingUsage>(`${API_BASE}/billing/usage`)
  }

  async createBillingCheckoutSession(body: CheckoutSessionRequest) {
    return this.request<CheckoutSessionResponse>(`${API_BASE}/billing/checkout`, {
      method: 'POST',
      body: JSON.stringify(body),
    })
  }

  async getBillingInvoices() {
    return this.request<Invoice[]>(`${API_BASE}/billing/invoices`)
  }

  async getBillingPaymentMethod() {
    return this.request<PaymentMethod>(`${API_BASE}/billing/payment-method`)
  }

  async createBillingSetupIntent() {
    return this.request<SetupIntentResponse>(`${API_BASE}/billing/setup-intent`, {
      method: 'POST',
    })
  }

  async cancelBillingSubscription() {
    return this.request<CancelSubscriptionResponse>(`${API_BASE}/billing/cancel`, {
      method: 'POST',
    })
  }

  async updatePaygBudget(paygBudgetCents: number) {
    return this.request<{paygBudgetCents: number}>(`${API_BASE}/billing/payg-budget`, {
      method: 'PUT',
      body: JSON.stringify({paygBudgetCents}),
    })
  }

  // Admin API
  async getAdminOverview() {
    return this.request<{
      totalOrganizations: number
      totalUsers: number
      totalEventsAllTime: number
      totalEventsLast30Days: number
      mrr: number
      subscriptionsByPlan: Record<string, number>
      eventsLast30Days: { date: string; count: number }[]
    }>(`${API_BASE}/admin/overview`)
  }

  async getAdminOrganizations(page = 1, limit = 25) {
    return this.request<Array<{
      id: number
      name: string
      slug: string
      plan: string
      eventCountThisMonth: number
      projectCount: number
      memberCount: number
      quotaUsedPercent: number | null
    }>>(`${API_BASE}/admin/organizations?page=${page}&limit=${limit}`)
  }

  async getAdminOrgDetail(orgId: number) {
    return this.request<{
      id: number
      name: string
      slug: string
      companySize: string | null
      plan: string
      subscriptionStatus: string | null
      memberCount: number
      projectCount: number
      eventCountThisMonth: number
      bytesIngestedThisMonth: number
      quotaUsedPercent: number | null
      members: Array<{ userId: number; email: string; name: string | null; role: string }>
      projects: Array<{ id: number; name: string; slug: string; platform: string | null }>
    }>(`${API_BASE}/admin/organizations/${orgId}`)
  }

  async getAdminOrgUsage(orgId: number, period = '7d') {
    return this.request<Array<{ date: string; eventType: string; eventCount: number; bytesIngested: number }>>(
      `${API_BASE}/admin/organizations/${orgId}/usage?period=${period}`
    )
  }

  async getAdminUsage(period = '7d') {
    return this.request<{
      daily: Array<{
        date: string
        error: number
        transaction: number
        replay: number
        feedback: number
        total: number
      }>
      totalBytes: number
    }>(`${API_BASE}/admin/usage?period=${period}`)
  }

  async getAdminRevenue() {
    return this.request<{
      mrr: number
      subscriptionsByPlan: Record<string, number>
      estimatedCostPerOrg: Record<string, number>
      churnLast30Days: number
    }>(`${API_BASE}/admin/revenue`)
  }

  async getAdminInfrastructure() {
    return this.request<{
      clickhouseTables: Array<{
        table: string
        rows: number
        bytesOnDisk: number
        bytesOnDiskFormatted: string
      }>
      totalDiskBytes: number
      totalRows: number
      storageUsedPercent: number
      scalingTriggerAlerts: string[]
    }>(`${API_BASE}/admin/infrastructure`)
  }

  async getAdminTopConsumers(limit = 10) {
    return this.request<Array<{
      orgId: number
      orgName: string
      orgSlug: string
      plan: string
      eventCount: number
      bytesIngested: number
    }>>(`${API_BASE}/admin/top-consumers?limit=${limit}`)
  }

  async getAdminEmailStats(period = '30d') {
    return this.request<{
      totalSent: number
      byType: Record<string, number>
      last7Days: Array<{ date: string; count: number }>
      last30Days: Array<{ date: string; count: number }>
      estimatedCost: number
    }>(`${API_BASE}/admin/emails?period=${period}`)
  }

  async getAdminBillingTiers(tier?: string) {
    const query = tier ? `?tier=${encodeURIComponent(tier)}` : ''
    return this.request<BillingPlan[] | BillingTierConfig[]>(`${API_BASE}/admin/billing/tiers${query}`)
  }

  async createAdminBillingTierVersion(tierName: string, body: CreateTierVersionRequest) {
    return this.request<BillingTierConfig>(`${API_BASE}/admin/billing/tiers/${encodeURIComponent(tierName)}/versions`, {
      method: 'POST',
      body: JSON.stringify(body),
    })
  }

  async migrateAdminBillingTier(tierName: string, targetVersion: number, dryRun = true) {
    return this.request<TierMigrationResponse>(`${API_BASE}/admin/billing/tiers/${encodeURIComponent(tierName)}/migrate`, {
      method: 'POST',
      body: JSON.stringify({targetVersion, dryRun}),
    })
  }

  async getAdminBillingSubscriptions(limit = 500) {
    return this.request<AdminBillingSubscription[]>(`${API_BASE}/admin/billing/subscriptions?limit=${limit}`)
  }

  // Notification Preferences
  async getNotificationPreferences() {
    return this.request<NotificationPreferences>(`${API_BASE}/notification-preferences`)
  }

  async updateNotificationPreferences(preferences: Partial<NotificationPreference>) {
    return this.request(`${API_BASE}/notification-preferences`, {
      method: 'PUT',
      body: JSON.stringify(preferences),
    })
  }

  async updateProjectNotificationPreferences(
    projectId: number,
    preferences: Partial<NotificationPreference>
  ) {
    return this.request(`${API_BASE}/notification-preferences/${projectId}`, {
      method: 'PUT',
      body: JSON.stringify(preferences),
    })
  }

  async deleteProjectNotificationPreferences(projectId: number) {
    return this.request(`${API_BASE}/notification-preferences/${projectId}`, {
      method: 'DELETE',
    })
  }

  // Monitoring API
  async getMonitorSystems() {
    const rows = await this.request<any[]>(`${API_BASE}/monitor/systems`)
    return rows.map((row) => this.mapMonitorSystem(row))
  }

  async getMonitorSystem(systemId: string) {
    const row = await this.request<any>(`${API_BASE}/monitor/systems/${systemId}`)
    return this.mapMonitorSystem(row) as MonitorSystemDetail
  }

  async createMonitorSystem(name: string) {
    return this.request<CreateMonitorSystemResponse>(`${API_BASE}/monitor/systems`, {
      method: 'POST',
      body: JSON.stringify({ name }),
    })
  }

  async deleteMonitorSystem(systemId: string) {
    return this.request<void>(`${API_BASE}/monitor/systems/${systemId}`, {
      method: 'DELETE',
    })
  }

  async getSystemMetrics(systemId: string, from?: string, to?: string, interval?: string) {
    const params = new URLSearchParams()
    if (from) params.append('from', from)
    if (to) params.append('to', to)
    if (interval) params.append('interval', interval)
    const query = params.toString()
    return this.request<SystemMetricsHistory>(
      `${API_BASE}/monitor/systems/${systemId}/metrics${query ? `?${query}` : ''}`
    )
  }

  async getSystemContainers(systemId: string) {
    const response = await this.request<{containers: ContainerStats[]}>(
      `${API_BASE}/monitor/systems/${systemId}/containers`
    )
    return response.containers
  }

  async getContainerMetrics(
    systemId: string,
    containerName: string,
    from?: string,
    to?: string,
    interval?: string
  ) {
    const params = new URLSearchParams()
    if (from) params.append('from', from)
    if (to) params.append('to', to)
    if (interval) params.append('interval', interval)
    const query = params.toString()
    return this.request<ContainerMetricsHistory>(
      `${API_BASE}/monitor/systems/${systemId}/containers/${encodeURIComponent(containerName)}/metrics${query ? `?${query}` : ''}`
    )
  }

  async getSystemAlerts(systemId: string) {
    const config = await this.getSystemAlertConfig(systemId)
    return config.effectiveAlerts
  }

  async getSystemAlertConfig(systemId: string) {
    const response = await this.request<any>(`${API_BASE}/monitor/systems/${systemId}/alerts/config`)
    return {
      scope: (response.scope ?? 'system') as 'global' | 'system',
      globalAlerts: (response.globalAlerts ?? response.global_alerts ?? []).map((row: any) => this.mapSystemAlert(row)),
      systemAlerts: (response.systemAlerts ?? response.system_alerts ?? []).map((row: any) => this.mapSystemAlert(row)),
      effectiveAlerts: (response.effectiveAlerts ?? response.effective_alerts ?? []).map((row: any) => this.mapSystemAlert(row)),
    } as SystemAlertConfig
  }

  async updateSystemAlertScope(systemId: string, scope: 'global' | 'system') {
    return this.request<void>(`${API_BASE}/monitor/systems/${systemId}/alerts/scope`, {
      method: 'PUT',
      body: JSON.stringify({scope}),
    })
  }

  async createSystemAlert(
    systemId: string,
    alert: {
      metric: string
      condition: string
      threshold: number
      durationSeconds?: number
      enabled?: boolean
    },
    scope: 'global' | 'system' = 'system'
  ) {
    return this.request<SystemAlert>(`${API_BASE}/monitor/systems/${systemId}/alerts?scope=${scope}`, {
      method: 'POST',
      body: JSON.stringify(alert),
    })
  }

  async updateSystemAlert(
    systemId: string,
    alertId: number,
    updates: Partial<SystemAlert>,
    scope: 'global' | 'system' = 'system'
  ) {
    return this.request<SystemAlert>(`${API_BASE}/monitor/systems/${systemId}/alerts/${alertId}?scope=${scope}`, {
      method: 'PUT',
      body: JSON.stringify(updates),
    })
  }

  async deleteSystemAlert(systemId: string, alertId: number, scope: 'global' | 'system' = 'system') {
    return this.request<void>(`${API_BASE}/monitor/systems/${systemId}/alerts/${alertId}?scope=${scope}`, {
      method: 'DELETE',
    })
  }
}

export const api = new ApiClient()
export type {
  AuthResponse,
  Project,
  ProjectKey,
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
  AuthToken,
  BillingTierConfig,
  BillingPlan,
  BillingPlansResponse,
  BillingUsage,
  CheckoutSessionRequest,
  CheckoutSessionResponse,
  Invoice,
  PaymentMethod,
  SetupIntentResponse,
  CancelSubscriptionResponse,
  AdminBillingSubscription,
  CreateTierVersionRequest,
  TierMigrationResponse,
  NotificationPreference,
  ProjectNotificationPreference,
  NotificationPreferences,
  MonitorSystem,
  MonitorSystemWithMetrics,
  MonitorSystemDetail,
  HistoricalDataPoint,
  SystemMetricsHistory,
  ContainerStats,
  ContainerHistoricalDataPoint,
  ContainerMetricsHistory,
  SystemAlert,
  SystemAlertConfig,
}
