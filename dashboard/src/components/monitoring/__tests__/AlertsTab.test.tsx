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

import type {ReactNode} from 'react'
import {screen, waitFor} from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import {beforeEach, describe, expect, it, vi} from 'vitest'
import {http, HttpResponse} from 'msw'
import {server} from '@/test/mocks/server'
import {clearAuthStorage, renderWithQueryClient} from '@/test/utils'
import {AlertsTab} from '../AlertsTab'

vi.mock('@tanstack/react-router', () => ({
  Link: ({children}: {children: ReactNode}) => <a href="/settings">{children}</a>,
}))

const API_BASE = 'http://localhost:8080'
const HOST_ID = 'host-5'
const ALERT_ID = 'alert-10'

function alertResponse(enabled: boolean) {
  return {
    id: ALERT_ID,
    host_id: HOST_ID,
    scope: 'host',
    metric: 'cpu_percent',
    condition: '>',
    threshold: 90,
    duration_seconds: 60,
    enabled,
    alert_priority: null,
    last_triggered_at: null,
    created_at: 1700000000,
  }
}

function alertConfig(enabled: boolean) {
  const alert = alertResponse(enabled)
  return {
    scope: 'host',
    global_alerts: [],
    host_alerts: [alert],
    effective_alerts: [alert],
  }
}

function installPointerCaptureMocks() {
  if (!HTMLElement.prototype.hasPointerCapture) {
    HTMLElement.prototype.hasPointerCapture = () => false
  }
  if (!HTMLElement.prototype.setPointerCapture) {
    HTMLElement.prototype.setPointerCapture = () => {}
  }
  if (!HTMLElement.prototype.releasePointerCapture) {
    HTMLElement.prototype.releasePointerCapture = () => {}
  }
}

describe('AlertsTab', () => {
  beforeEach(() => {
    clearAuthStorage()
    installPointerCaptureMocks()
  })

  it('updates an alert-rule toggle from the mutation variables before refetch completes', async () => {
    const user = userEvent.setup()
    let alertEnabled = true
    let configRequests = 0
    let releaseRefetch: (() => void) | undefined
    let updateBody: Record<string, unknown> | undefined

    server.use(
      http.get(`${API_BASE}/v1/monitor/hosts/${HOST_ID}/alerts/config`, async () => {
        configRequests += 1
        if (configRequests > 1) {
          await new Promise<void>((resolve) => {
            releaseRefetch = resolve
          })
        }
        return HttpResponse.json(alertConfig(alertEnabled))
      }),
      http.put(`${API_BASE}/v1/monitor/hosts/${HOST_ID}/alerts/${ALERT_ID}`, async ({request}) => {
        updateBody = await request.json() as Record<string, unknown>
        alertEnabled = false
        return new HttpResponse(null, {status: 204})
      })
    )

    renderWithQueryClient(<AlertsTab hostId={HOST_ID} />)

    const toggle = await screen.findByRole('switch', {name: /disable cpu usage/i})
    expect(toggle).toBeChecked()

    await user.click(toggle)

    try {
      await waitFor(() => expect(toggle).not.toBeChecked(), {timeout: 500})
      expect(screen.getByText('0 of 1 rules active (host-only)')).toBeInTheDocument()
    } finally {
      releaseRefetch?.()
    }

    expect(updateBody).toEqual({enabled: false})
  })
})
