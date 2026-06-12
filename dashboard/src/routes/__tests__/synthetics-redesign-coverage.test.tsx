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

import React from 'react'
import {beforeEach, describe, expect, it, vi} from 'vitest'
import {QueryClient, QueryClientProvider} from '@tanstack/react-query'
import {fireEvent, render, screen, waitFor, within} from '@testing-library/react'

const {mockApi, mockNavigate, routeParams} = vi.hoisted(() => ({
  mockApi: {
    listSyntheticTests: vi.fn(),
    listSyntheticResults: vi.fn(),
    listSyntheticLocations: vi.fn(),
    getSyntheticTest: vi.fn(),
    getSyntheticTestSummary: vi.fn(),
    getSyntheticTestResults: vi.fn(),
    getSyntheticLocationSummaries: vi.fn(),
    getSyntheticRunDetail: vi.fn(),
    syntheticScreenshotUrl: vi.fn(),
    runSyntheticTest: vi.fn(),
    updateSyntheticTest: vi.fn(),
  },
  mockNavigate: vi.fn(),
  routeParams: {testId: 'test-1', resultId: 'run-1'},
}))

vi.mock('@/lib/api', () => ({
  api: mockApi,
}))

vi.mock('@tanstack/react-router', () => ({
  createFileRoute: () => (options: Record<string, unknown>) => ({
    ...options,
    component: options.component,
    useParams: () => routeParams,
  }),
  useNavigate: () => mockNavigate,
}))

vi.mock('recharts', () => ({
  CartesianGrid: () => <div data-testid="cartesian-grid" />,
  Line: () => <div data-testid="chart-line" />,
  LineChart: ({children}: {children: React.ReactNode}) => <div data-testid="line-chart">{children}</div>,
  ResponsiveContainer: ({children}: {children: React.ReactNode}) => <div>{children}</div>,
  Tooltip: () => <div data-testid="chart-tooltip" />,
  XAxis: () => <div data-testid="x-axis" />,
  YAxis: () => <div data-testid="y-axis" />,
}))

import {Route as SyntheticsOverviewRoute} from '../synthetics.index'
import {Route as SyntheticDetailRoute} from '../synthetics.$testId.index'
import {Route as SyntheticRunRoute} from '../synthetics.$testId.results.$resultId'

const locations = [
  {id: 'loc-managed', code: 'aws-us-east-1', name: 'US East', type: 'managed', region: 'us-east-1', workerCount: 1},
  {id: 'loc-private', code: 'private-iad', name: 'Private IAD', type: 'private', region: 'iad', workerCount: 2},
]

const tests = [
  {
    id: 'test-1',
    name: 'Checkout API',
    testType: 'api',
    active: true,
    intervalSeconds: 60,
    timeoutSeconds: 30,
    url: 'https://api.example.com/health',
    method: 'GET',
    status: 'failed',
    lastStatus: 'failed',
    lastRunAt: Date.now(),
    service: 'checkout',
    environment: 'production',
    tags: ['tier:critical'],
    locations: ['aws-us-east-1', 'private-iad'],
    alertConfig: {consecutiveChecks: 2, minLocations: 1, totalLocations: 2, retestCount: 1},
    alertRecipients: [{type: 'slack', target: '#checkout'}],
    assertions: [{type: 'status_code', operator: 'equals', value: '200'}],
  },
  {
    id: 'test-2',
    name: 'Login browser',
    testType: 'browser',
    active: false,
    intervalSeconds: 300,
    timeoutSeconds: 60,
    status: 'passed',
    lastStatus: 'passed',
    lastRunAt: null,
    service: 'web',
    environment: 'staging',
    tags: [],
    locations: ['aws-us-east-1'],
    alertRecipients: [],
  },
]

const results = [
  {
    resultId: 'run-1',
    testId: 'test-1',
    testName: 'Checkout API',
    status: 'failed',
    probeDc: 'aws-us-east-1',
    locationCode: 'aws-us-east-1',
    durationMs: 780,
    errorMessage: 'Assertion failed',
    statusCode: 503,
    assertionsTotal: 1,
    assertionsFailed: 1,
    timestamp: new Date().toISOString(),
  },
  {
    resultId: 'run-2',
    testId: 'test-1',
    testName: 'Checkout API',
    status: 'passed',
    probeDc: 'private-iad',
    locationCode: 'private-iad',
    durationMs: 120,
    errorMessage: '',
    statusCode: 200,
    assertionsTotal: 1,
    assertionsFailed: 0,
    timestamp: new Date(Date.now() - 60_000).toISOString(),
  },
]

function renderRoute(Component: React.ComponentType) {
  const queryClient = new QueryClient({
    defaultOptions: {queries: {retry: false}, mutations: {retry: false}},
  })
  return render(
    <QueryClientProvider client={queryClient}>
      <Component />
    </QueryClientProvider>
  )
}

function routeComponent(route: unknown): React.ComponentType {
  return (route as {component: React.ComponentType}).component
}

beforeEach(() => {
  mockNavigate.mockReset()
  mockApi.listSyntheticTests.mockResolvedValue(tests)
  mockApi.listSyntheticResults.mockResolvedValue({results, totalCount: results.length})
  mockApi.listSyntheticLocations.mockResolvedValue(locations)
  mockApi.getSyntheticTest.mockResolvedValue(tests[0])
  mockApi.getSyntheticTestSummary.mockResolvedValue({
    testId: 'test-1',
    uptimePercent: 95.5,
    avgResponseMs: 450,
    p95ResponseMs: 780,
    totalRuns: 2,
    failureCount: 1,
  })
  mockApi.getSyntheticTestResults.mockResolvedValue({results, totalCount: results.length})
  mockApi.getSyntheticLocationSummaries.mockResolvedValue([
    {locationCode: 'aws-us-east-1', uptimePercent: 0, p95ResponseMs: 780},
    {locationCode: 'private-iad', uptimePercent: 100, p95ResponseMs: 120},
  ])
  mockApi.getSyntheticRunDetail.mockResolvedValue({
    resultId: 'run-1',
    testId: 'test-1',
    testName: 'Login browser',
    status: 'failed',
    locationCode: 'aws-us-east-1',
    durationMs: 1800,
    statusCode: 0,
    errorMessage: 'Step 2 failed',
    attempt: 2,
    timestamp: new Date().toISOString(),
    detail: {
      browser: {
        failedStep: 2,
        viewport: '1280 x 800',
        browser: 'Chromium',
        steps: [
          {action: 'navigate', label: 'Open app', status: 'passed', durationMs: 300, screenshotKey: 'synthetics/1/run/step-1.png'},
          {action: 'click', label: 'Click login', status: 'failed', durationMs: 1500, screenshotKey: 'synthetics/1/run/step-2.png', errorMessage: 'not found'},
          {action: 'assert', label: 'Dashboard visible', status: 'skipped'},
        ],
        console: [{level: 'error', text: 'button not found'}],
        network: [{method: 'GET', url: 'https://example.com', status: 500, durationMs: 120}],
      },
    },
  })
  mockApi.syntheticScreenshotUrl.mockImplementation((key: string) => `/v1/synthetics/screenshots/${key}`)
  mockApi.runSyntheticTest.mockResolvedValue(undefined)
  mockApi.updateSyntheticTest.mockResolvedValue(tests[0])
})

describe('synthetics redesign routes', () => {
  it('renders overview health, filters, and bulk actions', async () => {
    renderRoute(routeComponent(SyntheticsOverviewRoute))

    expect(await screen.findByText('Synthetic Monitoring')).toBeInTheDocument()
    expect(await screen.findAllByText('Checkout API')).not.toHaveLength(0)
    expect(screen.getByText('Needs attention')).toBeInTheDocument()

    fireEvent.click(screen.getByRole('button', {name: /^Failing\s*1$/}))
    expect(screen.getByText('1 of 2 tests')).toBeInTheDocument()

    fireEvent.click(screen.getByLabelText('Select all'))
    fireEvent.click(screen.getByRole('button', {name: 'Run'}))

    await waitFor(() => {
      expect(mockApi.runSyntheticTest).toHaveBeenCalledWith('test-1')
    })
  })

  it('renders detail stats, location health, alerting, and navigation', async () => {
    renderRoute(routeComponent(SyntheticDetailRoute))

    expect(await screen.findByText('Checkout API')).toBeInTheDocument()
    expect(screen.getByText(/Failing from US East/)).toBeInTheDocument()
    expect(screen.getByText('Availability by location')).toBeInTheDocument()
    expect(screen.getByText('#checkout')).toBeInTheDocument()

    fireEvent.click(screen.getByRole('button', {name: /view failing run/i}))
    expect(mockNavigate).toHaveBeenCalledWith({
      to: '/synthetics/$testId/results/$resultId',
      params: {testId: 'test-1', resultId: 'run-1'},
    })
  })

  it('renders browser run drill-in artifacts', async () => {
    renderRoute(routeComponent(SyntheticRunRoute))

    expect(await screen.findByText('Run detail')).toBeInTheDocument()
    expect(screen.getByText('Failed at step 2')).toBeInTheDocument()
    expect(screen.getByText('Screenshots')).toBeInTheDocument()
    expect(screen.getByText('button not found')).toBeInTheDocument()

    const network = screen.getByText('Network').closest('div')?.parentElement
    expect(network ? within(network).getByText('500') : null).toBeInTheDocument()
  })
})
