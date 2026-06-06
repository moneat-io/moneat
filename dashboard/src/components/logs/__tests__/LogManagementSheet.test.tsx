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
import {beforeEach, describe, expect, it, vi} from 'vitest'
import {http, HttpResponse} from 'msw'
import {server} from '@/test/mocks/server'
import {clearAuthStorage, renderWithQueryClient} from '@/test/utils'
import {LogManagementSheet} from '@/components/logs/LogManagementSheet'
import type {LogSavedViewState} from '@/lib/api'

const API_BASE = 'http://localhost:8080'

const currentViewState: LogSavedViewState = {
  query: 'service:api',
  levels: ['error'],
  facets: {service: 'api'},
  time_preset: '1h',
  from: null,
  to: null,
  visualization: 'timeseries',
  group_by: 'service',
  top_field: 'level',
}

const savedViewState: LogSavedViewState = {
  query: 'level:error',
  levels: ['error'],
  facets: {environment: 'prod'},
  time_preset: '15m',
  from: null,
  to: null,
  visualization: 'table',
  group_by: 'environment',
  top_field: 'service',
}

const currentLogs = [
  {
    logId: 'log-1',
    timestamp: '2026-06-04T23:00:00.000Z',
    level: 'error',
    message: 'token=abc failed',
    body: 'token=abc failed',
    service: 'api',
    environment: 'prod',
    host: 'web-1',
    source: 'otlp',
    containerName: '',
    containerId: '',
    containerImage: '',
    traceId: '',
    spanId: '',
    tags: {team: 'payments'},
    resourceAttributes: {region: 'us-east'},
  },
]

function renderSheet(onApplySavedView = vi.fn()) {
  return renderWithQueryClient(
    <LogManagementSheet
      trigger={<button type="button">Manage</button>}
      currentQuery="service:api"
      currentLevels={['error']}
      currentViewState={currentViewState}
      currentLogs={currentLogs}
      onApplySavedView={onApplySavedView}
    />
  )
}

function openSheet() {
  fireEvent.click(screen.getByRole('button', {name: 'Manage'}))
  return screen.findByText('Log management')
}

function selectTab(name: RegExp) {
  const tab = screen.getByRole('tab', {name})
  fireEvent.pointerDown(tab, {button: 0, ctrlKey: false})
  fireEvent.mouseDown(tab, {button: 0, ctrlKey: false})
  fireEvent.click(tab)
  fireEvent.keyDown(tab, {key: 'Enter'})
}

function baseHandlers() {
  server.use(
    http.get(`${API_BASE}/v1/logs/indexes`, () => HttpResponse.json({
      indexes: [
        {
          id: 1,
          name: 'main',
          filter_query: 'service:api',
          retention_days: 30,
          sampling_rate: 1,
          priority: 0,
          is_active: true,
          daily_quota_gb: 5,
        },
        {
          id: 2,
          name: 'catch-all',
          filter_query: '',
          retention_days: 7,
          sampling_rate: 0.5,
          priority: 1,
          is_active: false,
          daily_quota_gb: null,
        },
        {
          id: 3,
          name: 'kb-index',
          filter_query: 'level:info',
          retention_days: 14,
          sampling_rate: 1,
          priority: 2,
          is_active: true,
          daily_quota_gb: null,
        },
      ],
    })),
    http.get(`${API_BASE}/v1/logs/indexes/usage`, () => HttpResponse.json({
      usage: [
        {index_name: 'main', bytes_today: 2 * 1024 * 1024 * 1024, count_today: 12, quota_gb: 5},
        {index_name: 'catch-all', bytes_today: 512, count_today: 2, quota_gb: null},
        {index_name: 'kb-index', bytes_today: 2048, count_today: 3, quota_gb: null},
      ],
    })),
    http.get(`${API_BASE}/v1/logs/pipelines`, () => HttpResponse.json({
      pipelines: [
        {
          id: 10,
          name: 'Redact tokens',
          description: '',
          steps: [{type: 'redact', enabled: true}],
          priority: 4,
          is_active: true,
          created_at: '2026-06-04T00:00:00Z',
          updated_at: '2026-06-04T00:00:00Z',
        },
      ],
    })),
    http.get(`${API_BASE}/v1/logs/saved-views`, () => HttpResponse.json({
      views: [
        {
          id: 20,
          name: 'Production errors',
          state: savedViewState,
          is_shared: true,
          created_at: '2026-06-04T00:00:00Z',
          updated_at: '2026-06-04T00:00:00Z',
        },
      ],
    })),
    http.get(`${API_BASE}/v1/logs/metrics/rules`, () => HttpResponse.json({
      rules: [
        {
          id: 30,
          name: 'error_logs',
          query: 'service:api',
          levels: ['error'],
          group_by: 'service',
          interval: '5m',
          is_active: true,
          created_at: '2026-06-04T00:00:00Z',
          updated_at: '2026-06-04T00:00:00Z',
        },
      ],
    })),
    http.get(`${API_BASE}/v1/logs/monitors`, () => HttpResponse.json({
      monitors: [
        {
          id: 40,
          name: 'error_volume',
          query: 'service:api',
          levels: ['error'],
          group_by: null,
          condition: '>',
          threshold: 20,
          warning_threshold: null,
          window_minutes: 5,
          is_active: true,
          created_at: '2026-06-04T00:00:00Z',
          updated_at: '2026-06-04T00:00:00Z',
        },
      ],
    }))
  )
}

describe('LogManagementSheet', () => {
  beforeEach(() => {
    clearAuthStorage()
    baseHandlers()
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
  })

  it('renders index usage, creates an index, toggles status, and runs retention', async () => {
    let createdIndex: Record<string, unknown> | null = null
    let updatedIndex: Record<string, unknown> | null = null
    let retentionRan = false

    server.use(
      http.post(`${API_BASE}/v1/logs/indexes`, async ({request}) => {
        createdIndex = await request.json() as Record<string, unknown>
        return HttpResponse.json({id: 4, ...createdIndex})
      }),
      http.put(`${API_BASE}/v1/logs/indexes/1`, async ({request}) => {
        updatedIndex = await request.json() as Record<string, unknown>
        return HttpResponse.json({id: 1, name: 'main', is_active: false})
      }),
      http.post(`${API_BASE}/v1/logs/indexes/retention/run`, () => {
        retentionRan = true
        return HttpResponse.json({indexes_processed: 3})
      })
    )

    renderSheet()
    await openSheet()

    expect(await screen.findByText('main')).toBeInTheDocument()
    expect(screen.getByText(/2.00 GB \/ 5 GB today/)).toBeInTheDocument()
    expect(screen.getByText(/512 B today/)).toBeInTheDocument()
    expect(screen.getByText(/2.0 KB today/)).toBeInTheDocument()
    expect(screen.getAllByText('catch-all').length).toBeGreaterThan(0)
    expect(screen.getByText('Paused')).toBeInTheDocument()

    fireEvent.change(screen.getByPlaceholderText('production-errors'), {target: {value: 'api-errors'}})
    fireEvent.change(screen.getByPlaceholderText('optional'), {target: {value: '2'}})
    const filterTextarea = document.querySelector('textarea')
    expect(filterTextarea).not.toBeNull()
    fireEvent.change(filterTextarea as HTMLTextAreaElement, {target: {value: 'level:error'}})
    fireEvent.click(screen.getByRole('button', {name: 'Use current search'}))
    fireEvent.click(screen.getByRole('button', {name: 'Create index'}))

    await waitFor(() => {
      expect(createdIndex).toEqual({
        name: 'api-errors',
        filter_query: 'service:api',
        retention_days: 30,
        sampling_rate: 1,
        daily_quota_gb: 2,
      })
    })

    fireEvent.click(screen.getAllByRole('switch')[0])
    await waitFor(() => {
      expect(updatedIndex).toEqual({is_active: false})
    })

    fireEvent.click(screen.getByRole('button', {name: 'Enforce retention'}))
    await waitFor(() => {
      expect(retentionRan).toBe(true)
    })
  })

  it('blocks invalid index quota values before creating an index', async () => {
    let createRequests = 0

    server.use(
      http.post(`${API_BASE}/v1/logs/indexes`, () => {
        createRequests += 1
        return HttpResponse.json({id: 4, name: 'api-errors'})
      })
    )

    renderSheet()
    await openSheet()

    fireEvent.change(screen.getByPlaceholderText('production-errors'), {target: {value: 'api-errors'}})
    fireEvent.change(screen.getByPlaceholderText('optional'), {target: {value: '-1'}})

    const createButton = screen.getByRole('button', {name: 'Create index'})
    expect(createButton).toBeDisabled()
    fireEvent.click(createButton)
    expect(createRequests).toBe(0)

    fireEvent.change(screen.getByPlaceholderText('optional'), {target: {value: '2'}})
    expect(createButton).not.toBeDisabled()
    fireEvent.click(createButton)

    await waitFor(() => {
      expect(createRequests).toBe(1)
    })
  })

  it('runs pipeline, saved-view, metric, and monitor actions from the current explorer state', async () => {
    const appliedView = vi.fn()
    const capturedBodies: Record<string, unknown>[] = []

    server.use(
      http.post(`${API_BASE}/v1/logs/pipelines`, async ({request}) => {
        capturedBodies.push(await request.json() as Record<string, unknown>)
        return HttpResponse.json({id: 11, name: 'Mask tokens', description: '', steps: [], priority: 0})
      }),
      http.post(`${API_BASE}/v1/logs/pipelines/preview`, async ({request}) => {
        capturedBodies.push(await request.json() as Record<string, unknown>)
        return HttpResponse.json({
          results: [
            {before: {message: 'token=abc failed'}, after: {message: 'token=[redacted] failed'}, dropped: false},
          ],
        })
      }),
      http.post(`${API_BASE}/v1/logs/saved-views`, async ({request}) => {
        capturedBodies.push(await request.json() as Record<string, unknown>)
        return HttpResponse.json({
          id: 21,
          name: 'Current errors',
          state: currentViewState,
          is_shared: true,
          created_at: '2026-06-04T00:00:00Z',
          updated_at: '2026-06-04T00:00:00Z',
        })
      }),
      http.post(`${API_BASE}/v1/logs/metrics/rules`, async ({request}) => {
        capturedBodies.push(await request.json() as Record<string, unknown>)
        return HttpResponse.json({
          id: 31,
          name: 'checkout_errors',
          query: 'service:api',
          levels: ['error'],
          group_by: 'service',
          interval: '5m',
          is_active: true,
        })
      }),
      http.post(`${API_BASE}/v1/logs/metrics/preview`, async ({request}) => {
        capturedBodies.push(await request.json() as Record<string, unknown>)
        return HttpResponse.json({
          buckets: [{timestamp: '2026-06-04T23:00:00Z', count: 4, groups: {api: 4}}],
          totalCount: 4,
          interval: '5m',
        })
      }),
      http.post(`${API_BASE}/v1/logs/metrics/rules/30/rollup`, () => {
        return HttpResponse.json({points_inserted: 5})
      }),
      http.post(`${API_BASE}/v1/logs/monitors`, async ({request}) => {
        capturedBodies.push(await request.json() as Record<string, unknown>)
        return HttpResponse.json({
          id: 41,
          name: 'High errors',
          query: 'service:api',
          levels: ['error'],
          group_by: null,
          condition: '>',
          threshold: 15,
          warning_threshold: null,
          window_minutes: 5,
          is_active: true,
          created_at: '2026-06-04T00:00:00Z',
          updated_at: '2026-06-04T00:00:00Z',
        })
      })
    )

    renderSheet(appliedView)
    await openSheet()

    selectTab(/Pipelines/)
    expect(await screen.findByText('Redact tokens')).toBeInTheDocument()
    fireEvent.change(screen.getByPlaceholderText('Redact sensitive keys'), {target: {value: 'Mask tokens'}})
    fireEvent.click(screen.getByRole('button', {name: 'Test on samples'}))
    expect(await screen.findByText('Test results')).toBeInTheDocument()
    expect(await screen.findByText(/token=\[redacted\]/)).toBeInTheDocument()
    expect(screen.getByText('Redacted')).toBeInTheDocument()
    fireEvent.click(screen.getByRole('button', {name: 'Create pipeline'}))

    selectTab(/Views/)
    expect(await screen.findByText('Production errors')).toBeInTheDocument()
    fireEvent.click(screen.getByRole('button', {name: 'Apply'}))
    expect(appliedView).toHaveBeenCalledWith(savedViewState)
    fireEvent.change(screen.getByPlaceholderText('Name this view'), {target: {value: 'Current errors'}})
    fireEvent.click(screen.getByRole('button', {name: 'Save'}))

    selectTab(/Metrics/)
    expect(await screen.findByText('error_logs')).toBeInTheDocument()
    fireEvent.change(screen.getByPlaceholderText('checkout_error_logs'), {target: {value: 'checkout_errors'}})
    fireEvent.click(screen.getByRole('button', {name: 'Estimate volume'}))
    expect(await screen.findByText(/2026-06-04T23:00:00Z/)).toBeInTheDocument()
    fireEvent.click(screen.getByRole('button', {name: 'Create metric'}))
    fireEvent.click(screen.getByRole('button', {name: 'Roll up'}))

    selectTab(/Monitors/)
    expect(await screen.findByText('error_volume')).toBeInTheDocument()
    fireEvent.change(screen.getByPlaceholderText('High error log volume'), {target: {value: 'High errors'}})
    const thresholdInput = screen.getByDisplayValue('10')
    fireEvent.change(thresholdInput, {target: {value: '15'}})
    fireEvent.click(screen.getByRole('button', {name: 'Create monitor'}))

    await waitFor(() => {
      expect(capturedBodies).toEqual(expect.arrayContaining([
        expect.objectContaining({name: 'Mask tokens', is_active: true}),
        expect.objectContaining({sample_logs: [expect.objectContaining({message: 'token=abc failed'})]}),
        {name: 'Current errors', state: currentViewState, is_shared: true},
        expect.objectContaining({
          name: 'checkout_errors',
          query: 'service:api',
          levels: ['error'],
          group_by: 'service',
          interval: '5m',
        }),
        expect.objectContaining({name: 'High errors', threshold: 15}),
      ]))
    })
  })

  it('shows the pass-through copy when no sampled logs match the pipeline pattern', async () => {
    server.use(
      http.post(`${API_BASE}/v1/logs/pipelines/preview`, () => HttpResponse.json({
        results: [
          {before: {message: 'GET /health 200'}, after: {message: 'GET /health 200'}, dropped: false},
        ],
      }))
    )

    renderSheet()
    await openSheet()

    selectTab(/Pipelines/)
    expect(await screen.findByText('Redact tokens')).toBeInTheDocument()
    fireEvent.click(screen.getByRole('button', {name: 'Test on samples'}))

    expect(
      await screen.findByText('None of the sampled logs matched this pattern. They would pass through unchanged.')
    ).toBeInTheDocument()
    expect(screen.queryByText('Redacted')).toBeNull()
  })

  it('tests a manual sample line and renders the redacted result', async () => {
    let previewBody: Record<string, unknown> | null = null

    server.use(
      http.post(`${API_BASE}/v1/logs/pipelines/preview`, async ({request}) => {
        previewBody = await request.json() as Record<string, unknown>
        return HttpResponse.json({
          results: [
            {before: {message: 'token=hunter2 failed'}, after: {message: 'token=[redacted] failed'}, dropped: false},
          ],
        })
      })
    )

    renderSheet()
    await openSheet()

    selectTab(/Pipelines/)
    await screen.findByText('Redact tokens')
    fireEvent.change(
      screen.getByPlaceholderText('request failed credential=[sample] value=[sample]'),
      {target: {value: 'token=hunter2 failed'}}
    )
    fireEvent.click(screen.getByRole('button', {name: 'Run sample'}))

    expect(await screen.findByText('Test results')).toBeInTheDocument()
    expect(await screen.findByText(/token=\[redacted\]/)).toBeInTheDocument()
    await waitFor(() => {
      expect(previewBody).toEqual(expect.objectContaining({
        sample_logs: [{message: 'token=hunter2 failed'}],
      }))
    })
  })

  it('constrains the metric group-by to preset fields', async () => {
    renderSheet()
    await openSheet()

    selectTab(/Metrics/)
    expect(await screen.findByText('error_logs')).toBeInTheDocument()

    // The free-text group-by input is replaced by a constrained select.
    expect(screen.queryByPlaceholderText('service')).toBeNull()
    expect(screen.getByText('Creates one metric series per selected value.')).toBeInTheDocument()
    expect(screen.getAllByRole('combobox').length).toBeGreaterThan(0)
  })
})
