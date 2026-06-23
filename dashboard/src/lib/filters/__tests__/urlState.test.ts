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

import {describe, expect, it} from 'vitest'
import type {FacetFilter} from '@/lib/filters/types'
import {
  isFacetFilter,
  parseExplorerSearch,
  parseFacetFiltersParam,
  parseReadableFacetFilters,
  serializeReadableFacetFilters,
  serializeExplorerSearch,
  serializeFacetFiltersParam,
} from '@/lib/filters/urlState'

describe('filters/urlState', () => {
  describe('isFacetFilter', () => {
    it('accepts well-formed filters and rejects the rest', () => {
      expect(isFacetFilter({key: 'status', value: 'unresolved'})).toBe(true)
      expect(isFacetFilter({key: 'service', value: 'api', exclude: true})).toBe(true)
      expect(isFacetFilter({key: 'service', value: 'api', exclude: 'yes'})).toBe(false)
      expect(isFacetFilter({key: 'service'})).toBe(false)
      expect(isFacetFilter(null)).toBe(false)
      expect(isFacetFilter('status:unresolved')).toBe(false)
    })
  })

  describe('parseFacetFiltersParam', () => {
    it('returns an empty array for absent, malformed, or non-conforming values', () => {
      expect(parseFacetFiltersParam(undefined)).toEqual([])
      expect(parseFacetFiltersParam('')).toEqual([])
      expect(parseFacetFiltersParam('not-json')).toEqual([])
      expect(parseFacetFiltersParam('{"key":"status"}')).toEqual([])
      expect(parseFacetFiltersParam('[{"nope":true}]')).toEqual([])
    })

    it('preserves valid structured filters as-authored', () => {
      const filters: FacetFilter[] = [
        {key: 'status', value: 'unresolved'},
        {key: 'service', value: 'api', exclude: true},
      ]
      expect(parseFacetFiltersParam(filters)).toEqual(filters)
    })

    it('accepts legacy JSON-string and double-stringified facet params', () => {
      const filters: FacetFilter[] = [{key: 'status', value: 'resolved'}]
      expect(parseFacetFiltersParam(JSON.stringify(filters))).toEqual(filters)
      expect(parseFacetFiltersParam(JSON.stringify(JSON.stringify(filters)))).toEqual(filters)
    })

    it('round-trips an explicit empty selection', () => {
      expect(parseFacetFiltersParam('[]')).toEqual([])
    })
  })

  describe('serializeFacetFiltersParam', () => {
    it('drops the redundant exclude:false for compact, stable URLs', () => {
      expect(serializeFacetFiltersParam([{key: 'status', value: 'unresolved', exclude: false}])).toEqual([
        {key: 'status', value: 'unresolved'},
      ])
    })

    it('keeps exclude:true', () => {
      expect(serializeFacetFiltersParam([{key: 'service', value: 'legacy', exclude: true}])).toEqual([
        {key: 'service', value: 'legacy', exclude: true},
      ])
    })

    it('emits an empty array literal for no filters', () => {
      expect(serializeFacetFiltersParam([])).toEqual([])
    })
  })

  describe('readable facet params', () => {
    const keys = ['service', 'environment'] as const

    it('parses include and exclude params', () => {
      expect(parseReadableFacetFilters({service: 'api', exclude_environment: 'dev'}, keys)).toEqual([
        {key: 'service', value: 'api'},
        {key: 'environment', value: 'dev', exclude: true},
      ])
    })

    it('serializes filters into first-class params', () => {
      expect(
        serializeReadableFacetFilters(
          [
            {key: 'service', value: 'api'},
            {key: 'service', value: 'worker'},
            {key: 'environment', value: 'dev', exclude: true},
          ],
          keys
        )
      ).toEqual({service: ['api', 'worker'], exclude_environment: 'dev'})
    })

    it('uses legacy facets only when readable params are absent', () => {
      expect(
        parseReadableFacetFilters(
          {
            facets: JSON.stringify([{key: 'service', value: 'legacy'}]),
          },
          keys
        )
      ).toEqual([{key: 'service', value: 'legacy'}])
      expect(
        parseReadableFacetFilters(
          {
            service: 'api',
            facets: JSON.stringify([{key: 'service', value: 'legacy'}]),
          },
          keys
        )
      ).toEqual([{key: 'service', value: 'api'}])
    })
  })

  describe('parseExplorerSearch', () => {
    it('trims the query and drops it when empty', () => {
      expect(parseExplorerSearch({q: '  boom  '})).toEqual({q: 'boom'})
      expect(parseExplorerSearch({q: '   '})).toEqual({})
    })

    it('canonicalises a valid facets param, including an explicit empty array', () => {
      expect(
        parseExplorerSearch({facets: '[{"key":"status","value":"unresolved","exclude":false}]'})
      ).toEqual({facets: [{key: 'status', value: 'unresolved'}]})
      expect(parseExplorerSearch({facets: '[]'})).toEqual({facets: []})
    })

    it('canonicalises structured and double-stringified facets', () => {
      const filters: FacetFilter[] = [{key: 'status', value: 'resolved', exclude: false}]
      expect(parseExplorerSearch({facets: filters})).toEqual({facets: [{key: 'status', value: 'resolved'}]})
      expect(parseExplorerSearch({facets: JSON.stringify(JSON.stringify(filters))})).toEqual({
        facets: [{key: 'status', value: 'resolved'}],
      })
    })

    it('omits an absent or malformed facets param so the surface can apply its default', () => {
      expect(parseExplorerSearch({})).toEqual({})
      expect(parseExplorerSearch({facets: 'not-json'})).toEqual({})
      expect(parseExplorerSearch({facets: '[{"bad":1}]'})).toEqual({})
    })
  })

  describe('serializeExplorerSearch', () => {
    it('omits an empty query but always emits facets', () => {
      expect(serializeExplorerSearch({query: '', facetFilters: []})).toEqual({q: undefined, facets: []})
      expect(
        serializeExplorerSearch({query: 'boom', facetFilters: [{key: 'status', value: 'unresolved'}]})
      ).toEqual({q: 'boom', facets: [{key: 'status', value: 'unresolved'}]})
    })

    it('round-trips through parseExplorerSearch', () => {
      const facetFilters: FacetFilter[] = [
        {key: 'status', value: 'unresolved'},
        {key: 'service', value: 'legacy', exclude: true},
      ]
      const serialized = serializeExplorerSearch({query: 'timeout', facetFilters})
      expect(parseExplorerSearch({q: serialized.q, facets: serialized.facets})).toEqual(serialized)
      expect(parseFacetFiltersParam(serialized.facets)).toEqual(facetFilters)
    })
  })
})
