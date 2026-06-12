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
import {api} from '../api'

const API_BASE = 'http://localhost:8080'

describe('security api module', () => {
  beforeEach(() => {
    localStorage.clear()
    sessionStorage.clear()
    sessionStorage.setItem('authenticated', 'true')
  })

  it('serialises signal list filters into the query string', async () => {
    let captured = ''
    server.use(
      http.get(`${API_BASE}/v1/security/signals`, ({request}) => {
        captured = new URL(request.url).search
        return HttpResponse.json({signals: [], total_count: 0})
      })
    )
    await api.listSignals({status: 'open', severity: 'high', source: 'detection', limit: 100, offset: 20})
    const params = new URLSearchParams(captured)
    expect(params.get('status')).toBe('open')
    expect(params.get('severity')).toBe('high')
    expect(params.get('source')).toBe('detection')
    expect(params.get('limit')).toBe('100')
    expect(params.get('offset')).toBe('20')
  })

  it('omits unset signal filters', async () => {
    let captured = '?x=1'
    server.use(
      http.get(`${API_BASE}/v1/security/signals`, ({request}) => {
        captured = new URL(request.url).search
        return HttpResponse.json({signals: [], total_count: 0})
      })
    )
    await api.listSignals()
    expect(captured).toBe('')
  })

  it('PATCHes a triage request', async () => {
    let body: unknown
    server.use(
      http.patch(`${API_BASE}/v1/security/signals/5`, async ({request}) => {
        body = await request.json()
        return HttpResponse.json({id: 5, status: 'archived'})
      })
    )
    await api.triageSignal('5', {status: 'archived', reason: 'benign'})
    expect(body).toEqual({status: 'archived', reason: 'benign'})
  })

  it('POSTs a detection rule and previews a saved rule', async () => {
    let createBody: unknown
    server.use(
      http.post(`${API_BASE}/v1/security/detection/rules`, async ({request}) => {
        createBody = await request.json()
        return HttpResponse.json({id: 7}, {status: 201})
      }),
      http.post(`${API_BASE}/v1/security/detection/rules/7/preview`, () =>
        HttpResponse.json({match_count: 2, samples: [], window_seconds: 300})
      )
    )
    const created = await api.createDetectionRule({name: 'r', filter: 'status:failed'})
    expect((createBody as {name: string}).name).toBe('r')
    const preview = await api.previewDetectionRule(created.id)
    expect(preview.match_count).toBe(2)
  })

  it('DELETEs a detection rule', async () => {
    let called = false
    server.use(
      http.delete(`${API_BASE}/v1/security/detection/rules/9`, () => {
        called = true
        return new HttpResponse(null, {status: 204})
      })
    )
    await api.deleteDetectionRule('9')
    expect(called).toBe(true)
  })

  it('GETs detection coverage and compliance trends', async () => {
    let coverageCalled = false
    let trendsCalled = false
    server.use(
      http.get(`${API_BASE}/v1/security/detection/coverage`, () => {
        coverageCalled = true
        return HttpResponse.json({enabled_rule_count: 1, tactics: [], techniques: []})
      }),
      http.get(`${API_BASE}/v1/security/compliance/trends`, () => {
        trendsCalled = true
        return HttpResponse.json({frameworks: []})
      })
    )

    await api.getDetectionCoverage()
    await api.getComplianceTrends()

    expect(coverageCalled).toBe(true)
    expect(trendsCalled).toBe(true)
  })
})
