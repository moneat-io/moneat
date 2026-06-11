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

import { describe, it, expect, beforeEach } from 'vitest'
import { http, HttpResponse } from 'msw'
import { server } from '@/test/mocks/server'
import { api } from '@/lib/api'

const API_BASE = 'http://localhost:8080'

const WORKFLOW_ID = '11111111-1111-4111-8111-111111111111'
const WORKFLOW_COPY_ID = '22222222-2222-4222-8222-222222222222'
const WORKFLOW_IMPORTED_ID = '33333333-3333-4333-8333-333333333333'
const emptyGraph = { nodes: [], edges: [] }

describe('workflows phase 4 API', () => {
  beforeEach(() => {
    globalThis.localStorage.clear()
    globalThis.sessionStorage.clear()
    globalThis.sessionStorage.setItem('authenticated', 'true')
  })

  // ──── Blueprints ────

  describe('getWorkflowBlueprints', () => {
    it('fetches the blueprint catalog', async () => {
      const blueprints = [
        {
          key: 'error-spike',
          name: 'Error spike alert',
          description: 'Notify on error spikes',
          category: 'alerting',
          trigger_name: 'issue.created',
          tags: ['errors', 'alerting'],
        },
      ]
      server.use(
        http.get(`${API_BASE}/v1/workflows/blueprints`, () =>
          HttpResponse.json(blueprints)
        )
      )

      const result = await api.getWorkflowBlueprints()
      expect(result).toEqual(blueprints)
    })
  })

  describe('getWorkflowBlueprint', () => {
    it('fetches a single blueprint by key and encodes it', async () => {
      const detail = {
        key: 'error spike',
        name: 'Error spike alert',
        description: 'Notify on error spikes',
        category: 'alerting',
        trigger_name: 'issue.created',
        tags: ['errors'],
        conditions: [],
        steps: [],
        graph: emptyGraph,
        once_for_template: ['{{issue.id}}'],
      }
      server.use(
        http.get(`${API_BASE}/v1/workflows/blueprints/error%20spike`, () =>
          HttpResponse.json(detail)
        )
      )

      const result = await api.getWorkflowBlueprint('error spike')
      expect(result).toEqual(detail)
    })
  })

  describe('instantiateBlueprint', () => {
    it('posts an empty body by default', async () => {
      const workflowResponse = { id: WORKFLOW_ID, name: 'Error spike alert' }
      server.use(
        http.post(
          `${API_BASE}/v1/workflows/blueprints/error-spike/instantiate`,
          async ({ request }) => {
            await expect(request.json()).resolves.toEqual({})
            return HttpResponse.json(workflowResponse, { status: 201 })
          }
        )
      )

      const result = await api.instantiateBlueprint('error-spike')
      expect(result).toEqual(workflowResponse)
    })

    it('passes a custom name', async () => {
      server.use(
        http.post(
          `${API_BASE}/v1/workflows/blueprints/error-spike/instantiate`,
          async ({ request }) => {
            await expect(request.json()).resolves.toEqual({ name: 'My copy' })
            return HttpResponse.json({ id: WORKFLOW_COPY_ID, name: 'My copy' }, { status: 201 })
          }
        )
      )

      const result = await api.instantiateBlueprint('error-spike', { name: 'My copy' })
      expect(result).toEqual({ id: WORKFLOW_COPY_ID, name: 'My copy' })
    })
  })

  // ──── Overview & usage ────

  describe('getWorkflowOverview', () => {
    it('fetches overview metrics', async () => {
      const overview = {
        total_workflows: 5,
        enabled_workflows: 3,
        published_workflows: 2,
        runs_last_30d: 120,
        success_rate: 0.95,
        failed_last_30d: 6,
        top_workflows: [{ workflow_id: '11111111-1111-4111-8111-111111111111', name: 'Pager', run_count: 40 }],
      }
      server.use(
        http.get(`${API_BASE}/v1/workflows/overview`, () =>
          HttpResponse.json(overview)
        )
      )

      const result = await api.getWorkflowOverview()
      expect(result).toEqual(overview)
    })
  })

  describe('getWorkflowUsage', () => {
    it('fetches usage with a limit', async () => {
      const usage = {
        period: '2026-05',
        used: 120,
        limit: 1000,
        remaining: 880,
        unlimited: false,
      }
      server.use(
        http.get(`${API_BASE}/v1/workflows/usage`, () => HttpResponse.json(usage))
      )

      const result = await api.getWorkflowUsage()
      expect(result).toEqual(usage)
    })
  })

  // ──── Audit ────

  describe('getWorkflowAudit', () => {
    it('fetches audit with no limit query by default', async () => {
      const entries = [
        {
          id: 'a1',
          action: 'workflow.created',
          detail: {},
          created_at: '2026-05-01T00:00:00Z',
        },
      ]
      server.use(
        http.get(`${API_BASE}/v1/workflows/audit`, ({ request }) => {
          const url = new URL(request.url)
          expect(url.searchParams.has('limit')).toBe(false)
          return HttpResponse.json(entries)
        })
      )

      const result = await api.getWorkflowAudit()
      expect(result).toEqual(entries)
    })

    it('passes a custom limit', async () => {
      server.use(
        http.get(`${API_BASE}/v1/workflows/audit`, ({ request }) => {
          const url = new URL(request.url)
          expect(url.searchParams.get('limit')).toBe('25')
          return HttpResponse.json([])
        })
      )

      await api.getWorkflowAudit(25)
    })
  })

  describe('getWorkflowAuditForWorkflow', () => {
    it('fetches per-workflow audit with no limit by default', async () => {
      server.use(
        http.get(`${API_BASE}/v1/workflows/${WORKFLOW_ID}/audit`, ({ request }) => {
          const url = new URL(request.url)
          expect(url.searchParams.has('limit')).toBe(false)
          return HttpResponse.json([])
        })
      )

      const result = await api.getWorkflowAuditForWorkflow(WORKFLOW_ID)
      expect(result).toEqual([])
    })

    it('passes a custom limit for a workflow', async () => {
      server.use(
        http.get(`${API_BASE}/v1/workflows/${WORKFLOW_ID}/audit`, ({ request }) => {
          const url = new URL(request.url)
          expect(url.searchParams.get('limit')).toBe('5')
          return HttpResponse.json([])
        })
      )

      await api.getWorkflowAuditForWorkflow(WORKFLOW_ID, 5)
    })
  })

  // ──── Export / import ────

  describe('exportWorkflow', () => {
    it('fetches the export payload', async () => {
      const exportResponse = {
        schema_version: 1,
        resource: {
          name: 'Pager',
          trigger_name: 'issue.created',
          enabled: true,
          graph: emptyGraph,
          once_for_template: ['{{issue.id}}'],
        },
        terraform: 'resource "moneat_workflow" "pager" {}',
      }
      server.use(
        http.get(`${API_BASE}/v1/workflows/${WORKFLOW_ID}/export`, () =>
          HttpResponse.json(exportResponse)
        )
      )

      const result = await api.exportWorkflow(WORKFLOW_ID)
      expect(result).toEqual(exportResponse)
    })
  })

  describe('importWorkflow', () => {
    it('posts the import body', async () => {
      const body = {
        name: 'Imported',
        trigger_name: 'issue.created',
        graph: emptyGraph,
        enabled: false,
        once_for_template: ['{{issue.id}}'],
      }
      server.use(
        http.post(`${API_BASE}/v1/workflows/import`, async ({ request }) => {
          await expect(request.json()).resolves.toEqual(body)
          return HttpResponse.json({ id: WORKFLOW_IMPORTED_ID, name: 'Imported' })
        })
      )

      const result = await api.importWorkflow(body)
      expect(result).toEqual({ id: WORKFLOW_IMPORTED_ID, name: 'Imported' })
    })
  })
})
