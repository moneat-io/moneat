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

import {fireEvent, screen, waitFor} from '@testing-library/react'
import {describe, expect, it, beforeEach} from 'vitest'
import userEvent from '@testing-library/user-event'
import {http, HttpResponse} from 'msw'
import {server} from '@/test/mocks/server'
import {clearAuthStorage, renderWithQueryClient} from '@/test/utils'
import {OtlpApiKeysTab} from '../OtlpApiKeysTab'

const API_BASE = 'http://localhost:8080'
const WEB_APP_RESOURCE_ID = '123e4567-e89b-12d3-a456-426614174030'
const WORKER_RESOURCE_ID = '123e4567-e89b-12d3-a456-426614174031'

function mockBaseResponses() {
  server.use(
    http.get(`${API_BASE}/v1/user`, () => {
      return HttpResponse.json({
        id: 1,
        email: 'user@example.com',
        emailVerified: true,
        onboardingCompleted: true,
        timezone: 'UTC',
      })
    }),
    http.get(`${API_BASE}/v1/logs/api-keys`, () => {
      return HttpResponse.json({
        keys: [
          {
            id: 1,
            name: 'production-key',
            key_prefix: 'motlp_prod',
            created_at: '2026-01-01T00:00:00Z',
            last_used_at: null,
          },
        ],
      })
    }),
    http.get(`${API_BASE}/v1/projects`, () => {
      return HttpResponse.json([
        {
          id: 30,
          resourceId: WEB_APP_RESOURCE_ID,
          name: 'Web App',
          slug: 'web-app',
          framework: 'react',
          keys: [],
          dsn: '',
        },
        {
          id: 31,
          resourceId: WORKER_RESOURCE_ID,
          name: 'Worker',
          slug: 'worker',
          framework: 'kotlin',
          keys: [],
          dsn: '',
        },
      ])
    }),
    http.get(`${API_BASE}/v1/otlp/services`, () => {
      return HttpResponse.json({
        services: [
          {
            id: 10,
            mapping_id: 20,
            service_namespace: 'checkout',
            service_name: 'api',
            project_id: 30,
            project_resource_id: WEB_APP_RESOURCE_ID,
            project_name: 'Web App',
            seen_logs: true,
            seen_traces: true,
            seen_metrics: false,
            seen_feedback: true,
            last_environment: 'production',
            first_seen_at: '2026-01-01T00:00:00Z',
            last_seen_at: '2026-01-01T01:00:00Z',
          },
          {
            id: 11,
            mapping_id: null,
            service_namespace: '',
            service_name: 'worker',
            project_id: null,
            project_name: null,
            seen_logs: false,
            seen_traces: false,
            seen_metrics: true,
            seen_feedback: false,
            last_environment: null,
            first_seen_at: '2026-01-01T00:00:00Z',
            last_seen_at: '2026-01-01T01:00:00Z',
          },
        ],
      })
    })
  )
}

describe('OtlpApiKeysTab', () => {
  beforeEach(() => {
    clearAuthStorage()
    if (!HTMLElement.prototype.hasPointerCapture) {
      HTMLElement.prototype.hasPointerCapture = () => false
    }
    if (!HTMLElement.prototype.setPointerCapture) {
      HTMLElement.prototype.setPointerCapture = () => {}
    }
    if (!HTMLElement.prototype.releasePointerCapture) {
      HTMLElement.prototype.releasePointerCapture = () => {}
    }
    if (!HTMLElement.prototype.scrollIntoView) {
      HTMLElement.prototype.scrollIntoView = () => {}
    }
    mockBaseResponses()
  })

  it('renders observed services with current mappings', async () => {
    renderWithQueryClient(<OtlpApiKeysTab />)

    expect(await screen.findByText('checkout/api')).toBeInTheDocument()
    expect(screen.getByText('worker')).toBeInTheDocument()
    expect(screen.getByText('Mapped to Web App')).toBeInTheDocument()
    expect(screen.getAllByText('Unmapped').length).toBeGreaterThan(0)
    expect(screen.getByText('production')).toBeInTheDocument()
    expect(screen.getByText('logs')).toBeInTheDocument()
    expect(screen.getByText('traces')).toBeInTheDocument()
    expect(screen.getByText('metrics')).toBeInTheDocument()
    expect(screen.getByText('feedback')).toBeInTheDocument()
    expect(screen.getByRole('button', {name: 'Remove mapping for checkout/api'})).toBeEnabled()
    expect(screen.getByRole('button', {name: 'Remove mapping for worker'})).toBeDisabled()
  })

  it('shows the empty observed services state', async () => {
    server.use(
      http.get(`${API_BASE}/v1/otlp/services`, () => {
        return HttpResponse.json({services: []})
      })
    )

    renderWithQueryClient(<OtlpApiKeysTab />)

    expect(await screen.findByText('Services appear here after OTLP telemetry is received.')).toBeInTheDocument()
  })

  it('removes an existing service mapping', async () => {
    let deletedMappingId: string | null = null
    server.use(
      http.delete(`${API_BASE}/v1/otlp/service-mappings/:id`, ({params}) => {
        deletedMappingId = String(params.id)
        return new HttpResponse(null, {status: 204})
      })
    )

    renderWithQueryClient(<OtlpApiKeysTab />)

    fireEvent.click(await screen.findByRole('button', {name: 'Remove mapping for checkout/api'}))

    await waitFor(() => {
      expect(deletedMappingId).toBe('20')
    })
  })

  it('maps an unmapped service from the service selector', async () => {
    let capturedBody: Record<string, unknown> | null = null
    const user = userEvent.setup()
    server.use(
      http.post(`${API_BASE}/v1/otlp/service-mappings`, async ({request}) => {
        capturedBody = (await request.json()) as Record<string, unknown>
        return HttpResponse.json({
          id: 21,
          service_namespace: '',
          service_name: 'worker',
          project_id: 31,
          project_name: 'Worker',
          updated_at: '2026-01-01T02:00:00Z',
        })
      })
    )

    renderWithQueryClient(<OtlpApiKeysTab />)

    const selectors = await screen.findAllByRole('combobox')
    await user.click(selectors[1])
    await user.click(await screen.findByRole('option', {name: 'Worker'}))

    await waitFor(() => {
      expect(capturedBody).toEqual({
        service_name: 'worker',
        service_namespace: '',
        project_resource_id: WORKER_RESOURCE_ID,
      })
    })
  })

  it('creates and revokes OTLP API keys from the tab configuration', async () => {
    let createdKeyName: string | null = null
    let revokedKeyId: string | null = null
    const user = userEvent.setup()
    server.use(
      http.post(`${API_BASE}/v1/logs/api-keys`, async ({request}) => {
        const body = (await request.json()) as Record<string, unknown>
        createdKeyName = String(body.name)
        return HttpResponse.json({
          key: 'motlp_full_secret',
          name: createdKeyName,
        })
      }),
      http.delete(`${API_BASE}/v1/logs/api-keys/:id`, ({params}) => {
        revokedKeyId = String(params.id)
        return new HttpResponse(null, {status: 204})
      })
    )

    renderWithQueryClient(<OtlpApiKeysTab />)

    await user.click(await screen.findByRole('button', {name: 'New Key'}))
    await user.type(screen.getByLabelText('Name'), 'staging otlp')
    await user.click(screen.getByRole('button', {name: 'Create'}))

    expect(await screen.findByText('motlp_full_secret')).toBeInTheDocument()
    expect(createdKeyName).toBe('staging otlp')

    await user.click(screen.getByRole('button', {name: "I've copied the key"}))
    await user.click(screen.getByRole('button', {name: 'Revoke API key production-key'}))

    expect(await screen.findByText(
      'Are you sure you want to revoke "production-key"? ' +
      'Any clients using this key will no longer be able to send data.'
    )).toBeInTheDocument()

    await user.click(screen.getByRole('button', {name: 'Revoke'}))

    await waitFor(() => {
      expect(revokedKeyId).toBe('1')
    })
  })
})
