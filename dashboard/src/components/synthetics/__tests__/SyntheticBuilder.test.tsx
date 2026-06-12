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

import type {SyntheticTestResponse} from '@/lib/api'
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

function renderBuilder({
  mode = 'create',
  initial,
}: {
  mode?: 'create' | 'edit'
  initial?: SyntheticTestResponse
} = {}) {
  const queryClient = new QueryClient({
    defaultOptions: {queries: {retry: false}, mutations: {retry: false}},
  })
  return render(
    <QueryClientProvider client={queryClient}>
      <SyntheticBuilder mode={mode} initial={initial} />
    </QueryClientProvider>
  )
}

function baseTest(overrides: Partial<SyntheticTestResponse> = {}): SyntheticTestResponse {
  return {
    id: 'test-1',
    organizationId: 'org-1',
    name: 'Checkout API',
    testType: 'api',
    active: true,
    intervalSeconds: 300,
    timeoutSeconds: 30,
    url: 'https://api.example.com/health',
    method: 'GET',
    assertions: [{type: 'status_code', operator: 'equals', value: '200'}],
    steps: [],
    status: 'active',
    lastRunAt: null,
    lastStatus: 'passed',
    tags: [],
    alertRecipients: [],
    locations: ['aws-us-east-1'],
    createdAt: 1,
    updatedAt: 1,
    ...overrides,
  }
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
    {
      id: 'loc-2',
      code: 'private-iad',
      name: 'Private IAD',
      region: 'iad',
      type: 'private',
      active: true,
      workerCount: 2,
    },
  ])
  mockApi.listSyntheticVariables.mockResolvedValue([{id: 'var-1', name: 'API_TOKEN', value: '********', isSecret: true}])
  mockApi.createSyntheticTest.mockResolvedValue({id: 'test-1'})
  mockApi.updateSyntheticTest.mockResolvedValue({id: 'test-1'})
  mockApi.previewSyntheticTest.mockResolvedValue({
    resultId: 'preview',
    testId: 'test-1',
    testName: 'Preview',
    testType: 'api',
    status: 'passed',
    locationCode: 'moneat',
    durationMs: 12,
    statusCode: 200,
    attempt: 1,
    assertionsTotal: 0,
    assertionsFailed: 0,
    errorMessage: '',
    timestamp: new Date().toISOString(),
  })
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

  it('submits api checks with headers, body, private locations, and metadata', async () => {
    renderBuilder()

    expect(await screen.findByText('{{API_TOKEN}}')).toBeInTheDocument()

    fireEvent.change(screen.getByLabelText('Endpoint'), {target: {value: 'https://api.example.com/orders'}})
    fireEvent.change(screen.getAllByRole('combobox')[0], {target: {value: 'POST'}})
    fireEvent.change(screen.getByLabelText('Request body'), {target: {value: '{"ok":true}'}})
    fireEvent.click(screen.getByRole('button', {name: 'Add header'}))
    fireEvent.change(screen.getByPlaceholderText('Header'), {target: {value: 'Authorization'}})
    fireEvent.change(screen.getByPlaceholderText('Value'), {target: {value: 'Bearer {{API_TOKEN}}'}})

    fireEvent.click(screen.getByRole('button', {name: /Locations$/}))
    expect(await screen.findByText('Private locations')).toBeInTheDocument()
    fireEvent.click(screen.getByRole('button', {name: /Private IAD/}))

    fireEvent.click(screen.getByRole('button', {name: /Alerting$/}))
    fireEvent.click(screen.getByRole('button', {name: 'Add recipient'}))
    fireEvent.change(screen.getByPlaceholderText('#channel or email'), {target: {value: '#synthetics'}})

    fireEvent.click(screen.getByRole('button', {name: /Schedule$/}))
    fireEvent.change(screen.getByLabelText('Run every'), {target: {value: '60'}})
    fireEvent.change(screen.getByLabelText('Timeout'), {target: {value: '120'}})
    fireEvent.change(screen.getByLabelText('Service'), {target: {value: 'checkout'}})
    fireEvent.change(screen.getByLabelText('Environment'), {target: {value: 'staging'}})
    const tagInput = screen.getByLabelText('Tags')
    fireEvent.change(tagInput, {target: {value: 'tier:critical'}})
    fireEvent.keyDown(tagInput, {key: 'Enter', code: 'Enter'})

    fireEvent.click(screen.getByRole('button', {name: 'Create test'}))

    await waitFor(() => expect(mockApi.createSyntheticTest).toHaveBeenCalled())
    const payload = mockApi.createSyntheticTest.mock.calls[0][0]
    expect(payload).toMatchObject({
      method: 'POST',
      body: '{"ok":true}',
      headers: {Authorization: 'Bearer {{API_TOKEN}}'},
      locations: ['aws-us-east-1', 'private-iad'],
      service: 'checkout',
      environment: 'staging',
      tags: ['tier:critical'],
      intervalSeconds: 60,
      timeoutSeconds: 120,
    })
    expect(payload.alertRecipients).toEqual([{type: 'slack', target: '#synthetics'}])
  })

  it('previews browser journeys and submits edited tests', async () => {
    mockApi.previewSyntheticTest.mockResolvedValueOnce({
      resultId: 'preview',
      testId: 'test-1',
      testName: 'Preview',
      testType: 'browser',
      status: 'failed',
      locationCode: 'aws-us-east-1',
      durationMs: 3400,
      statusCode: 0,
      attempt: 1,
      assertionsTotal: 1,
      assertionsFailed: 1,
      errorMessage: 'button missing',
      timestamp: new Date().toISOString(),
      detail: {
        timings: {load: 1200},
        assertions: [{label: 'Status code', expected: '200', actual: '500', passed: false}],
        browser: {
          steps: [
            {action: 'navigate', label: 'Open checkout', status: 'passed', durationMs: 400},
            {action: 'click', label: 'Buy button', status: 'failed', durationMs: 3000},
            {action: 'assert', label: 'Receipt visible', status: 'skipped'},
          ],
        },
        response: {body: 'login failed'},
      },
    })

    renderBuilder({
      mode: 'edit',
      initial: baseTest({
        testType: 'browser',
        browserSteps: [{action: 'click', selector: '#buy', value: 'Buy'}],
      }),
    })

    fireEvent.change(screen.getByLabelText('Starting URL'), {target: {value: 'https://shop.example.com'}})
    fireEvent.click(screen.getByRole('button', {name: 'Add step'}))
    const stepActions = screen.getAllByRole('combobox')
    fireEvent.change(stepActions[stepActions.length - 2], {target: {value: 'wait'}})
    fireEvent.change(screen.getByPlaceholderText('ms'), {target: {value: '500'}})

    fireEvent.click(screen.getByRole('button', {name: 'Run it now'}))
    expect(await screen.findByText('button missing')).toBeInTheDocument()
    expect(screen.getByText('Receipt visible')).toBeInTheDocument()
    expect(mockApi.previewSyntheticTest).toHaveBeenCalledWith(
      expect.objectContaining({
        testType: 'browser',
        url: 'https://shop.example.com',
        browserSteps: expect.arrayContaining([
          expect.objectContaining({action: 'click', selector: '#buy'}),
          expect.objectContaining({action: 'wait', value: '500'}),
        ]),
      }),
      'aws-us-east-1'
    )

    fireEvent.click(screen.getByRole('button', {name: 'Save changes'}))

    await waitFor(() => expect(mockApi.updateSyntheticTest).toHaveBeenCalled())
    expect(mockApi.updateSyntheticTest).toHaveBeenCalledWith(
      'test-1',
      expect.objectContaining({testType: 'browser', url: 'https://shop.example.com'})
    )
  })
})
