const API_BASE = '/api/v1'

interface AuthResponse {
  token: string
  user: { id: number; email: string; name?: string }
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

class ApiClient {
  private getToken(): string | null {
    return localStorage.getItem('auth_token')
  }

  private async request<T>(endpoint: string, options: RequestInit = {}): Promise<T> {
    const token = this.getToken()
    const headers: HeadersInit = {
      'Content-Type': 'application/json',
      ...options.headers,
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

  async getIssues(projectId: number, page = 1, limit = 25): Promise<Issue[]> {
    return this.request<Issue[]>(
      `${API_BASE}/projects/${projectId}/issues?page=${page}&limit=${limit}`
    )
  }
}

export const api = new ApiClient()
export type { AuthResponse, Project, Issue }
