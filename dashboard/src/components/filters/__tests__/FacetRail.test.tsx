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

import {render, screen, fireEvent, waitFor} from '@testing-library/react'
import {describe, it, expect, vi} from 'vitest'

import {FacetRail} from '@/components/filters/FacetRail'
import type {FacetRailSection} from '@/lib/filters/types'

const sections: FacetRailSection[] = [
  {key: 'service', label: 'Service', options: [{value: 'api', count: 42}, {value: 'worker', count: 5}]},
  {key: 'status', label: 'Status', singleSelect: true, allowExclude: false, options: [{value: 'unresolved'}, {value: 'resolved'}]},
]

function setup(props: Partial<React.ComponentProps<typeof FacetRail>> = {}) {
  const onFacetFiltersChange = vi.fn()
  render(
    <FacetRail
      sections={sections}
      facetFilters={[]}
      onFacetFiltersChange={onFacetFiltersChange}
      {...props}
    />
  )
  return {onFacetFiltersChange}
}

describe('FacetRail', () => {
  it('renders sections, values and counts', () => {
    setup()
    expect(screen.getByRole('button', {name: 'api'})).toBeTruthy()
    expect(screen.getByText('42')).toBeTruthy()
    expect(screen.getByRole('button', {name: 'resolved'})).toBeTruthy()
  })

  it('adds an include filter when a value is checked', () => {
    const {onFacetFiltersChange} = setup()
    fireEvent.click(screen.getByRole('button', {name: 'api'}))
    expect(onFacetFiltersChange).toHaveBeenCalledWith([{key: 'service', value: 'api', exclude: false}])
  })

  it('removes the filter when an included value is clicked again', () => {
    const {onFacetFiltersChange} = setup({facetFilters: [{key: 'service', value: 'api', exclude: false}]})
    fireEvent.click(screen.getByRole('button', {name: 'api'}))
    expect(onFacetFiltersChange).toHaveBeenCalledWith([])
  })

  it('adds an exclude filter via the exclude affordance', () => {
    const {onFacetFiltersChange} = setup()
    fireEvent.click(screen.getAllByTitle('Exclude this value')[0])
    expect(onFacetFiltersChange).toHaveBeenCalledWith([{key: 'service', value: 'api', exclude: true}])
  })

  it('removes an excluded value via the exclude affordance', () => {
    const {onFacetFiltersChange} = setup({facetFilters: [{key: 'service', value: 'api', exclude: true}]})
    fireEvent.click(screen.getByTitle('Remove exclusion'))
    expect(onFacetFiltersChange).toHaveBeenCalledWith([])
  })

  it('replaces the value for a single-select section', () => {
    const {onFacetFiltersChange} = setup({facetFilters: [{key: 'status', value: 'unresolved', exclude: false}]})
    fireEvent.click(screen.getByRole('button', {name: 'resolved'}))
    expect(onFacetFiltersChange).toHaveBeenCalledWith([{key: 'status', value: 'resolved', exclude: false}])
  })

  it('collapses a section to hide its values', () => {
    setup()
    expect(screen.getByRole('button', {name: 'api'})).toBeTruthy()
    fireEvent.click(screen.getByRole('button', {name: /Service/}))
    expect(screen.queryByRole('button', {name: 'api'})).toBeNull()
  })

  it('shows "Clear all" with a selection and clears it', () => {
    const {onFacetFiltersChange} = setup({facetFilters: [{key: 'service', value: 'api'}]})
    fireEvent.click(screen.getByText('Clear all'))
    expect(onFacetFiltersChange).toHaveBeenCalledWith([])
  })

  it('filters values via the per-section search box', () => {
    const many: FacetRailSection[] = [
      {
        key: 'tag',
        label: 'Tags',
        options: Array.from({length: 12}, (_, i) => ({value: `svc-${i}`})),
      },
    ]
    render(<FacetRail sections={many} facetFilters={[]} onFacetFiltersChange={() => {}} />)
    fireEvent.change(screen.getByLabelText('Filter Tags'), {target: {value: 'svc-1'}})
    expect(screen.getByRole('button', {name: 'svc-1'})).toBeTruthy()
    expect(screen.getByRole('button', {name: 'svc-10'})).toBeTruthy()
    expect(screen.queryByRole('button', {name: 'svc-2'})).toBeNull()
  })

  it('expands and collapses long value lists', () => {
    const many: FacetRailSection[] = [
      {
        key: 'tag',
        label: 'Tags',
        options: Array.from({length: 12}, (_, i) => ({value: `svc-${i}`})),
      },
    ]
    render(<FacetRail sections={many} facetFilters={[]} onFacetFiltersChange={() => {}} />)

    expect(screen.queryByRole('button', {name: 'svc-11'})).toBeNull()

    fireEvent.click(screen.getByRole('button', {name: 'Show 4 more'}))
    expect(screen.getByRole('button', {name: 'svc-11'})).toBeTruthy()

    fireEvent.click(screen.getByRole('button', {name: 'Show less'}))
    expect(screen.queryByRole('button', {name: 'svc-11'})).toBeNull()
  })

  it('shows an empty state after a lazy section loads zero values', async () => {
    const lazySections: FacetRailSection[] = [
      {
        key: 'service',
        label: 'Service',
        loadOptions: () => Promise.resolve([]),
      },
    ]
    render(<FacetRail sections={lazySections} facetFilters={[]} onFacetFiltersChange={() => {}} />)

    fireEvent.click(screen.getByRole('button', {name: /Service/}))

    expect(await screen.findByText('No matches')).toBeTruthy()
    expect(screen.queryByText('Loading values...')).toBeNull()
  })

  it('reloads lazy values when the loader time range changes', async () => {
    const loadOptions = vi.fn((range: string) => Promise.resolve([{value: `${range}-api`}]))
    const buildSections = (range: string): FacetRailSection[] => [
      {
        key: 'service',
        label: 'Service',
        loadOptions: () => loadOptions(range),
      },
    ]
    const {rerender} = render(
      <FacetRail
        sections={buildSections('1h')}
        facetFilters={[]}
        onFacetFiltersChange={() => {}}
      />
    )

    fireEvent.click(screen.getByRole('button', {name: /Service/}))

    expect(await screen.findByRole('button', {name: '1h-api'})).toBeTruthy()
    expect(loadOptions).toHaveBeenCalledWith('1h')

    rerender(
      <FacetRail
        sections={buildSections('24h')}
        facetFilters={[]}
        onFacetFiltersChange={() => {}}
      />
    )

    await waitFor(() => expect(loadOptions).toHaveBeenCalledWith('24h'))
    expect(await screen.findByRole('button', {name: '24h-api'})).toBeTruthy()
    expect(screen.queryByRole('button', {name: '1h-api'})).toBeNull()
  })

  it('shows a retry state when lazy loading fails', async () => {
    const loadOptions = vi.fn()
      .mockRejectedValueOnce(new Error('network failed'))
      .mockResolvedValueOnce([{value: 'api'}])
    const lazySections: FacetRailSection[] = [
      {
        key: 'service',
        label: 'Service',
        loadOptions,
      },
    ]
    render(<FacetRail sections={lazySections} facetFilters={[]} onFacetFiltersChange={() => {}} />)

    fireEvent.click(screen.getByRole('button', {name: /Service/}))

    expect(await screen.findByText('Could not load values.')).toBeTruthy()
    expect(screen.queryByText('Loading values...')).toBeNull()

    fireEvent.click(screen.getByRole('button', {name: 'Retry'}))

    expect(await screen.findByRole('button', {name: 'api'})).toBeTruthy()
    expect(loadOptions).toHaveBeenCalledTimes(2)
  })
})
