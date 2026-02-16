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

import { APIRequestContext } from '@playwright/test'

export interface AuthResponse {
  token: string
  user: {
    id: string
    email: string
  }
}

export interface Project {
  id: string
  name: string
  platform: string
}

export interface ProjectKey {
  id: string
  publicKey: string
  secretKey: string
}

export class MoneatAPI {
  constructor(private request: APIRequestContext, private baseURL: string = 'http://localhost:8080') {}

  async login(email: string, password: string): Promise<AuthResponse> {
    const response = await this.request.post(`${this.baseURL}/auth/login`, {
      data: { email, password }
    })

    if (!response.ok()) {
      throw new Error(`Login failed: ${response.status()} ${await response.text()}`)
    }

    return response.json()
  }

  async getProjects(token: string): Promise<Project[]> {
    const response = await this.request.get(`${this.baseURL}/v1/projects`, {
      headers: { 'Authorization': `Bearer ${token}` }
    })

    if (!response.ok()) {
      throw new Error(`Get projects failed: ${response.status()}`)
    }

    return response.json()
  }

  async getProjectKeys(token: string, projectId: string): Promise<ProjectKey[]> {
    const response = await this.request.get(`${this.baseURL}/v1/projects/${projectId}/keys`, {
      headers: { 'Authorization': `Bearer ${token}` }
    })

    if (!response.ok()) {
      throw new Error(`Get project keys failed: ${response.status()}`)
    }

    return response.json()
  }

  async ingestEvent(projectId: string, publicKey: string, event: any): Promise<void> {
    const eventId = event.event_id || this.generateEventId()
    
    const envelope = [
      JSON.stringify({
        event_id: eventId,
        sent_at: new Date().toISOString(),
      }),
      JSON.stringify({
        type: 'event',
      }),
      JSON.stringify({
        ...event,
        event_id: eventId,
        timestamp: event.timestamp || Math.floor(Date.now() / 1000),
      })
    ].join('\n')

    const response = await this.request.post(`${this.baseURL}/api/${projectId}/envelope/`, {
      headers: {
        'Content-Type': 'application/x-sentry-envelope',
        'X-Sentry-Auth': `Sentry sentry_version=7, sentry_key=${publicKey}, sentry_client=moneat-e2e/1.0.0`
      },
      data: envelope
    })

    if (!response.ok()) {
      throw new Error(`Ingest failed: ${response.status()} ${await response.text()}`)
    }
  }

  private generateEventId(): string {
    return Date.now().toString() + '0'.repeat(32 - Date.now().toString().length)
  }
}

export async function waitForIssueToAppear(
  request: APIRequestContext,
  token: string,
  projectId: string,
  errorMessage: string,
  timeoutMs: number = 15000
): Promise<boolean> {
  const startTime = Date.now()
  
  while (Date.now() - startTime < timeoutMs) {
    try {
      const response = await request.get(`http://localhost:8080/v1/projects/${projectId}/issues`, {
        headers: { 'Authorization': `Bearer ${token}` }
      })

      if (response.ok()) {
        const issues = await response.json()
        const found = issues.some((issue: any) => 
          issue.title?.includes(errorMessage) || 
          issue.message?.includes(errorMessage)
        )
        
        if (found) return true
      }
    } catch (e) {
      // Continue waiting
    }

    await new Promise(resolve => setTimeout(resolve, 1000))
  }

  return false
}
