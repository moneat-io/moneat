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

import {beforeEach, describe, expect, it, vi} from 'vitest'
import {fireEvent, screen, waitFor} from '@testing-library/react'
import {renderWithQueryClient} from '@/test/utils'
import {IncidentSourcesPanel} from '../IncidentSourcesPanel'

const INCIDENT_ID = 'd0000000-0000-4000-8000-000000000009'

const {mockApi} = vi.hoisted(() => ({
  mockApi: {
    getIncidentSources: vi.fn(),
    linkIncidentSource: vi.fn(),
    unlinkIncidentSource: vi.fn(),
  },
}))

vi.mock('@/lib/api', () => ({api: mockApi}))

const SAFE_URL = 'https://runbook.example.com/runbooks/db'

describe('IncidentSourcesPanel', () => {
  beforeEach(() => {
    vi.clearAllMocks()
    mockApi.getIncidentSources.mockResolvedValue([
      {
        id: 's1',
        sourceType: 'URL',
        sourceKey: SAFE_URL,
        sourceUrl: SAFE_URL,
        label: 'Runbook',
        metadata: {},
        createdAt: '2026-06-05T12:00:00.000Z',
      },
    ])
    mockApi.linkIncidentSource.mockResolvedValue([])
    mockApi.unlinkIncidentSource.mockResolvedValue({message: 'Incident source removed'})
  })

  it('lists linked sources as safe links and unlinks one', async () => {
    renderWithQueryClient(<IncidentSourcesPanel incidentId={INCIDENT_ID} />)
    expect(await screen.findByText('Runbook')).toBeInTheDocument()
    const link = screen.getByRole('link', {name: /runbooks\/db/})
    expect(link).toHaveAttribute('href', SAFE_URL)
    fireEvent.click(screen.getByRole('button', {name: 'Unlink source'}))
    await waitFor(() => expect(mockApi.unlinkIncidentSource).toHaveBeenCalledWith(INCIDENT_ID, 's1'))
  })

  it('links a normalized http(s) URL source', async () => {
    renderWithQueryClient(<IncidentSourcesPanel incidentId={INCIDENT_ID} />)
    await screen.findByText('Runbook')
    fireEvent.click(screen.getByRole('button', {name: /Link/}))
    fireEvent.change(await screen.findByPlaceholderText('https://…'), {
      target: {value: 'https://status.example.com/incident/42'},
    })
    fireEvent.click(screen.getByRole('button', {name: 'Link source'}))
    await waitFor(() =>
      expect(mockApi.linkIncidentSource).toHaveBeenCalledWith(
        INCIDENT_ID,
        expect.objectContaining({sourceType: 'URL', sourceUrl: 'https://status.example.com/incident/42'})
      )
    )
  })

  it('rejects a non-http(s) URL with an inline error and does not submit', async () => {
    renderWithQueryClient(<IncidentSourcesPanel incidentId={INCIDENT_ID} />)
    await screen.findByText('Runbook')
    fireEvent.click(screen.getByRole('button', {name: /Link/}))
    fireEvent.change(await screen.findByPlaceholderText('https://…'), {
      target: {value: 'javascript:alert(1)'},
    })
    fireEvent.click(screen.getByRole('button', {name: 'Link source'}))
    expect(await screen.findByText(/valid http/i)).toBeInTheDocument()
    expect(mockApi.linkIncidentSource).not.toHaveBeenCalled()
  })

  it('renders an unsafe stored URL as inert text, never a link', async () => {
    mockApi.getIncidentSources.mockResolvedValue([
      {
        id: 's2',
        sourceType: 'URL',
        sourceKey: 'evil',
        sourceUrl: 'javascript:alert(1)',
        label: 'Suspicious',
        metadata: {},
        createdAt: '2026-06-05T12:00:00.000Z',
      },
    ])
    renderWithQueryClient(<IncidentSourcesPanel incidentId={INCIDENT_ID} />)
    expect(await screen.findByText('Suspicious')).toBeInTheDocument()
    expect(screen.queryByRole('link')).not.toBeInTheDocument()
  })
})
