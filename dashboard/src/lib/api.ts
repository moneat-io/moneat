const API_BASE = '/api/v1'

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
}

class ApiClient {
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
    if (!response.ok) throw new Error(`API Error: ${response.statusText}`)
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
}

export const api = new ApiClient()
export type { AuthResponse, Project, Issue, IssueDetail, Event, ProjectStats, TimelinePoint, TopIssue }
