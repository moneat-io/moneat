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

import {render, screen} from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import {describe, expect, it, vi} from 'vitest'
import type {FacetFilter, FacetRailSection} from '@/lib/filters/types'
import {MapFacetRail} from '../MapFacetRail'

const SECTIONS: FacetRailSection[] = [
  {
    key: 'status',
    label: 'Status',
    color: 'bg-success-solid',
    options: [
      {value: 'up', count: 5},
      {value: 'down', count: 1},
    ],
  },
  {
    key: 'env',
    label: 'Environment',
    color: 'bg-chart-1',
    singleSelect: true,
    options: [
      {value: 'prod', count: 3},
      {value: 'staging', count: 1},
    ],
  },
]

const NO_FILTERS: FacetFilter[] = []

describe('MapFacetRail', () => {
  it('renders sections with option counts and per-value counts', () => {
    render(<MapFacetRail sections={SECTIONS} facetFilters={NO_FILTERS} onFacetFiltersChange={vi.fn()} />)

    expect(screen.getByText('Status')).toBeInTheDocument()
    expect(screen.getByText('Environment')).toBeInTheDocument()
    expect(screen.getByRole('checkbox', {name: 'up'})).toBeInTheDocument()
    // Per-value count is shown next to the value.
    expect(screen.getByText('5')).toBeInTheDocument()
  })

  it('adds a value on click (multi-select sections append)', async () => {
    const user = userEvent.setup()
    const onChange = vi.fn()
    render(
      <MapFacetRail
        sections={SECTIONS}
        facetFilters={[{key: 'status', value: 'up'}]}
        onFacetFiltersChange={onChange}
      />,
    )

    await user.click(screen.getByRole('checkbox', {name: 'down'}))

    expect(onChange).toHaveBeenCalledWith([
      {key: 'status', value: 'up'},
      {key: 'status', value: 'down'},
    ])
  })

  it('removes a value when an active one is clicked again', async () => {
    const user = userEvent.setup()
    const onChange = vi.fn()
    render(
      <MapFacetRail
        sections={SECTIONS}
        facetFilters={[{key: 'status', value: 'up'}]}
        onFacetFiltersChange={onChange}
      />,
    )

    expect(screen.getByRole('checkbox', {name: 'up'})).toHaveAttribute('aria-checked', 'true')
    await user.click(screen.getByRole('checkbox', {name: 'up'}))

    expect(onChange).toHaveBeenCalledWith([])
  })

  it('replaces the value for a single-select section', async () => {
    const user = userEvent.setup()
    const onChange = vi.fn()
    render(
      <MapFacetRail
        sections={SECTIONS}
        facetFilters={[{key: 'env', value: 'prod'}]}
        onFacetFiltersChange={onChange}
      />,
    )

    await user.click(screen.getByRole('checkbox', {name: 'staging'}))

    expect(onChange).toHaveBeenCalledWith([{key: 'env', value: 'staging'}])
  })

  it('clears all filters from the header', async () => {
    const user = userEvent.setup()
    const onChange = vi.fn()
    render(
      <MapFacetRail
        sections={SECTIONS}
        facetFilters={[{key: 'status', value: 'up'}]}
        onFacetFiltersChange={onChange}
      />,
    )

    await user.click(screen.getByRole('button', {name: 'Clear'}))

    expect(onChange).toHaveBeenCalledWith([])
  })

  it('collapses a section, hiding its values', async () => {
    const user = userEvent.setup()
    render(<MapFacetRail sections={SECTIONS} facetFilters={NO_FILTERS} onFacetFiltersChange={vi.fn()} />)

    expect(screen.getByRole('checkbox', {name: 'up'})).toBeInTheDocument()
    await user.click(screen.getByRole('button', {name: /Status/}))
    expect(screen.queryByRole('checkbox', {name: 'up'})).not.toBeInTheDocument()
  })

  it('truncates long sections behind a show-more toggle', async () => {
    const user = userEvent.setup()
    const longSection: FacetRailSection = {
      key: 'service',
      label: 'tag: service',
      options: Array.from({length: 12}, (_unused, index) => ({value: `svc-${index}`, count: index})),
    }
    render(<MapFacetRail sections={[longSection]} facetFilters={NO_FILTERS} onFacetFiltersChange={vi.fn()} />)

    expect(screen.queryByRole('checkbox', {name: 'svc-11'})).not.toBeInTheDocument()
    await user.click(screen.getByRole('button', {name: 'Show 2 more'}))
    expect(screen.getByRole('checkbox', {name: 'svc-11'})).toBeInTheDocument()
  })

  it('shows an empty state when no section has values', () => {
    render(
      <MapFacetRail
        sections={[{key: 'status', label: 'Status', options: []}]}
        facetFilters={NO_FILTERS}
        onFacetFiltersChange={vi.fn()}
      />,
    )

    expect(screen.getByText('No facets available yet.')).toBeInTheDocument()
  })
})
