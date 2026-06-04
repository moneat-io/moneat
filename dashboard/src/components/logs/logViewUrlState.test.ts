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

import {describe, it, expect} from 'vitest'
import {
  NO_LOG_GROUP_BY_URL_VALUE,
  parseLogViewSearch,
  parseFacetFiltersFromUrl,
  parseLevelsFromUrl,
  resolveLogGroupBy,
  serializeLogViewState,
} from './logViewUrlState'
import type {FacetFilter} from '@/lib/filters/types'

describe('logViewUrlState', () => {
  describe('parseLogViewSearch', () => {
    it('should parse valid query string', () => {
      const result = parseLogViewSearch({q: 'error timeout'})
      expect(result.q).toBe('error timeout')
    })

    it('should trim whitespace from query', () => {
      const result = parseLogViewSearch({q: '  error  '})
      expect(result.q).toBe('error')
    })

    it('should omit empty query', () => {
      const result = parseLogViewSearch({q: '   '})
      expect(result.q).toBeUndefined()
    })

    it('should parse comma-separated levels', () => {
      const result = parseLogViewSearch({levels: 'error,warn,fatal'})
      expect(result.levels).toBe('error,warn,fatal')
    })

    it('should parse valid facet filters JSON', () => {
      const filters = [{key: 'service', value: 'api'}, {key: 'env', value: 'prod', exclude: true}]
      const result = parseLogViewSearch({facets: JSON.stringify(filters)})
      expect(result.facets).toBe(JSON.stringify(filters))
    })

    it('should ignore malformed facet JSON', () => {
      const result = parseLogViewSearch({facets: 'not-json'})
      expect(result.facets).toBeUndefined()
    })

    it('should ignore facet arrays with invalid objects', () => {
      const result = parseLogViewSearch({facets: '[{"invalid": true}]'})
      expect(result.facets).toBeUndefined()
    })

    it('should parse valid time preset', () => {
      const result = parseLogViewSearch({timePreset: '1h'})
      expect(result.timePreset).toBe('1h')
    })

    it('should ignore invalid time preset', () => {
      const result = parseLogViewSearch({timePreset: 'invalid'})
      expect(result.timePreset).toBeUndefined()
    })

    it('should parse valid ISO timestamps', () => {
      const from = '2024-01-15T10:00:00Z'
      const to = '2024-01-15T11:00:00Z'
      const result = parseLogViewSearch({from, to})
      expect(result.from).toBe(from)
      expect(result.to).toBe(to)
    })

    it('should ignore invalid timestamps', () => {
      const result = parseLogViewSearch({from: 'not-a-date', to: 'also-invalid'})
      expect(result.from).toBeUndefined()
      expect(result.to).toBeUndefined()
    })

    it('should parse valid viz mode', () => {
      const result = parseLogViewSearch({viz: 'table'})
      expect(result.viz).toBe('table')
    })

    it('should migrate list viz mode to timeseries', () => {
      const result = parseLogViewSearch({viz: 'list'})
      expect(result.viz).toBe('timeseries')
    })

    it('should ignore invalid viz mode', () => {
      const result = parseLogViewSearch({viz: 'invalid-mode'})
      expect(result.viz).toBeUndefined()
    })

    it('should parse groupBy and topField', () => {
      const result = parseLogViewSearch({groupBy: 'service', topField: 'level'})
      expect(result.groupBy).toBe('service')
      expect(result.topField).toBe('level')
    })

    it('should parse explicit no-grouping state', () => {
      const result = parseLogViewSearch({groupBy: NO_LOG_GROUP_BY_URL_VALUE})
      expect(result.groupBy).toBe(NO_LOG_GROUP_BY_URL_VALUE)
    })

    it('should ignore invalid groupBy values', () => {
      const result = parseLogViewSearch({groupBy: 'message'})
      expect(result.groupBy).toBeUndefined()
    })

    it('should parse cursor and logId', () => {
      const result = parseLogViewSearch({cursor: 'abc123', logId: 'log-xyz'})
      expect(result.cursor).toBe('abc123')
      expect(result.logId).toBe('log-xyz')
    })

    it('should handle complete valid search object', () => {
      const facets = [{key: 'service', value: 'api'}]
      const search = {
        q: 'error',
        levels: 'error,warn',
        facets: JSON.stringify(facets),
        timePreset: '1h',
        from: '2024-01-15T10:00:00Z',
        to: '2024-01-15T11:00:00Z',
        viz: 'table',
        groupBy: 'service',
        topField: 'level',
        cursor: 'abc',
        logId: 'xyz',
      }
      const result = parseLogViewSearch(search)
      expect(result.q).toBe('error')
      expect(result.levels).toBe('error,warn')
      expect(result.facets).toBe(JSON.stringify(facets))
      expect(result.timePreset).toBe('1h')
      expect(result.viz).toBe('table')
      expect(result.cursor).toBe('abc')
    })
  })

  describe('serializeLogViewState', () => {
    it('should omit empty query', () => {
      const result = serializeLogViewState({query: ''})
      expect(result.q).toBeUndefined()
    })

    it('should include non-empty query', () => {
      const result = serializeLogViewState({query: 'error timeout'})
      expect(result.q).toBe('error timeout')
    })

    it('should omit default level selection (all 6 levels)', () => {
      const result = serializeLogViewState({levels: ['trace', 'debug', 'info', 'warn', 'error', 'fatal']})
      expect(result.levels).toBeUndefined()
    })

    it('should include custom level selection', () => {
      const result = serializeLogViewState({levels: ['error', 'warn']})
      expect(result.levels).toBe('error,warn')
    })

    it('should omit empty facet filters', () => {
      const result = serializeLogViewState({facetFilters: []})
      expect(result.facets).toBeUndefined()
    })

    it('should include non-empty facet filters', () => {
      const filters: FacetFilter[] = [{key: 'service', value: 'api'}]
      const result = serializeLogViewState({facetFilters: filters})
      expect(result.facets).toBe(JSON.stringify(filters))
    })

    it('should omit default time preset (15m)', () => {
      const result = serializeLogViewState({timePreset: '15m'})
      expect(result.timePreset).toBeUndefined()
    })

    it('should include non-default time preset', () => {
      const result = serializeLogViewState({timePreset: '1h'})
      expect(result.timePreset).toBe('1h')
    })

    it('should include custom time range when preset is custom', () => {
      const result = serializeLogViewState({
        timePreset: 'custom',
        customFrom: '2024-01-15T10:00:00',
        customTo: '2024-01-15T11:00:00',
      })
      expect(result.timePreset).toBe('custom')
      expect(result.from).toBeDefined()
      expect(result.to).toBeDefined()
    })

    it('should omit custom time range when preset is not custom', () => {
      const result = serializeLogViewState({
        timePreset: '1h',
        customFrom: '2024-01-15T10:00:00',
        customTo: '2024-01-15T11:00:00',
      })
      expect(result.from).toBeUndefined()
      expect(result.to).toBeUndefined()
    })

    it('should omit default viz mode (timeseries)', () => {
      const result = serializeLogViewState({vizMode: 'timeseries'})
      expect(result.viz).toBeUndefined()
    })

    it('should include non-default viz mode', () => {
      const result = serializeLogViewState({vizMode: 'table'})
      expect(result.viz).toBe('table')
    })

    it('should omit default topField (service)', () => {
      const result = serializeLogViewState({topField: 'service'})
      expect(result.topField).toBeUndefined()
    })

    it('should include non-default topField', () => {
      const result = serializeLogViewState({topField: 'level'})
      expect(result.topField).toBe('level')
    })

    it('should omit default level grouping', () => {
      const result = serializeLogViewState({groupBy: 'level'})
      expect(result.groupBy).toBeUndefined()
    })

    it('should serialize explicit no-grouping state', () => {
      const result = serializeLogViewState({groupBy: ''})
      expect(result.groupBy).toBe(NO_LOG_GROUP_BY_URL_VALUE)
    })

    it('should include non-default groupBy', () => {
      const result = serializeLogViewState({groupBy: 'service'})
      expect(result.groupBy).toBe('service')
    })

    it('should include cursor', () => {
      const result = serializeLogViewState({cursor: 'abc123'})
      expect(result.cursor).toBe('abc123')
    })

    it('should omit null cursor', () => {
      const result = serializeLogViewState({cursor: null})
      expect(result.cursor).toBeUndefined()
    })

    it('should include selected log ID', () => {
      const result = serializeLogViewState({selectedLogId: 'log-xyz'})
      expect(result.logId).toBe('log-xyz')
    })

    it('should omit null selected log ID', () => {
      const result = serializeLogViewState({selectedLogId: null})
      expect(result.logId).toBeUndefined()
    })
  })

  describe('parseFacetFiltersFromUrl', () => {
    it('should return empty array for undefined', () => {
      expect(parseFacetFiltersFromUrl(undefined)).toEqual([])
    })

    it('should return empty array for invalid JSON', () => {
      expect(parseFacetFiltersFromUrl('not-json')).toEqual([])
    })

    it('should parse valid facet filter array', () => {
      const filters: FacetFilter[] = [
        {key: 'service', value: 'api'},
        {key: 'env', value: 'prod', exclude: true},
      ]
      const result = parseFacetFiltersFromUrl(JSON.stringify(filters))
      expect(result).toEqual(filters)
    })

    it('should return empty array for non-array JSON', () => {
      expect(parseFacetFiltersFromUrl('{"key": "value"}')).toEqual([])
    })

    it('should return empty array for array with invalid items', () => {
      expect(parseFacetFiltersFromUrl('[{"invalid": true}]')).toEqual([])
    })
  })

  describe('parseLevelsFromUrl', () => {
    it('should return empty array for undefined', () => {
      expect(parseLevelsFromUrl(undefined)).toEqual([])
    })

    it('should parse comma-separated levels', () => {
      expect(parseLevelsFromUrl('error,warn,fatal')).toEqual(['error', 'warn', 'fatal'])
    })

    it('should filter empty strings', () => {
      expect(parseLevelsFromUrl('error,,warn')).toEqual(['error', 'warn'])
    })

    it('should handle single level', () => {
      expect(parseLevelsFromUrl('error')).toEqual(['error'])
    })
  })

  describe('resolveLogGroupBy', () => {
    it('should default missing groupBy to level', () => {
      expect(resolveLogGroupBy(undefined)).toBe('level')
    })

    it('should preserve explicit no-grouping state', () => {
      expect(resolveLogGroupBy(NO_LOG_GROUP_BY_URL_VALUE)).toBe('')
    })

    it('should preserve valid grouping fields', () => {
      expect(resolveLogGroupBy('environment')).toBe('environment')
    })
  })

  describe('round-trip serialization', () => {
    it('should round-trip complete state', () => {
      const state = {
        query: 'error timeout',
        levels: ['error', 'warn'],
        facetFilters: [{key: 'service', value: 'api'}] as FacetFilter[],
        timePreset: '1h',
        customFrom: '',
        customTo: '',
        vizMode: 'table' as const,
        groupBy: 'service',
        topField: 'level',
        cursor: 'abc123',
        selectedLogId: 'log-xyz',
      }

      const serialized = serializeLogViewState(state)
      const parsed = parseLogViewSearch(serialized)

      expect(parsed.q).toBe(state.query)
      expect(parsed.levels).toBe(state.levels.join(','))
      expect(parsed.facets).toBe(JSON.stringify(state.facetFilters))
      expect(parsed.timePreset).toBe(state.timePreset)
      expect(parsed.viz).toBe(state.vizMode)
      expect(parsed.groupBy).toBe(state.groupBy)
      expect(parsed.topField).toBe(state.topField)
      expect(parsed.cursor).toBe(state.cursor)
      expect(parsed.logId).toBe(state.selectedLogId)
    })
  })
})
