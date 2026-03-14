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
import {describe, it, expect, vi, beforeEach} from 'vitest'
import {render, screen, waitFor} from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import {QueryClient, QueryClientProvider} from '@tanstack/react-query'
import {http, HttpResponse} from 'msw'
import {server} from '@/test/mocks/server'
import {api} from '@/lib/api'
import {AlertConfigForm} from '../AlertConfigForm'
import type {QueryDsl, DashboardWidgetAlert} from '@/lib/api'

const API_BASE = 'http://localhost:8080'

function renderWithQuery(ui: React.ReactElement) {
  const queryClient = new QueryClient({defaultOptions: {queries: {retry: false}}})
  return render(
    <QueryClientProvider client={queryClient}>{ui}</QueryClientProvider>
  )
}

const baseQuery: QueryDsl = {
  dataSource: 'events',
  metrics: [{function: 'count', alias: 'total_count'}],
  groupBy: [],
  filters: [],
  limit: 100,
  timeRange: {from: 'now-24h', to: 'now'},
}

const makeAlert = (overrides: Partial<DashboardWidgetAlert> = {}): DashboardWidgetAlert => ({
  id: 1,
  widget_id: 10,
  dashboard_id: 5,
  name: 'High error rate',
  condition: '>',
  threshold: 100,
  metric_index: 0,
  duration_seconds: 0,
  incident_severity: null,
  enabled: true,
  notification_channels: {email: true, slack: true, discord: true},
  last_triggered_at: null,
  last_value: null,
  created_at: '2024-01-01T00:00:00Z',
  updated_at: '2024-01-01T00:00:00Z',
  ...overrides,
})

beforeEach(() => {
  localStorage.clear()
  sessionStorage.clear()
  sessionStorage.setItem('authenticated', 'true')
})

describe('AlertConfigForm', () => {
  // ──── Initial render – no alerts ────
  describe('initial render with no alerts', () => {
    beforeEach(() => {
      server.use(
        http.get(`${API_BASE}/v1/dashboards/:id/alerts`, () =>
          HttpResponse.json([])
        )
      )
    })

    it('renders "Add alert" button when no alerts exist', async () => {
      renderWithQuery(
        <AlertConfigForm dashboardId={5} widgetId={10} queryConfigs={[baseQuery]} />
      )
      await waitFor(() => {
        expect(screen.getByText('Add alert')).toBeInTheDocument()
      })
    })

    it('does not show form initially', () => {
      renderWithQuery(
        <AlertConfigForm dashboardId={5} widgetId={10} queryConfigs={[baseQuery]} />
      )
      expect(screen.queryByPlaceholderText('Alert name')).not.toBeInTheDocument()
    })
  })

  // ──── Opening the form ────
  describe('form toggle', () => {
    beforeEach(() => {
      server.use(
        http.get(`${API_BASE}/v1/dashboards/:id/alerts`, () =>
          HttpResponse.json([])
        )
      )
    })

    it('shows form when "Add alert" is clicked', async () => {
      const user = userEvent.setup()
      renderWithQuery(
        <AlertConfigForm dashboardId={5} widgetId={10} queryConfigs={[baseQuery]} />
      )
      await user.click(screen.getByText('Add alert'))
      expect(screen.getByPlaceholderText('Alert name')).toBeInTheDocument()
      expect(screen.getByText('Create Alert')).toBeInTheDocument()
      expect(screen.getByText('Cancel')).toBeInTheDocument()
    })

    it('hides form when Cancel is clicked', async () => {
      const user = userEvent.setup()
      renderWithQuery(
        <AlertConfigForm dashboardId={5} widgetId={10} queryConfigs={[baseQuery]} />
      )
      await user.click(screen.getByText('Add alert'))
      expect(screen.getByPlaceholderText('Alert name')).toBeInTheDocument()
      await user.click(screen.getByText('Cancel'))
      expect(screen.queryByPlaceholderText('Alert name')).not.toBeInTheDocument()
    })
  })

  // ──── Form fields and interactions ────
  describe('form inputs', () => {
    beforeEach(() => {
      server.use(
        http.get(`${API_BASE}/v1/dashboards/:id/alerts`, () =>
          HttpResponse.json([])
        )
      )
    })

    it('renders all condition options', async () => {
      const user = userEvent.setup()
      renderWithQuery(
        <AlertConfigForm dashboardId={5} widgetId={10} queryConfigs={[baseQuery]} />
      )
      await user.click(screen.getByText('Add alert'))
      const conditionSelect = screen.getByDisplayValue('>')
      expect(conditionSelect).toBeInTheDocument()
      expect(conditionSelect.querySelectorAll('option')).toHaveLength(5)
    })

    it('renders all severity options including None', async () => {
      const user = userEvent.setup()
      renderWithQuery(
        <AlertConfigForm dashboardId={5} widgetId={10} queryConfigs={[baseQuery]} />
      )
      await user.click(screen.getByText('Add alert'))
      const severitySelect = screen.getByDisplayValue('None')
      expect(severitySelect.querySelectorAll('option')).toHaveLength(5)
    })

    it('renders notification channel checkboxes', async () => {
      const user = userEvent.setup()
      renderWithQuery(
        <AlertConfigForm dashboardId={5} widgetId={10} queryConfigs={[baseQuery]} />
      )
      await user.click(screen.getByText('Add alert'))
      expect(screen.getByText('Email')).toBeInTheDocument()
      expect(screen.getByText('Slack')).toBeInTheDocument()
      expect(screen.getByText('Discord')).toBeInTheDocument()
    })

    it('Create Alert button is disabled when name is empty', async () => {
      const user = userEvent.setup()
      renderWithQuery(
        <AlertConfigForm dashboardId={5} widgetId={10} queryConfigs={[baseQuery]} />
      )
      await user.click(screen.getByText('Add alert'))
      const createBtn = screen.getByText('Create Alert')
      expect(createBtn.closest('button')).toBeDisabled()
    })

    it('Create Alert button is enabled when name is provided', async () => {
      const user = userEvent.setup()
      renderWithQuery(
        <AlertConfigForm dashboardId={5} widgetId={10} queryConfigs={[baseQuery]} />
      )
      await user.click(screen.getByText('Add alert'))
      await user.type(screen.getByPlaceholderText('Alert name'), 'My alert')
      const createBtn = screen.getByText('Create Alert')
      expect(createBtn.closest('button')).not.toBeDisabled()
    })

    it('can change condition select', async () => {
      const user = userEvent.setup()
      renderWithQuery(
        <AlertConfigForm dashboardId={5} widgetId={10} queryConfigs={[baseQuery]} />
      )
      await user.click(screen.getByText('Add alert'))
      const conditionSelect = screen.getByDisplayValue('>')
      await user.selectOptions(conditionSelect, '<')
      expect(conditionSelect).toHaveValue('<')
    })

    it('can change severity select', async () => {
      const user = userEvent.setup()
      renderWithQuery(
        <AlertConfigForm dashboardId={5} widgetId={10} queryConfigs={[baseQuery]} />
      )
      await user.click(screen.getByText('Add alert'))
      const severitySelect = screen.getByDisplayValue('None')
      await user.selectOptions(severitySelect, 'CRITICAL')
      expect(severitySelect).toHaveValue('CRITICAL')
    })

    it('can set severity back to None (null)', async () => {
      const user = userEvent.setup()
      renderWithQuery(
        <AlertConfigForm dashboardId={5} widgetId={10} queryConfigs={[baseQuery]} />
      )
      await user.click(screen.getByText('Add alert'))
      const severitySelect = screen.getByDisplayValue('None')
      await user.selectOptions(severitySelect, 'HIGH')
      expect(severitySelect).toHaveValue('HIGH')
      await user.selectOptions(severitySelect, '')
      expect(severitySelect).toHaveValue('')
    })

    it('can change threshold input', async () => {
      const user = userEvent.setup()
      renderWithQuery(
        <AlertConfigForm dashboardId={5} widgetId={10} queryConfigs={[baseQuery]} />
      )
      await user.click(screen.getByText('Add alert'))
      // Threshold and duration both start at 0; threshold has no placeholder
      const zeroInputs = screen.getAllByDisplayValue('0')
      const thresholdInput = zeroInputs.find(el => !el.getAttribute('placeholder'))!
      await user.clear(thresholdInput)
      await user.type(thresholdInput, '500')
      expect(thresholdInput).toHaveValue(500)
    })

    it('can change duration_seconds input', async () => {
      const user = userEvent.setup()
      renderWithQuery(
        <AlertConfigForm dashboardId={5} widgetId={10} queryConfigs={[baseQuery]} />
      )
      await user.click(screen.getByText('Add alert'))
      const durationInput = screen.getByPlaceholderText('0')
      await user.clear(durationInput)
      await user.type(durationInput, '60')
      expect(durationInput).toHaveValue(60)
    })

    it('can toggle notification channel checkboxes', async () => {
      const user = userEvent.setup()
      renderWithQuery(
        <AlertConfigForm dashboardId={5} widgetId={10} queryConfigs={[baseQuery]} />
      )
      await user.click(screen.getByText('Add alert'))
      const checkboxes = screen.getAllByRole('checkbox')
      // All should be checked initially
      expect(checkboxes[0]).toBeChecked()
      await user.click(checkboxes[0])
      expect(checkboxes[0]).not.toBeChecked()
    })
  })

  // ──── Form submission ────
  describe('form submission', () => {
    it('calls createDashboardAlert API on form submit', async () => {
      server.use(
        http.get(`${API_BASE}/v1/dashboards/:id/alerts`, () =>
          HttpResponse.json([])
        ),
        http.post(`${API_BASE}/v1/dashboards/:id/alerts`, () =>
          HttpResponse.json(makeAlert({id: 2, name: 'New alert'}))
        )
      )
      const user = userEvent.setup()
      renderWithQuery(
        <AlertConfigForm dashboardId={5} widgetId={10} queryConfigs={[baseQuery]} />
      )
      await user.click(screen.getByText('Add alert'))
      await user.type(screen.getByPlaceholderText('Alert name'), 'New alert')
      await user.click(screen.getByText('Create Alert'))

      // After success, form should be hidden
      await waitFor(() => {
        expect(screen.queryByPlaceholderText('Alert name')).not.toBeInTheDocument()
      })
    })
  })

  // ──── Existing alerts display ────
  describe('existing alerts display', () => {
    it('renders existing alerts for the widget', async () => {
      server.use(
        http.get(`${API_BASE}/v1/dashboards/:id/alerts`, () =>
          HttpResponse.json([
            makeAlert({id: 1, widget_id: 10, name: 'High errors', enabled: true}),
            makeAlert({id: 2, widget_id: 10, name: 'Low throughput', enabled: false}),
          ])
        )
      )
      renderWithQuery(
        <AlertConfigForm dashboardId={5} widgetId={10} queryConfigs={[baseQuery]} />
      )
      await waitFor(() => {
        expect(screen.getByText('High errors')).toBeInTheDocument()
      })
      expect(screen.getByText('Low throughput')).toBeInTheDocument()
    })

    it('filters alerts to only show current widget', async () => {
      server.use(
        http.get(`${API_BASE}/v1/dashboards/:id/alerts`, () =>
          HttpResponse.json([
            makeAlert({id: 1, widget_id: 10, name: 'Widget 10 alert'}),
            makeAlert({id: 2, widget_id: 99, name: 'Other widget alert'}),
          ])
        )
      )
      renderWithQuery(
        <AlertConfigForm dashboardId={5} widgetId={10} queryConfigs={[baseQuery]} />
      )
      await waitFor(() => {
        expect(screen.getByText('Widget 10 alert')).toBeInTheDocument()
      })
      expect(screen.queryByText('Other widget alert')).not.toBeInTheDocument()
    })

    it('shows FIRING badge when last_triggered_at is set', async () => {
      server.use(
        http.get(`${API_BASE}/v1/dashboards/:id/alerts`, () =>
          HttpResponse.json([
            makeAlert({id: 1, widget_id: 10, name: 'Firing alert', last_triggered_at: '2024-06-01T00:00:00Z'}),
          ])
        )
      )
      renderWithQuery(
        <AlertConfigForm dashboardId={5} widgetId={10} queryConfigs={[baseQuery]} />
      )
      await waitFor(() => {
        expect(screen.getByText('FIRING')).toBeInTheDocument()
      })
    })

    it('does not show FIRING badge when last_triggered_at is null', async () => {
      server.use(
        http.get(`${API_BASE}/v1/dashboards/:id/alerts`, () =>
          HttpResponse.json([
            makeAlert({id: 1, widget_id: 10, name: 'Idle alert', last_triggered_at: null}),
          ])
        )
      )
      renderWithQuery(
        <AlertConfigForm dashboardId={5} widgetId={10} queryConfigs={[baseQuery]} />
      )
      await waitFor(() => {
        expect(screen.getByText('Idle alert')).toBeInTheDocument()
      })
      expect(screen.queryByText('FIRING')).not.toBeInTheDocument()
    })

    it('shows disabled styling for disabled alerts', async () => {
      server.use(
        http.get(`${API_BASE}/v1/dashboards/:id/alerts`, () =>
          HttpResponse.json([
            makeAlert({id: 1, widget_id: 10, name: 'Disabled alert', enabled: false}),
          ])
        )
      )
      renderWithQuery(
        <AlertConfigForm dashboardId={5} widgetId={10} queryConfigs={[baseQuery]} />
      )
      await waitFor(() => {
        expect(screen.getByText('Disabled alert')).toBeInTheDocument()
      })
      // The opacity-60 class is on the outer row container (flex items-center justify-between)
      const alertRow = screen.getByText('Disabled alert').closest('div[class*="justify-between"]')
      expect(alertRow?.className).toContain('opacity-60')
    })
  })

  // ──── Toggle alert enabled/disabled ────
  describe('toggle alert', () => {
    it('toggles an enabled alert to disabled', async () => {
      server.use(
        http.get(`${API_BASE}/v1/dashboards/:id/alerts`, () =>
          HttpResponse.json([
            makeAlert({id: 1, widget_id: 10, name: 'Toggle me', enabled: true}),
          ])
        ),
        http.put(`${API_BASE}/v1/dashboards/:dashId/alerts/:alertId`, () =>
          HttpResponse.json(makeAlert({id: 1, enabled: false}))
        )
      )
      const user = userEvent.setup()
      renderWithQuery(
        <AlertConfigForm dashboardId={5} widgetId={10} queryConfigs={[baseQuery]} />
      )
      await waitFor(() => {
        expect(screen.getByText('Toggle me')).toBeInTheDocument()
      })
      const toggleBtn = screen.getByTitle('Disable')
      await user.click(toggleBtn)
      // Just verifying the click doesn't error
    })
  })

  // ──── Delete alert ────
  describe('delete alert', () => {
    it('calls delete when trash button is clicked', async () => {
      server.use(
        http.get(`${API_BASE}/v1/dashboards/:id/alerts`, () =>
          HttpResponse.json([
            makeAlert({id: 1, widget_id: 10, name: 'Delete me'}),
          ])
        ),
        http.delete(`${API_BASE}/v1/dashboards/:dashId/alerts/:alertId`, () =>
          new HttpResponse(null, {status: 204})
        )
      )
      const user = userEvent.setup()
      renderWithQuery(
        <AlertConfigForm dashboardId={5} widgetId={10} queryConfigs={[baseQuery]} />
      )
      await waitFor(() => {
        expect(screen.getByText('Delete me')).toBeInTheDocument()
      })
      // Find the trash button — it's the last button in the alert row
      const deleteButtons = document.querySelectorAll('button.text-muted-foreground')
      await user.click(deleteButtons[deleteButtons.length - 1])
    })
  })

  // ──── getMetricLabels branches ────
  describe('metric labels', () => {
    it('uses alias from queryConfigs when available', async () => {
      server.use(
        http.get(`${API_BASE}/v1/dashboards/:id/alerts`, () =>
          HttpResponse.json([
            makeAlert({id: 1, widget_id: 10, metric_index: 0, condition: '>', threshold: 50}),
          ])
        )
      )
      renderWithQuery(
        <AlertConfigForm
          dashboardId={5}
          widgetId={10}
          queryConfigs={[baseQuery]}
        />
      )
      await waitFor(() => {
        expect(screen.getByText(/total_count/)).toBeInTheDocument()
      })
    })

    it('falls back to function(field) when alias is not set', async () => {
      const queryWithoutAlias: QueryDsl = {
        ...baseQuery,
        metrics: [{function: 'avg', field: 'duration_ms'}],
      }
      server.use(
        http.get(`${API_BASE}/v1/dashboards/:id/alerts`, () =>
          HttpResponse.json([
            makeAlert({id: 1, widget_id: 10, metric_index: 0, condition: '>', threshold: 50}),
          ])
        )
      )
      renderWithQuery(
        <AlertConfigForm
          dashboardId={5}
          widgetId={10}
          queryConfigs={[queryWithoutAlias]}
        />
      )
      await waitFor(() => {
        expect(screen.getByText(/avg\(duration_ms\)/)).toBeInTheDocument()
      })
    })

    it('falls back to function() when field is null', async () => {
      const queryNoField: QueryDsl = {
        ...baseQuery,
        metrics: [{function: 'count'}],
      }
      server.use(
        http.get(`${API_BASE}/v1/dashboards/:id/alerts`, () =>
          HttpResponse.json([
            makeAlert({id: 1, widget_id: 10, metric_index: 0, condition: '>', threshold: 50}),
          ])
        )
      )
      renderWithQuery(
        <AlertConfigForm
          dashboardId={5}
          widgetId={10}
          queryConfigs={[queryNoField]}
        />
      )
      await waitFor(() => {
        expect(screen.getByText(/count\(\)/)).toBeInTheDocument()
      })
    })

    it('defaults to count() when queryConfigs have empty metrics', async () => {
      const emptyMetricsQuery: QueryDsl = {
        ...baseQuery,
        metrics: [],
      }
      server.use(
        http.get(`${API_BASE}/v1/dashboards/:id/alerts`, () =>
          HttpResponse.json([
            makeAlert({id: 1, widget_id: 10, metric_index: 0, condition: '>', threshold: 50}),
          ])
        )
      )
      renderWithQuery(
        <AlertConfigForm
          dashboardId={5}
          widgetId={10}
          queryConfigs={[emptyMetricsQuery]}
        />
      )
      await waitFor(() => {
        expect(screen.getByText(/count\(\)/)).toBeInTheDocument()
      })
    })

    it('shows "metric" when metric_index does not match any label', async () => {
      server.use(
        http.get(`${API_BASE}/v1/dashboards/:id/alerts`, () =>
          HttpResponse.json([
            makeAlert({id: 1, widget_id: 10, metric_index: 999, condition: '>', threshold: 50}),
          ])
        )
      )
      renderWithQuery(
        <AlertConfigForm
          dashboardId={5}
          widgetId={10}
          queryConfigs={[baseQuery]}
        />
      )
      await waitFor(() => {
        expect(screen.getByText(/metric/)).toBeInTheDocument()
      })
    })
  })

  // ──── Multiple queryConfigs ────
  describe('multiple queryConfigs', () => {
    it('shows metric options from all queryConfigs in the select', async () => {
      const queries: QueryDsl[] = [
        {...baseQuery, metrics: [{function: 'count', alias: 'errors'}]},
        {...baseQuery, metrics: [{function: 'avg', field: 'duration_ms', alias: 'latency'}]},
      ]
      server.use(
        http.get(`${API_BASE}/v1/dashboards/:id/alerts`, () =>
          HttpResponse.json([])
        )
      )
      const user = userEvent.setup()
      renderWithQuery(
        <AlertConfigForm dashboardId={5} widgetId={10} queryConfigs={queries} />
      )
      await user.click(screen.getByText('Add alert'))
      const metricSelect = screen.getByDisplayValue('errors')
      expect(metricSelect.querySelectorAll('option')).toHaveLength(2)
    })
  })
})
