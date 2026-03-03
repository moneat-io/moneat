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
import {formatValue, findValueMapping} from '../formatValue'

describe('formatValue', () => {
  describe('unit: none', () => {
    it('formats integers without decimals', () => {
      expect(formatValue(42, 'none')).toBe('42')
    })

    it('formats floats with 2 decimals by default', () => {
      expect(formatValue(3.14159, 'none')).toBe('3.14')
    })

    it('respects explicit decimals', () => {
      expect(formatValue(3.14159, 'none', '3')).toBe('3.142')
      expect(formatValue(3.14159, 'none', '0')).toBe('3')
    })
  })

  describe('unit: short', () => {
    it('formats small numbers as-is', () => {
      expect(formatValue(42, 'short')).toBe('42')
    })

    it('formats thousands as K', () => {
      expect(formatValue(1500, 'short')).toBe('1.5K')
    })

    it('formats millions as M', () => {
      expect(formatValue(2500000, 'short')).toBe('2.5M')
    })

    it('formats billions as B', () => {
      expect(formatValue(1000000000, 'short')).toBe('1.0B')
    })

    it('formats zero', () => {
      expect(formatValue(0, 'short')).toBe('0')
    })

    it('handles negative values', () => {
      expect(formatValue(-1500, 'short')).toBe('-1.5K')
    })
  })

  describe('unit: bytes', () => {
    it('formats zero bytes', () => {
      expect(formatValue(0, 'bytes')).toBe('0 B')
    })

    it('formats bytes', () => {
      expect(formatValue(512, 'bytes')).toBe('512 B')
    })

    it('formats kilobytes', () => {
      expect(formatValue(1536, 'bytes')).toBe('1.50 KB')
    })

    it('formats megabytes', () => {
      expect(formatValue(1048576, 'bytes')).toBe('1.00 MB')
    })

    it('formats gigabytes', () => {
      expect(formatValue(1073741824, 'bytes')).toBe('1.00 GB')
    })
  })

  describe('unit: bytes/s', () => {
    it('appends /s to byte formatting', () => {
      expect(formatValue(1048576, 'bytes/s')).toBe('1.00 MB/s')
    })
  })

  describe('unit: decbytes', () => {
    it('formats zero', () => {
      expect(formatValue(0, 'decbytes')).toBe('0 B')
    })

    it('formats kilobytes (base 1000)', () => {
      expect(formatValue(1500, 'decbytes')).toBe('1.50 kB')
    })

    it('formats megabytes (base 1000)', () => {
      expect(formatValue(1000000, 'decbytes')).toBe('1.00 MB')
    })

    it('formats gigabytes (base 1000)', () => {
      expect(formatValue(1000000000, 'decbytes')).toBe('1.00 GB')
    })

    it('respects decimals', () => {
      expect(formatValue(1500, 'decbytes', '0')).toBe('2 kB')
    })
  })

  describe('unit: KBs', () => {
    it('formats kB/s', () => {
      expect(formatValue(4.5, 'KBs')).toBe('4.5 kB/s')
    })

    it('formats large values as MB/s', () => {
      expect(formatValue(1500, 'KBs')).toBe('1.5 MB/s')
    })

    it('respects decimals', () => {
      expect(formatValue(4.567, 'KBs', '2')).toBe('4.57 kB/s')
    })
  })

  describe('unit: percent', () => {
    it('formats percentage', () => {
      expect(formatValue(95.5, 'percent')).toBe('95.5%')
    })

    it('respects decimals', () => {
      expect(formatValue(95.5678, 'percent', '2')).toBe('95.57%')
    })
  })

  describe('unit: ms', () => {
    it('formats small values as ms', () => {
      expect(formatValue(250, 'ms')).toBe('250.0 ms')
    })

    it('converts large values to seconds', () => {
      expect(formatValue(1500, 'ms')).toBe('1.5 s')
    })
  })

  describe('unit: s', () => {
    it('formats small values as seconds', () => {
      expect(formatValue(30, 's')).toBe('30.0 s')
    })

    it('converts to minutes', () => {
      expect(formatValue(90, 's')).toBe('1.5 m')
    })

    it('converts to hours', () => {
      expect(formatValue(7200, 's')).toBe('2.0 h')
    })
  })

  describe('unit: reqps', () => {
    it('formats requests per second', () => {
      expect(formatValue(150.5, 'reqps')).toBe('150.5 req/s')
    })
  })

  describe('unit: ops', () => {
    it('formats operations per second', () => {
      expect(formatValue(1000, 'ops')).toBe('1000.0 ops/s')
    })
  })

  describe('non-numeric values', () => {
    it('returns string values as-is', () => {
      expect(formatValue('hello', 'short')).toBe('hello')
    })

    it('returns null/undefined as empty string', () => {
      expect(formatValue(null, 'short')).toBe('')
      expect(formatValue(undefined, 'short')).toBe('')
    })

    it('handles Infinity', () => {
      expect(formatValue(Infinity, 'short')).toBe('Infinity')
    })
  })

  describe('value mappings', () => {
    const mappings = [
      {value: '0', text: 'Inactive'},
      {value: '1', text: 'Active', color: '#22c55e'},
      {value: '200', text: 'OK'},
    ]

    it('applies exact match mapping', () => {
      expect(formatValue(0, 'none', undefined, mappings)).toBe('Inactive')
      expect(formatValue(1, 'none', undefined, mappings)).toBe('Active')
      expect(formatValue(200, 'none', undefined, mappings)).toBe('OK')
    })

    it('falls through to unit formatting when no mapping matches', () => {
      expect(formatValue(42, 'short', undefined, mappings)).toBe('42')
    })

    it('maps string values', () => {
      expect(formatValue('200', 'none', undefined, mappings)).toBe('OK')
    })
  })

  describe('decimals: auto', () => {
    it('uses auto decimals when specified', () => {
      expect(formatValue(42, 'none', 'auto')).toBe('42')
      expect(formatValue(3.14, 'none', 'auto')).toBe('3.14')
    })
  })
})

describe('findValueMapping', () => {
  const mappings = [
    {value: '0', text: 'Off'},
    {value: '1', text: 'On', color: '#22c55e'},
  ]

  it('finds matching mapping', () => {
    expect(findValueMapping(0, mappings)).toEqual({value: '0', text: 'Off'})
  })

  it('returns undefined when no match', () => {
    expect(findValueMapping(99, mappings)).toBeUndefined()
  })

  it('returns undefined for empty mappings', () => {
    expect(findValueMapping(0, [])).toBeUndefined()
    expect(findValueMapping(0, undefined)).toBeUndefined()
  })
})
