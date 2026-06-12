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

import {beforeEach, describe, expect, it} from 'vitest'
import {http, HttpResponse} from 'msw'
import {server} from '@/test/mocks/server'
import {api, type LogPipelineStep, type LogSavedViewState} from '@/lib/api'

const API_BASE = 'http://localhost:8080'
const PIPELINE_ID = '11111111-1111-4111-8111-111111111111'
const SAVED_VIEW_ID = '22222222-2222-4222-8222-222222222222'
const METRIC_RULE_ID = '33333333-3333-4333-8333-333333333333'
const DASHBOARD_ALERT_ID = '44444444-4444-4444-8444-444444444444'
const LOG_MONITOR_ID = '55555555-5555-4555-8555-555555555555'

const savedViewState: LogSavedViewState = {
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

const redactStep: LogPipelineStep = {
  type: 'redact',
  enabled: true,
  condition: 'level:error',
  source_field: 'message',
  pattern: 'token=([^\\s]+)',
  replacement: 'token=[redacted]',
}

describe('Log Management API', () => {
  beforeEach(() => {
    localStorage.clear()
    sessionStorage.clear()
    sessionStorage.setItem('authenticated', 'true')
  })

  it('fetches permissions and index usage', async () => {
    const permissions = {
      can_manage: true,
      can_live_tail: true,
      can_create_metrics: true,
      can_create_monitors: false,
    }
    const usage = {
      usage: [
        {index_name: 'main', bytes_today: 2048, count_today: 12, quota_gb: 1},
      ],
    }

    server.use(
      http.get(`${API_BASE}/v1/logs/permissions`, () => HttpResponse.json(permissions)),
      http.get(`${API_BASE}/v1/logs/indexes/usage`, () => HttpResponse.json(usage))
    )

    expect(await api.getLogPermissions()).toEqual(permissions)
    expect(await api.getLogIndexUsage()).toEqual(usage)
  })

  it('runs index retention', async () => {
    server.use(
      http.post(`${API_BASE}/v1/logs/indexes/retention/run`, () => {
        return HttpResponse.json({indexes_processed: 2})
      })
    )

    await expect(api.runLogIndexRetention()).resolves.toEqual({indexes_processed: 2})
  })

  it('creates, updates, previews, and deletes pipelines', async () => {
    const pipeline = {
      id: PIPELINE_ID,
      name: 'Redact tokens',
      description: '',
      steps: [redactStep],
      priority: 0,
      is_active: true,
      created_at: '2026-06-04T00:00:00Z',
      updated_at: '2026-06-04T00:00:00Z',
    }

    server.use(
      http.get(`${API_BASE}/v1/logs/pipelines`, () => HttpResponse.json({pipelines: [pipeline]})),
      http.post(`${API_BASE}/v1/logs/pipelines`, async ({request}) => {
        const body = await request.json()
        expect(body).toEqual({name: 'Redact tokens', steps: [redactStep], is_active: true})
        return HttpResponse.json(pipeline)
      }),
      http.put(`${API_BASE}/v1/logs/pipelines/${PIPELINE_ID}`, async ({request}) => {
        const body = await request.json()
        expect(body).toEqual({is_active: false})
        return HttpResponse.json({...pipeline, is_active: false})
      }),
      http.post(`${API_BASE}/v1/logs/pipelines/preview`, async ({request}) => {
        const body = await request.json()
        expect(body).toEqual({
          steps: [redactStep],
          sample_logs: [{message: 'token=abc', level: 'error'}],
        })
        return HttpResponse.json({
          results: [
            {
              before: {message: 'token=abc', level: 'error'},
              after: {message: 'token=[redacted]', level: 'error'},
              dropped: false,
            },
          ],
        })
      }),
      http.delete(`${API_BASE}/v1/logs/pipelines/${PIPELINE_ID}`, () => new HttpResponse(null, {status: 204}))
    )

    expect(await api.getLogPipelines()).toEqual({pipelines: [pipeline]})
    expect(await api.createLogPipeline({name: 'Redact tokens', steps: [redactStep], is_active: true}))
      .toEqual(pipeline)
    expect(await api.updateLogPipeline(PIPELINE_ID, {is_active: false})).toEqual({...pipeline, is_active: false})
    expect(await api.previewLogPipeline([redactStep], [{message: 'token=abc', level: 'error'}]))
      .toEqual({
        results: [
          {
            before: {message: 'token=abc', level: 'error'},
            after: {message: 'token=[redacted]', level: 'error'},
            dropped: false,
          },
        ],
      })
    await api.deleteLogPipeline(PIPELINE_ID)
  })

  it('creates, updates, and deletes saved views', async () => {
    const view = {
      id: SAVED_VIEW_ID,
      name: 'Errors',
      state: savedViewState,
      is_shared: true,
      created_at: '2026-06-04T00:00:00Z',
      updated_at: '2026-06-04T00:00:00Z',
    }

    server.use(
      http.get(`${API_BASE}/v1/logs/saved-views`, () => HttpResponse.json({views: [view]})),
      http.post(`${API_BASE}/v1/logs/saved-views`, async ({request}) => {
        const body = await request.json()
        expect(body).toEqual({name: 'Errors', state: savedViewState, is_shared: true})
        return HttpResponse.json(view)
      }),
      http.put(`${API_BASE}/v1/logs/saved-views/${SAVED_VIEW_ID}`, async ({request}) => {
        const body = await request.json()
        expect(body).toEqual({name: 'Errors updated'})
        return HttpResponse.json({...view, name: 'Errors updated'})
      }),
      http.delete(`${API_BASE}/v1/logs/saved-views/${SAVED_VIEW_ID}`, () => new HttpResponse(null, {status: 204}))
    )

    expect(await api.getLogSavedViews()).toEqual({views: [view]})
    expect(await api.createLogSavedView({name: 'Errors', state: savedViewState, is_shared: true}))
      .toEqual(view)
    expect(await api.updateLogSavedView(SAVED_VIEW_ID, {name: 'Errors updated'}))
      .toEqual({...view, name: 'Errors updated'})
    await api.deleteLogSavedView(SAVED_VIEW_ID)
  })

  it('creates, updates, previews, rolls up, and deletes metric rules', async () => {
    const rule = {
      id: METRIC_RULE_ID,
      name: 'error_logs',
      query: 'service:api',
      levels: ['error'],
      group_by: 'service',
      interval: '5m',
      is_active: true,
      created_at: '2026-06-04T00:00:00Z',
      updated_at: '2026-06-04T00:00:00Z',
    }
    const request = {
      name: 'error_logs',
      query: 'service:api',
      levels: ['error'],
      group_by: 'service',
      interval: '5m',
    }

    server.use(
      http.get(`${API_BASE}/v1/logs/metrics/rules`, () => HttpResponse.json({rules: [rule]})),
      http.post(`${API_BASE}/v1/logs/metrics/rules`, async ({request: req}) => {
        expect(await req.json()).toEqual(request)
        return HttpResponse.json(rule)
      }),
      http.put(`${API_BASE}/v1/logs/metrics/rules/${METRIC_RULE_ID}`, async ({request: req}) => {
        expect(await req.json()).toEqual({is_active: false})
        return HttpResponse.json({...rule, is_active: false})
      }),
      http.post(`${API_BASE}/v1/logs/metrics/preview`, async ({request: req}) => {
        expect(await req.json()).toEqual(request)
        return HttpResponse.json({buckets: [], totalCount: 0, interval: '5m'})
      }),
      http.post(`${API_BASE}/v1/logs/metrics/rules/${METRIC_RULE_ID}/rollup`, () => {
        return HttpResponse.json({points_inserted: 4})
      }),
      http.delete(`${API_BASE}/v1/logs/metrics/rules/${METRIC_RULE_ID}`, () => new HttpResponse(null, {status: 204}))
    )

    expect(await api.getLogMetricRules()).toEqual({rules: [rule]})
    expect(await api.createLogMetricRule(request)).toEqual(rule)
    expect(await api.updateLogMetricRule(METRIC_RULE_ID, {is_active: false})).toEqual({...rule, is_active: false})
    expect(await api.previewLogMetricRule(request)).toEqual({buckets: [], totalCount: 0, interval: '5m'})
    expect(await api.rollupLogMetricRule(METRIC_RULE_ID)).toEqual({points_inserted: 4})
    await api.deleteLogMetricRule(METRIC_RULE_ID)
  })

  it('creates monitor drafts from a log query', async () => {
    const request = {
      name: 'High error logs',
      query: 'service:api',
      levels: ['error'],
      condition: '>' as const,
      threshold: 10,
      warning_threshold: null,
    }
    const draft = {
      ...request,
      group_by: null,
      dashboard_alert_created: true,
      dashboard_alert_id: DASHBOARD_ALERT_ID,
    }

    server.use(
      http.post(`${API_BASE}/v1/logs/monitors/from-query`, async ({request: req}) => {
        expect(await req.json()).toEqual(request)
        return HttpResponse.json(draft)
      })
    )

    expect(await api.createLogMonitorFromQuery(request)).toEqual(draft)
  })

  it('lists, creates, updates, and deletes standalone log monitors', async () => {
    const monitor = {
      id: LOG_MONITOR_ID,
      name: 'High error logs',
      query: 'service:api',
      levels: ['error'],
      group_by: null,
      condition: '>' as const,
      threshold: 10,
      warning_threshold: null,
      window_minutes: 5,
      is_active: true,
      created_at: '2026-06-04T00:00:00Z',
      updated_at: '2026-06-04T00:00:00Z',
    }
    const request = {
      name: 'High error logs',
      query: 'service:api',
      levels: ['error'],
      group_by: null,
      condition: '>' as const,
      threshold: 10,
      window_minutes: 5,
    }

    server.use(
      http.get(`${API_BASE}/v1/logs/monitors`, () => HttpResponse.json({monitors: [monitor]})),
      http.post(`${API_BASE}/v1/logs/monitors`, async ({request: req}) => {
        expect(await req.json()).toEqual(request)
        return HttpResponse.json(monitor)
      }),
      http.put(`${API_BASE}/v1/logs/monitors/${LOG_MONITOR_ID}`, async ({request: req}) => {
        expect(await req.json()).toEqual({is_active: false})
        return HttpResponse.json({...monitor, is_active: false})
      }),
      http.delete(`${API_BASE}/v1/logs/monitors/${LOG_MONITOR_ID}`, () => new HttpResponse(null, {status: 204}))
    )

    expect(await api.getLogMonitors()).toEqual({monitors: [monitor]})
    expect(await api.createLogMonitor(request)).toEqual(monitor)
    expect(await api.updateLogMonitor(LOG_MONITOR_ID, {is_active: false})).toEqual({...monitor, is_active: false})
    await api.deleteLogMonitor(LOG_MONITOR_ID)
  })
})
