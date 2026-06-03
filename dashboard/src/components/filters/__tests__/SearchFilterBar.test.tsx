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

import {render, screen, fireEvent} from '@testing-library/react'
import {describe, it, expect, vi} from 'vitest'

import {SearchFilterBar} from '@/components/filters/SearchFilterBar'
import type {FacetSchema} from '@/lib/filters/types'

const schema: FacetSchema = [
  {key: 'service', suggestions: ['api', 'worker']},
  {key: 'environment', aliases: ['env'], suggestions: ['prod', 'staging'], allowExclude: true},
  {key: 'status', singleSelect: true, suggestions: ['unresolved', 'resolved']},
]

function setup(props: Partial<React.ComponentProps<typeof SearchFilterBar>> = {}) {
  const onQueryChange = vi.fn()
  const onFacetFiltersChange = vi.fn()
  render(
    <SearchFilterBar
      query=""
      onQueryChange={onQueryChange}
      facetFilters={[]}
      onFacetFiltersChange={onFacetFiltersChange}
      schema={schema}
      {...props}
    />
  )
  return {onQueryChange, onFacetFiltersChange, input: screen.getByRole('textbox')}
}

describe('SearchFilterBar', () => {
  it('turns a key:value token into a facet filter on Enter', () => {
    const {onFacetFiltersChange, input} = setup()
    fireEvent.change(input, {target: {value: 'service:api'}})
    fireEvent.keyDown(input, {key: 'Enter'})
    expect(onFacetFiltersChange).toHaveBeenCalledWith([{key: 'service', value: 'api', exclude: false}])
  })

  it('routes free text to the query', () => {
    const {onQueryChange, input} = setup()
    fireEvent.change(input, {target: {value: 'timeout'}})
    fireEvent.keyDown(input, {key: 'Enter'})
    expect(onQueryChange).toHaveBeenCalledWith('timeout')
  })

  it('resolves an alias to the canonical key and supports exclude', () => {
    const {onFacetFiltersChange, input} = setup()
    fireEvent.change(input, {target: {value: '-env:prod'}})
    fireEvent.keyDown(input, {key: 'Enter'})
    expect(onFacetFiltersChange).toHaveBeenCalledWith([{key: 'environment', value: 'prod', exclude: true}])
  })

  it('routes an exclude on a non-excludable facet to the query', () => {
    const {onQueryChange, onFacetFiltersChange, input} = setup()
    fireEvent.change(input, {target: {value: '-service:api'}})
    fireEvent.keyDown(input, {key: 'Enter'})
    expect(onQueryChange).toHaveBeenCalledWith('-service:api')
    expect(onFacetFiltersChange).not.toHaveBeenCalled()
  })

  it('replaces the value for a single-select facet', () => {
    const {onFacetFiltersChange, input} = setup({facetFilters: [{key: 'status', value: 'unresolved'}]})
    fireEvent.change(input, {target: {value: 'status:resolved'}})
    fireEvent.keyDown(input, {key: 'Enter'})
    expect(onFacetFiltersChange).toHaveBeenCalledWith([{key: 'status', value: 'resolved', exclude: false}])
  })

  it('lets an interceptor claim a token (e.g. level)', () => {
    const onInterceptToken = vi.fn().mockReturnValue(true)
    const {onQueryChange, onFacetFiltersChange, input} = setup({onInterceptToken})
    fireEvent.change(input, {target: {value: 'level:error'}})
    fireEvent.keyDown(input, {key: 'Enter'})
    expect(onInterceptToken).toHaveBeenCalledWith('level', 'error', false)
    expect(onQueryChange).not.toHaveBeenCalled()
    expect(onFacetFiltersChange).not.toHaveBeenCalled()
  })

  it('removes a facet chip via its dismiss button', () => {
    const {onFacetFiltersChange} = setup({facetFilters: [{key: 'service', value: 'api'}]})
    fireEvent.click(screen.getByRole('button', {name: 'Remove service:api'}))
    expect(onFacetFiltersChange).toHaveBeenCalledWith([])
  })

  it('peels off the last facet on Backspace when the input is empty', () => {
    const {onFacetFiltersChange, input} = setup({
      facetFilters: [{key: 'service', value: 'api'}, {key: 'environment', value: 'prod'}],
    })
    fireEvent.keyDown(input, {key: 'Backspace'})
    expect(onFacetFiltersChange).toHaveBeenCalledWith([{key: 'service', value: 'api'}])
  })

  it('applies a value suggestion on click', () => {
    const {onFacetFiltersChange, input} = setup()
    fireEvent.change(input, {target: {value: 'service:ap'}})
    const suggestion = screen.getByText('service:api')
    fireEvent.mouseDown(suggestion)
    expect(onFacetFiltersChange).toHaveBeenCalledWith([{key: 'service', value: 'api', exclude: false}])
  })
})
