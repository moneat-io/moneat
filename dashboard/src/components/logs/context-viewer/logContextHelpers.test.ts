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
import type {ApmSpanResponse, LogEntry} from '@/lib/api'
import {
  buildContextVolume,
  computeLogPinPct,
  computeTraceLayout,
  countLogAttributes,
  derivePatternString,
  filterAttrGroups,
  formatDeltaMs,
  formatTraceDuration,
  groupLogAttributes,
  guessAttrType,
  splitPatternSegments,
  tokenizeJson,
} from './logContextHelpers'

function makeLog(overrides: Partial<LogEntry> = {}): LogEntry {
  return {
    logId: 'log-1',
    timestamp: '2026-06-08T14:32:08.412Z',
    level: 'error',
    message: 'something happened',
    body: '',
    service: 'payments-api',
    environment: 'production',
    host: 'ip-10-2-43-118',
    source: 'otlp',
    containerName: '',
    containerId: '',
    containerImage: '',
    traceId: '4f9a2c1e7b8d40a5b1c9e3f2a6d70b84',
    spanId: 'a1b2c3d4e5f60718',
    tags: {},
    resourceAttributes: {},
    ...overrides,
  }
}

function makeSpan(overrides: Partial<ApmSpanResponse> = {}): ApmSpanResponse {
  return {
    spanId: 's1',
    traceId: 't1',
    parentId: '',
    name: 'op',
    service: 'svc',
    resource: 'res',
    type: 'server',
    startNs: 0,
    durationNs: 1_000_000,
    error: 0,
    meta: {},
    metrics: {},
    host: '',
    env: '',
    version: '',
    source: 'otlp',
    ...overrides,
  }
}

describe('derivePatternString', () => {
  it('collapses ints and prefixed ids to placeholders', () => {
    const out = derivePatternString(
      'Payment gateway timeout after 3 retries for order ord_8F2K19 (stripe charge ch_3PqfQ2K)'
    )
    expect(out).toBe('Payment gateway timeout after <int> retries for order <id> (stripe charge <id>)')
  })

  it('collapses uuids and quoted strings', () => {
    expect(derivePatternString('trace 4f9a2c1e-7b8d-40a5-b1c9-e3f2a6d70b84 said "boom"')).toBe(
      'trace <uuid> said <str>'
    )
  })

  it('distinguishes floats from ints', () => {
    expect(derivePatternString('latency 12.5 over 3')).toBe('latency <float> over <int>')
  })

  it('returns empty string for empty input', () => {
    expect(derivePatternString('')).toBe('')
  })
})

describe('splitPatternSegments', () => {
  it('separates literals from wildcards in order', () => {
    const segs = splitPatternSegments('order <id> failed <int> times')
    expect(segs).toEqual([
      {text: 'order ', wildcard: false},
      {text: '<id>', wildcard: true},
      {text: ' failed ', wildcard: false},
      {text: '<int>', wildcard: true},
      {text: ' times', wildcard: false},
    ])
  })

  it('handles a leading wildcard', () => {
    expect(splitPatternSegments('<int> retries')).toEqual([
      {text: '<int>', wildcard: true},
      {text: ' retries', wildcard: false},
    ])
  })
})

describe('guessAttrType', () => {
  it('classifies primitives', () => {
    expect(guessAttrType('504')).toBe('int')
    expect(guessAttrType('12.5')).toBe('float')
    expect(guessAttrType('true')).toBe('bool')
    expect(guessAttrType('2026-06-08T14:32:08.412Z')).toBe('time')
    expect(guessAttrType('payments-api')).toBe('str')
  })
})

describe('groupLogAttributes', () => {
  it('builds reserved, trace, prefixed and resource groups', () => {
    const groups = groupLogAttributes(
      makeLog({
        tags: {'http.method': 'POST', 'http.status_code': '504', 'payment.provider': 'stripe'},
        resourceAttributes: {'k8s.namespace': 'prod'},
      })
    )
    const names = groups.map((g) => g.name)
    expect(names).toContain('Reserved')
    expect(names).toContain('Trace')
    expect(names).toContain('HTTP')
    expect(names).toContain('Payment')
    expect(names).toContain('Resource')

    const http = groups.find((g) => g.name === 'HTTP')!
    const statusRow = http.rows.find((r) => r.key === 'http.status_code')!
    expect(statusRow.pill).toBe('err')
    expect(statusRow.type).toBe('int')

    const trace = groups.find((g) => g.name === 'Trace')!
    expect(trace.rows.every((r) => r.linkable)).toBe(true)
  })

  it('drops empty values and counts the rest', () => {
    const groups = groupLogAttributes(
      makeLog({host: '', source: '', traceId: '', spanId: '', tags: {}, resourceAttributes: {}})
    )
    // status, service, env, timestamp remain
    expect(countLogAttributes(groups)).toBe(4)
    expect(groups.find((g) => g.name === 'Trace')).toBeUndefined()
  })

  it('marks error status with an err pill', () => {
    const groups = groupLogAttributes(makeLog({level: 'error'}))
    const status = groups[0].rows.find((r) => r.key === 'status')!
    expect(status.pill).toBe('err')
  })
})

describe('filterAttrGroups', () => {
  const groups = groupLogAttributes(
    makeLog({tags: {'http.method': 'POST', 'payment.provider': 'stripe'}})
  )

  it('returns all groups for an empty query', () => {
    expect(filterAttrGroups(groups, '   ')).toEqual(groups)
  })

  it('matches on key or value and drops empty groups', () => {
    const filtered = filterAttrGroups(groups, 'stripe')
    expect(filtered).toHaveLength(1)
    expect(filtered[0].name).toBe('Payment')
  })
})

describe('buildContextVolume', () => {
  it('marks the bin holding the anchor as hot and locates the marker', () => {
    const anchor = 1000
    const ts = [0, 250, 500, 750, 1000, 2000]
    const {buckets, markerPct} = buildContextVolume(ts, anchor, 11)
    expect(buckets).toHaveLength(11)
    const hot = buckets.filter((b) => b.hot)
    expect(hot).toHaveLength(1)
    // total counted equals number of surrounding timestamps
    expect(buckets.reduce((s, b) => s + b.count, 0)).toBe(ts.length)
    expect(markerPct).toBeGreaterThan(0)
    expect(markerPct).toBeLessThan(100)
  })

  it('is stable when all timestamps coincide', () => {
    const {buckets, markerPct} = buildContextVolume([5, 5], 5, 5)
    expect(buckets).toHaveLength(5)
    expect(markerPct).toBe(50)
  })
})

describe('formatDeltaMs', () => {
  it('formats zero, sub-second, seconds and minutes', () => {
    expect(formatDeltaMs(0)).toBe('0ms')
    expect(formatDeltaMs(6)).toBe('+6ms')
    expect(formatDeltaMs(-712)).toBe('−712ms')
    expect(formatDeltaMs(-2510)).toBe('−2.51s')
    expect(formatDeltaMs(90_000)).toBe('+1.5m')
  })
})

describe('formatTraceDuration', () => {
  it('formats nanoseconds into adaptive units', () => {
    expect(formatTraceDuration(3_140_000_000)).toBe('3.14s')
    expect(formatTraceDuration(14_000_000)).toBe('14ms')
    expect(formatTraceDuration(8_400_000)).toBe('8.4ms')
    expect(formatTraceDuration(1_200_000)).toBe('1.2ms')
    expect(formatTraceDuration(500_000)).toBe('500µs')
  })
})

describe('computeTraceLayout', () => {
  it('orders spans depth-first and computes geometry', () => {
    const spans = [
      makeSpan({spanId: 'b', parentId: 'a', name: 'child', startNs: 100, durationNs: 500, type: 'db'}),
      makeSpan({spanId: 'a', parentId: '', name: 'root', startNs: 0, durationNs: 1000, service: 'api'}),
    ]
    const layout = computeTraceLayout(spans)
    expect(layout.rows.map((r) => r.span.spanId)).toEqual(['a', 'b'])
    expect(layout.rows[0].depth).toBe(0)
    expect(layout.rows[1].depth).toBe(1)
    expect(layout.traceDurationNs).toBe(1000)
    expect(layout.rows[0].offsetPct).toBe(0)
    expect(layout.rows[0].widthPct).toBe(100)
    expect(layout.rows[1].offsetPct).toBeCloseTo(10)
    expect(layout.rows[1].widthPct).toBeCloseTo(50)
  })

  it('counts services and errors', () => {
    const spans = [
      makeSpan({spanId: 'a', service: 'api', error: 1}),
      makeSpan({spanId: 'b', service: 'db', statusCode: 503, parentId: 'a'}),
      makeSpan({spanId: 'c', service: 'api', parentId: 'a'}),
    ]
    const layout = computeTraceLayout(spans)
    expect(layout.serviceCount).toBe(2)
    expect(layout.errorCount).toBe(2)
  })

  it('handles an empty span list', () => {
    const layout = computeTraceLayout([])
    expect(layout.rows).toHaveLength(0)
    expect(layout.traceDurationNs).toBe(1)
  })
})

describe('tokenizeJson', () => {
  it('classifies keys, strings, numbers and booleans', () => {
    const tokens = tokenizeJson('{\n  "a": "x",\n  "n": 504,\n  "b": true\n}')
    const kinds = tokens.filter((t) => t.kind !== 'plain')
    expect(kinds).toEqual([
      {text: '"a":', kind: 'key'},
      {text: '"x"', kind: 'string'},
      {text: '"n":', kind: 'key'},
      {text: '504', kind: 'number'},
      {text: '"b":', kind: 'key'},
      {text: 'true', kind: 'boolean'},
    ])
  })

  it('reassembles to the original string', () => {
    const json = '{"k": [1, 2.5, false, null]}'
    expect(tokenizeJson(json).map((t) => t.text).join('')).toBe(json)
  })
})

describe('computeLogPinPct', () => {
  const layout = {traceStartNs: 1_000_000_000, traceDurationNs: 1_000_000_000}

  it('locates the pin inside the window', () => {
    // logNs = 1.5e9 → halfway through a 1s window starting at 1s
    expect(computeLogPinPct(layout, 1500)).toBeCloseTo(50)
  })

  it('clamps a log just past the trace end to 100', () => {
    expect(computeLogPinPct(layout, 2050)).toBe(100)
  })

  it('returns null when far outside the window', () => {
    expect(computeLogPinPct(layout, 5000)).toBeNull()
  })
})
