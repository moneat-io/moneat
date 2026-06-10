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

import {fireEvent, render, screen, within} from '@testing-library/react'
import {describe, expect, it, vi} from 'vitest'
import type {DashboardTemplateSummary} from '@/lib/api'
import {DashboardsGetStarted} from '../DashboardsGetStarted'

const SAMPLE_TEMPLATES: readonly DashboardTemplateSummary[] = [
  {
    id: 'node-exporter-full',
    title: 'Node Exporter Full',
    description: 'Prebuilt Moneat dashboard for host telemetry.',
    category: 'infrastructure',
    tags: ['Infrastructure', 'Prometheus'],
    required_sources: ['Prometheus'],
    widget_count: 140,
    variable_count: 4,
    resource_path: 'dashboard-templates/community/node-exporter-full.json',
  },
  {
    id: 'postgresql-overview',
    title: 'PostgreSQL Overview',
    description: 'Prebuilt Moneat dashboard for database telemetry.',
    category: 'databases',
    tags: ['Databases', 'PostgreSQL'],
    required_sources: ['PostgreSQL'],
    widget_count: 16,
    variable_count: 2,
    resource_path: 'dashboard-templates/community/postgresql-overview.json',
  },
  {
    id: 'log-analytics',
    title: 'Log Analytics',
    description: 'Prebuilt Moneat dashboard for log telemetry.',
    category: 'logs',
    tags: ['Logs', 'Loki'],
    required_sources: ['Loki'],
    widget_count: 8,
    variable_count: 1,
    resource_path: 'dashboard-templates/community/log-analytics.json',
  },
]

function setup(
  templates: readonly DashboardTemplateSummary[] = SAMPLE_TEMPLATES,
  isLoadingTemplates = false,
) {
  const onCreateBlank = vi.fn()
  const onUseTemplate = vi.fn()
  const onImport = vi.fn()
  const result = render(
    <DashboardsGetStarted
      templates={templates}
      isLoadingTemplates={isLoadingTemplates}
      onCreateBlank={onCreateBlank}
      onUseTemplate={onUseTemplate}
      onImport={onImport}
    />,
  )
  return {onCreateBlank, onUseTemplate, onImport, ...result}
}

describe('DashboardsGetStarted', () => {
  it('renders the three start tiles and wires blank + import', () => {
    const {onCreateBlank, onImport} = setup()

    fireEvent.click(screen.getByRole('button', {name: /Blank dashboard/i}))
    expect(onCreateBlank).toHaveBeenCalledTimes(1)

    fireEvent.click(screen.getByRole('button', {name: /Import JSON/i}))
    expect(onImport).toHaveBeenCalledTimes(1)
  })

  it('renders the recommended-template gallery from metadata', () => {
    setup()
    for (const title of [
      'Node Exporter Full',
      'PostgreSQL Overview',
      'Log Analytics',
    ]) {
      expect(
        screen.getByRole('button', {name: new RegExp(`Use the ${title} template`, 'i')}),
      ).toBeInTheDocument()
    }
  })

  it('does not render template quality labels', () => {
    setup()

    expect(screen.queryByText('Ready')).not.toBeInTheDocument()
    expect(screen.queryByText('Partial')).not.toBeInTheDocument()
    expect(screen.queryByText('Review')).not.toBeInTheDocument()
  })

  it('uses a template by id when its card is clicked', () => {
    const {onUseTemplate} = setup()
    fireEvent.click(
      screen.getByRole('button', {name: /Use the Node Exporter Full template/i}),
    )
    expect(onUseTemplate).toHaveBeenCalledWith('node-exporter-full')
  })

  it('filters the gallery by category', () => {
    setup()
    const filters = screen.getByRole('group', {name: /filter templates by category/i})

    fireEvent.click(within(filters).getByRole('button', {name: 'Databases'}))

    expect(
      screen.getByRole('button', {name: /Use the PostgreSQL Overview template/i}),
    ).toBeInTheDocument()
    expect(
      screen.queryByRole('button', {name: /Use the Node Exporter Full template/i}),
    ).not.toBeInTheDocument()
  })

  it('marks the active category filter with aria-pressed', () => {
    setup()
    const filters = screen.getByRole('group', {name: /filter templates by category/i})
    const infrastructure = within(filters).getByRole('button', {name: 'Infrastructure'})

    expect(infrastructure).toHaveAttribute('aria-pressed', 'false')
    fireEvent.click(infrastructure)
    expect(infrastructure).toHaveAttribute('aria-pressed', 'true')
    expect(within(filters).getByRole('button', {name: 'All'})).toHaveAttribute(
      'aria-pressed',
      'false',
    )
  })

  it('uses a native labelled group for category filters', () => {
    setup()

    const filters = screen.getByRole('group', {name: /filter templates by category/i})

    expect(filters.tagName.toLowerCase()).toBe('fieldset')
    expect(within(filters).getByText('Filter templates by category')).toHaveClass('sr-only')
  })

  it('shows a loading gallery while template metadata is pending', () => {
    const {container} = setup([], true)

    expect(container.querySelectorAll('.animate-pulse').length).toBeGreaterThan(0)
    expect(screen.queryByText('No templates match this category.')).not.toBeInTheDocument()
  })

  it('shows an empty filtered state when no templates match the selected category', () => {
    setup([])

    expect(screen.getByText('No templates match this category.')).toBeInTheDocument()
  })

  it('renders every thumbnail family used by template categories and tags', () => {
    const templates: readonly DashboardTemplateSummary[] = [
      ...SAMPLE_TEMPLATES,
      {
        id: 'kubernetes-cluster',
        title: 'Kubernetes Cluster',
        description: 'Cluster telemetry.',
        category: 'kubernetes',
        tags: ['Kubernetes'],
        required_sources: ['Prometheus'],
        widget_count: 24,
        variable_count: 3,
        resource_path: 'dashboard-templates/community/kubernetes-cluster.json',
      },
      {
        id: 'browser-vitals',
        title: 'Browser Vitals',
        description: 'Browser performance telemetry.',
        category: 'frontend',
        tags: ['RUM', 'Browser'],
        required_sources: ['OTLP'],
        widget_count: 7,
        variable_count: 1,
        resource_path: 'dashboard-templates/community/browser-vitals.json',
      },
      {
        id: 'service-dashboard',
        title: 'Service Dashboard',
        description: 'Service-level telemetry.',
        category: 'applications',
        tags: ['Applications'],
        required_sources: ['OTLP'],
        widget_count: 11,
        variable_count: 1,
        resource_path: 'dashboard-templates/community/service-dashboard.json',
      },
    ]

    setup(templates)

    expect(screen.getByText('Kubernetes Cluster')).toBeInTheDocument()
    expect(screen.getByText('Browser Vitals')).toBeInTheDocument()
    expect(screen.getByText('Service Dashboard')).toBeInTheDocument()
  })
})
