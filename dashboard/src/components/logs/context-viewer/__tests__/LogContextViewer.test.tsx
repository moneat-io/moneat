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

import {describe, it, expect, vi} from 'vitest'
import {fireEvent, screen} from '@testing-library/react'
import {renderWithQueryClient} from '@/test/utils'
import type {LogEntry} from '@/lib/api'
import {LogContextViewer} from '../LogContextViewer'

// traceId is intentionally empty so no trace/pattern/context query fires on the
// default Content tab — this keeps the smoke test free of network.
function makeLog(overrides: Partial<LogEntry> = {}): LogEntry {
  return {
    logId: 'log-1',
    timestamp: '2026-06-08T14:32:08.412Z',
    level: 'error',
    message: 'Payment gateway timeout after 3 retries for order ord_8F2K19',
    body: '',
    service: 'payments-api',
    environment: 'production',
    host: 'ip-10-2-43-118',
    source: 'otlp',
    containerName: '',
    containerId: '',
    containerImage: '',
    traceId: '',
    spanId: '',
    tags: {'http.method': 'POST', 'http.status_code': '504'},
    resourceAttributes: {},
    ...overrides,
  }
}

function renderViewer(extra: Partial<Parameters<typeof LogContextViewer>[0]> = {}) {
  const log = makeLog()
  const props = {
    log,
    logs: [log, makeLog({logId: 'log-2', message: 'next event'})],
    index: 0,
    total: 248,
    onNavigate: vi.fn(),
    onClose: vi.fn(),
    onAddFacetFilter: vi.fn(),
    ...extra,
  }
  renderWithQueryClient(<LogContextViewer {...props} />)
  return props
}

describe('LogContextViewer', () => {
  it('renders the headline, severity, event counter and all tabs', () => {
    renderViewer()
    // The message shows in both the headline and the Content panel.
    expect(screen.getAllByText(/Payment gateway timeout after 3 retries/).length).toBeGreaterThan(0)
    expect(screen.getByText('error')).toBeInTheDocument()
    expect(screen.getByText('Event 1')).toBeInTheDocument()
    expect(screen.getByText('of 248')).toBeInTheDocument()
    for (const tab of ['Content', 'Context', 'Trace', 'Attributes', 'Patterns']) {
      expect(screen.getByText(tab)).toBeInTheDocument()
    }
  })

  it('shows grouped attributes when the Attributes tab is opened', () => {
    renderViewer()
    fireEvent.click(screen.getByText('Attributes'))
    expect(screen.getByText('http.status_code')).toBeInTheDocument()
    expect(screen.getByText('504')).toBeInTheDocument()
  })

  it('navigates events via the next button and J/K keys', () => {
    const {onNavigate} = renderViewer()
    fireEvent.click(screen.getByTitle('Next event (J)'))
    expect(onNavigate).toHaveBeenCalledWith(1)

    window.dispatchEvent(new KeyboardEvent('keydown', {key: 'j'}))
    expect(onNavigate).toHaveBeenCalledTimes(2)
  })

  it('closes on Escape', () => {
    const {onClose} = renderViewer()
    window.dispatchEvent(new KeyboardEvent('keydown', {key: 'Escape'}))
    expect(onClose).toHaveBeenCalled()
  })

  it('forwards facet filters from a chip action', () => {
    const {onAddFacetFilter} = renderViewer()
    // The service chip exposes a "Filter to" action.
    const filterButtons = screen.getAllByTitle('Filter to')
    fireEvent.click(filterButtons[0])
    expect(onAddFacetFilter).toHaveBeenCalledWith('service', 'payments-api', false)
  })
})
