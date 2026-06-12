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

import {QueryClient, QueryClientProvider} from '@tanstack/react-query'
import {fireEvent, render, screen, waitFor, within} from '@testing-library/react'
import {beforeEach, describe, expect, it, vi} from 'vitest'

import {SyntheticBuilder} from '../SyntheticBuilder'

const {mockApi, mockNavigate} = vi.hoisted(() => ({
  mockApi: {
    listSyntheticLocations: vi.fn(),
    listSyntheticVariables: vi.fn(),
    createSyntheticTest: vi.fn(),
    updateSyntheticTest: vi.fn(),
    previewSyntheticTest: vi.fn(),
  },
  mockNavigate: vi.fn(),
}))

vi.mock('@/lib/api', () => ({
  api: mockApi,
}))

vi.mock('@tanstack/react-router', async () => {
  const actual = await vi.importActual<typeof import('@tanstack/react-router')>('@tanstack/react-router')
  return {
    ...actual,
    useNavigate: () => mockNavigate,
  }
})

function renderBuilder() {
  const queryClient = new QueryClient({
    defaultOptions: {queries: {retry: false}, mutations: {retry: false}},
  })
  return render(
    <QueryClientProvider client={queryClient}>
      <SyntheticBuilder mode="create" />
    </QueryClientProvider>
  )
}

beforeEach(() => {
  Object.values(mockApi).forEach((mock) => mock.mockReset())
  mockNavigate.mockReset()
  mockApi.listSyntheticLocations.mockResolvedValue([
    {
      id: 'loc-1',
      code: 'aws-us-east-1',
      name: 'US East',
      region: 'N. Virginia',
      type: 'managed',
      active: true,
      workerCount: 1,
    },
  ])
  mockApi.listSyntheticVariables.mockResolvedValue([])
  mockApi.createSyntheticTest.mockResolvedValue({id: 'test-1'})
  mockApi.updateSyntheticTest.mockResolvedValue({id: 'test-1'})
  mockApi.previewSyntheticTest.mockResolvedValue({resultId: 'preview', status: 'passed'})
})

describe('SyntheticBuilder', () => {
  it('creates network checks with config hostname and clamped alert locations', async () => {
    renderBuilder()

    fireEvent.click(screen.getByRole('button', {name: /^TCP$/}))
    fireEvent.change(screen.getByLabelText('Hostname'), {target: {value: 'tcp.example.com'}})
    fireEvent.click(screen.getByRole('button', {name: /Alerting$/}))
    fireEvent.click(screen.getByRole('button', {name: 'Create test'}))

    await waitFor(() => expect(mockApi.createSyntheticTest).toHaveBeenCalled())
    const payload = mockApi.createSyntheticTest.mock.calls[0][0]
    expect(payload.testType).toBe('tcp')
    expect(payload.config).toEqual({hostname: 'tcp.example.com'})
    expect(payload.alertConfig.minLocations).toBe(1)
    expect(payload.alertConfig.totalLocations).toBe(1)
  })

  it('creates multistep tests with ordered request steps', async () => {
    renderBuilder()

    fireEvent.click(screen.getByRole('button', {name: /^Multistep$/}))
    fireEvent.change(screen.getByPlaceholderText('Step name'), {target: {value: 'Health check'}})
    fireEvent.change(screen.getByPlaceholderText('https://api.example.com/step'), {
      target: {value: 'https://api.example.com/health'},
    })
    fireEvent.click(screen.getByRole('button', {name: 'Create test'}))

    await waitFor(() => expect(mockApi.createSyntheticTest).toHaveBeenCalled())
    const payload = mockApi.createSyntheticTest.mock.calls[0][0]
    expect(payload.testType).toBe('multistep')
    expect(payload.steps).toMatchObject([
      {name: 'Health check', method: 'GET', url: 'https://api.example.com/health'},
    ])
  })

  it('lets targeted assertions choose an operator', () => {
    renderBuilder()

    fireEvent.click(screen.getByRole('button', {name: /Assertions$/}))
    const row = screen.getByText('Status code').closest('div')
    expect(row).not.toBeNull()

    const controls = within(row as HTMLElement).getAllByRole('combobox')
    fireEvent.change(controls[0], {target: {value: 'header'}})
    fireEvent.change(screen.getByPlaceholderText('header name'), {target: {value: 'content-type'}})
    fireEvent.change(controls[1], {target: {value: 'contains'}})

    expect(controls[1]).toHaveValue('contains')
  })
})
