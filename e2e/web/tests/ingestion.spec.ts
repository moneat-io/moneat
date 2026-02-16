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

import { test, expect } from '@playwright/test'

test.describe('Event Ingestion and Issue Display @smoke', () => {
  let authToken: string
  let projectId: string
  let projectKey: string

  test.beforeAll(async ({ request }) => {
    // Login via API to get auth token
    const loginResponse = await request.post('http://localhost:8080/auth/login', {
      data: {
        email: 'e2e-test@moneat.dev',
        password: 'e2e-test-password'
      }
    })

    expect(loginResponse.ok()).toBeTruthy()
    const loginData = await loginResponse.json()
    authToken = loginData.token

    // Get project ID from projects list
    const projectsResponse = await request.get('http://localhost:8080/v1/projects', {
      headers: {
        'Authorization': `Bearer ${authToken}`
      }
    })

    expect(projectsResponse.ok()).toBeTruthy()
    const projects = await projectsResponse.json()
    const testProject = projects.find((p: any) => p.name === 'Android E2E App')
    
    expect(testProject).toBeDefined()
    projectId = testProject.id

    // Get project key (DSN)
    const keysResponse = await request.get(`http://localhost:8080/v1/projects/${projectId}/keys`, {
      headers: {
        'Authorization': `Bearer ${authToken}`
      }
    })

    expect(keysResponse.ok()).toBeTruthy()
    const keys = await keysResponse.json()
    expect(keys.length).toBeGreaterThan(0)
    projectKey = keys[0].publicKey
  })

  test('ingest event and verify issue appears in dashboard', async ({ page, request }) => {
    const timestamp = Date.now()
    const errorMessage = `E2E Test Error ${timestamp}`

    // Send Sentry envelope via ingestion endpoint
    const envelope = [
      JSON.stringify({
        event_id: `${timestamp}000000000000000000000000`,
        sent_at: new Date().toISOString(),
      }),
      JSON.stringify({
        type: 'event',
      }),
      JSON.stringify({
        event_id: `${timestamp}000000000000000000000000`,
        timestamp: Math.floor(Date.now() / 1000),
        platform: 'javascript',
        level: 'error',
        exception: {
          values: [
            {
              type: 'Error',
              value: errorMessage,
              stacktrace: {
                frames: [
                  {
                    filename: 'app.js',
                    function: 'handleClick',
                    lineno: 42,
                    colno: 15,
                    abs_path: '/src/app.js',
                    in_app: true,
                  },
                  {
                    filename: 'main.js',
                    function: 'main',
                    lineno: 10,
                    colno: 5,
                    abs_path: '/src/main.js',
                    in_app: true,
                  }
                ]
              }
            }
          ]
        },
        tags: {
          environment: 'e2e-test',
          'test-run': timestamp.toString()
        },
        user: {
          id: 'e2e-user-123',
          email: 'e2e-user@example.com'
        }
      })
    ].join('\n')

    // Send ingestion request
    const ingestResponse = await request.post(`http://localhost:8080/api/${projectId}/envelope/`, {
      headers: {
        'Content-Type': 'application/x-sentry-envelope',
        'X-Sentry-Auth': `Sentry sentry_version=7, sentry_key=${projectKey}, sentry_client=e2e-test/1.0.0`
      },
      data: envelope
    })

    expect(ingestResponse.status()).toBe(200)

    // Login to dashboard
    await page.goto('/login')
    await page.fill('input[type="email"]', 'e2e-test@moneat.dev')
    await page.fill('input[type="password"]', 'e2e-test-password')
    await page.click('button[type="submit"]')
    await expect(page).toHaveURL(/\/dashboard|\/projects/, { timeout: 10000 })

    // Navigate to project issues
    await page.goto(`/projects/${projectId}`)
    await page.waitForLoadState('networkidle')

    // Wait for and verify issue appears
    const issueLocator = page.locator(`text="${errorMessage}"`)
    await expect(issueLocator).toBeVisible({ timeout: 15000 })

    // Click on issue to view details
    await issueLocator.click()
    await page.waitForLoadState('networkidle')

    // Verify issue details page shows stack trace
    await expect(page.locator('text=app.js')).toBeVisible({ timeout: 5000 })
    await expect(page.locator('text=handleClick')).toBeVisible({ timeout: 5000 })
  })

  test('verify event metadata is displayed', async ({ page, request }) => {
    const timestamp = Date.now()
    const eventId = `${timestamp}100000000000000000000000`

    const envelope = [
      JSON.stringify({ event_id: eventId, sent_at: new Date().toISOString() }),
      JSON.stringify({ type: 'event' }),
      JSON.stringify({
        event_id: eventId,
        timestamp: Math.floor(Date.now() / 1000),
        platform: 'javascript',
        level: 'warning',
        message: `E2E Metadata Test ${timestamp}`,
        tags: {
          browser: 'chrome',
          version: '1.2.3',
          custom_tag: 'e2e-value'
        },
        extra: {
          custom_data: 'test-data',
          request_id: 'req-123'
        },
        user: {
          id: 'user-456',
          email: 'test@example.com',
          username: 'testuser'
        },
        breadcrumbs: [
          {
            timestamp: Math.floor(Date.now() / 1000) - 10,
            category: 'navigation',
            message: 'User clicked button',
            level: 'info'
          }
        ]
      })
    ].join('\n')

    await request.post(`http://localhost:8080/api/${projectId}/envelope/`, {
      headers: {
        'Content-Type': 'application/x-sentry-envelope',
        'X-Sentry-Auth': `Sentry sentry_version=7, sentry_key=${projectKey}`
      },
      data: envelope
    })

    // Login and navigate
    await page.goto('/login')
    await page.fill('input[type="email"]', 'e2e-test@moneat.dev')
    await page.fill('input[type="password"]', 'e2e-test-password')
    await page.click('button[type="submit"]')
    await page.goto(`/projects/${projectId}`)

    // Find and open the event
    const eventLocator = page.locator(`text="E2E Metadata Test ${timestamp}"`)
    await expect(eventLocator).toBeVisible({ timeout: 15000 })
    await eventLocator.click()

    // Verify tags are displayed
    await expect(page.locator('text=browser')).toBeVisible({ timeout: 5000 })
    await expect(page.locator('text=chrome')).toBeVisible()

    // Verify user info
    await expect(page.locator('text=user-456')).toBeVisible()
  })
})
