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
import type {CustomDataSourceResponse} from '@/lib/api'
import {DataSourceConnectDialog} from '../DataSourceConnectDialog'

const {mockApi} = vi.hoisted(() => ({
  mockApi: {
    createCustomDataSource: vi.fn(),
    updateCustomDataSource: vi.fn(),
    testDataSourceConnection: vi.fn(),
  },
}))

vi.mock('@/lib/api', () => ({api: mockApi}))

function pickPrometheus() {
  fireEvent.click(screen.getByRole('button', {name: /Prometheus/}))
}

beforeEach(() => {
  vi.clearAllMocks()
  mockApi.createCustomDataSource.mockResolvedValue({id: 1})
  mockApi.updateCustomDataSource.mockResolvedValue({id: 1})
  mockApi.testDataSourceConnection.mockResolvedValue({success: true, message: 'Connected', metrics: ['up']})
})

describe('DataSourceConnectDialog', () => {
  it('shows the picker then the configure step on pick', () => {
    renderWithQueryClient(<DataSourceConnectDialog mode="create" onClose={vi.fn()} />)
    expect(screen.getByText('Databases')).toBeInTheDocument()
    expect(screen.getByRole('button', {name: /PostgreSQL/})).toBeInTheDocument()

    fireEvent.click(screen.getByRole('button', {name: /PostgreSQL/}))
    expect(screen.getByLabelText('Host')).toBeInTheDocument()
    expect(screen.getByRole('button', {name: 'Add data source'})).toBeDisabled()
  })

  it('links the setup guide to real documentation', () => {
    renderWithQueryClient(<DataSourceConnectDialog mode="create" onClose={vi.fn()} />)
    pickPrometheus()
    expect(screen.getByRole('link', {name: /Setup guide/})).toHaveAttribute('href', '/docs')
  })

  it('smart-splits a pasted URL out of the host field', () => {
    renderWithQueryClient(<DataSourceConnectDialog mode="create" onClose={vi.fn()} />)
    pickPrometheus()
    fireEvent.change(screen.getByLabelText('Host'), {
      target: {value: 'https://prom.example.com:9090/prometheus'},
    })
    expect((screen.getByLabelText('Host') as HTMLInputElement).value).toBe('prom.example.com')
    expect((screen.getByLabelText(/^Port/) as HTMLInputElement).value).toBe('9090')
    expect(screen.getByText(/Split your pasted value/)).toBeInTheDocument()
  })

  it('rejects an out-of-range port and blocks save', () => {
    renderWithQueryClient(<DataSourceConnectDialog mode="create" onClose={vi.fn()} />)
    pickPrometheus()
    fireEvent.change(screen.getByLabelText('Host'), {target: {value: 'prom'}})
    fireEvent.change(screen.getByLabelText(/^Port/), {target: {value: '80808080'}})
    expect(screen.getByText(/between 1 and 65535/)).toBeInTheDocument()
    expect(screen.getByRole('button', {name: 'Add data source'})).toBeDisabled()
  })

  it('reveals bearer-token fields when auth method changes', () => {
    renderWithQueryClient(<DataSourceConnectDialog mode="create" onClose={vi.fn()} />)
    pickPrometheus()
    fireEvent.change(screen.getByLabelText('Authentication'), {target: {value: 'bearer'}})
    expect(screen.getByLabelText('Bearer token')).toBeInTheDocument()
  })

  it('tests the connection and shows the result', async () => {
    renderWithQueryClient(<DataSourceConnectDialog mode="create" onClose={vi.fn()} />)
    pickPrometheus()
    fireEvent.change(screen.getByLabelText('Host'), {target: {value: 'prom'}})
    fireEvent.click(screen.getByRole('button', {name: /Test connection/}))
    await waitFor(() => expect(mockApi.testDataSourceConnection).toHaveBeenCalled())
    expect(await screen.findByText('Connected')).toBeInTheDocument()
  })

  it('loads a BigQuery service account JSON file into the form', async () => {
    renderWithQueryClient(<DataSourceConnectDialog mode="create" onClose={vi.fn()} />)
    fireEvent.click(screen.getByRole('button', {name: /BigQuery/}))
    const file = new File(['{"type":"service_account","project_id":"demo"}'], 'key.json', {
      type: 'application/json',
    })

    fireEvent.change(screen.getByLabelText('Upload JSON key file'), {target: {files: [file]}})

    await waitFor(() => {
      expect(screen.getByLabelText(/paste the JSON/)).toHaveValue('{"type":"service_account","project_id":"demo"}')
    })
  })

  it('creates a source with structured extra_config', async () => {
    const onClose = vi.fn()
    renderWithQueryClient(<DataSourceConnectDialog mode="create" onClose={onClose} />)
    pickPrometheus()
    fireEvent.change(screen.getByLabelText('Host'), {target: {value: 'prom'}})
    fireEvent.click(screen.getByRole('button', {name: 'Add data source'}))
    await waitFor(() => expect(mockApi.createCustomDataSource).toHaveBeenCalled())
    const payload = mockApi.createCustomDataSource.mock.calls[0][0]
    expect(payload).toMatchObject({source_type: 'prometheus', host: 'prom'})
    expect(payload.extra_config).toMatchObject({scheme: 'https'})
    await waitFor(() => expect(onClose).toHaveBeenCalled())
  })

  it('hydrates and updates an existing source in edit mode', async () => {
    const initial: CustomDataSourceResponse = {
      id: '00000000-0000-0000-0000-000000000007',
      org_id: '11111111-1111-4111-8111-111111111111',
      name: 'Edge Prom',
      source_type: 'prometheus',
      host: 'prom.example.com', port: 9090, extra_config: {scheme: 'http'},
      enabled: true,
      created_by: '22222222-2222-4222-8222-222222222222',
      created_at: '',
      updated_at: '',
      has_credentials: true,
    }
    renderWithQueryClient(<DataSourceConnectDialog mode="edit" initial={initial} onClose={vi.fn()} />)
    expect((screen.getByLabelText('Host') as HTMLInputElement).value).toBe('prom.example.com')
    fireEvent.change(screen.getByLabelText('Display name'), {target: {value: 'Renamed'}})
    fireEvent.click(screen.getByRole('button', {name: 'Save changes'}))
    await waitFor(() =>
      expect(mockApi.updateCustomDataSource).toHaveBeenCalledWith(
        '00000000-0000-0000-0000-000000000007',
        expect.objectContaining({name: 'Renamed'})
      )
    )
  })

  it('creates a Prometheus source with no port when the field is left blank', async () => {
    renderWithQueryClient(<DataSourceConnectDialog mode="create" onClose={vi.fn()} />)
    pickPrometheus()
    fireEvent.change(screen.getByLabelText('Host'), {target: {value: 'prometheus.bandapella.com'}})
    expect((screen.getByLabelText(/^Port/) as HTMLInputElement).value).toBe('')
    fireEvent.click(screen.getByRole('button', {name: 'Add data source'}))

    await waitFor(() => expect(mockApi.createCustomDataSource).toHaveBeenCalled())
    const payload = mockApi.createCustomDataSource.mock.calls[0][0]
    expect(payload.host).toBe('prometheus.bandapella.com')
    expect(payload.port).toBeUndefined()
    expect('clear_port' in payload).toBe(false)
  })

  it('clears a stored port when the user blanks it in edit mode', async () => {
    const initial: CustomDataSourceResponse = {
      id: '00000000-0000-0000-0000-000000000008',
      org_id: '11111111-1111-4111-8111-111111111111',
      name: 'Edge Prom',
      source_type: 'prometheus',
      host: 'prom.example.com', port: 9090, extra_config: {scheme: 'https'},
      enabled: true,
      created_by: '22222222-2222-4222-8222-222222222222',
      created_at: '',
      updated_at: '',
      has_credentials: true,
    }
    renderWithQueryClient(<DataSourceConnectDialog mode="edit" initial={initial} onClose={vi.fn()} />)
    expect((screen.getByLabelText(/^Port/) as HTMLInputElement).value).toBe('9090')

    fireEvent.change(screen.getByLabelText(/^Port/), {target: {value: ''}})
    fireEvent.click(screen.getByRole('button', {name: 'Save changes'}))

    await waitFor(() => expect(mockApi.updateCustomDataSource).toHaveBeenCalled())
    const [id, payload] = mockApi.updateCustomDataSource.mock.calls[0]
    expect(id).toBe('00000000-0000-0000-0000-000000000008')
    expect(payload.clear_port).toBe(true)
    expect(payload.port).toBeUndefined()
  })
})
