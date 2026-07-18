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
import {act, renderHook} from '@testing-library/react'
import {useSeriesVisibility} from '../useSeriesVisibility'
import {CHART_TOKENS, seriesColor} from '../chartColors'

const KEYS = ['a', 'b', 'c']

function hiddenArray(hidden: ReadonlySet<string>) {
  return [...hidden].sort()
}

describe('useSeriesVisibility', () => {
  it('starts with every series visible', () => {
    const {result} = renderHook(() => useSeriesVisibility(KEYS))
    expect(result.current.hidden.size).toBe(0)
  })

  it('isolates a series on a plain click (hides all others)', () => {
    const {result} = renderHook(() => useSeriesVisibility(KEYS))
    act(() => result.current.toggle('b', false))
    expect(hiddenArray(result.current.hidden)).toEqual(['a', 'c'])
  })

  it('restores all when clicking the already-isolated series', () => {
    const {result} = renderHook(() => useSeriesVisibility(KEYS))
    act(() => result.current.toggle('b', false))
    act(() => result.current.toggle('b', false))
    expect(result.current.hidden.size).toBe(0)
  })

  it('isolates a different series when clicking another while isolated', () => {
    const {result} = renderHook(() => useSeriesVisibility(KEYS))
    act(() => result.current.toggle('b', false))
    act(() => result.current.toggle('a', false))
    expect(hiddenArray(result.current.hidden)).toEqual(['b', 'c'])
  })

  it('toggles a single series with a modifier (additive) click', () => {
    const {result} = renderHook(() => useSeriesVisibility(KEYS))
    act(() => result.current.toggle('a', true))
    expect(hiddenArray(result.current.hidden)).toEqual(['a'])
    act(() => result.current.toggle('a', true))
    expect(result.current.hidden.size).toBe(0)
  })

  it('never hides the last visible series via additive clicks', () => {
    const {result} = renderHook(() => useSeriesVisibility(['a', 'b']))
    act(() => result.current.toggle('a', true))
    act(() => result.current.toggle('b', true)) // would blank the chart — ignored
    expect(hiddenArray(result.current.hidden)).toEqual(['a'])
  })

  it('resets visibility when the set of series changes', () => {
    const {result, rerender} = renderHook(({keys}) => useSeriesVisibility(keys), {
      initialProps: {keys: KEYS},
    })
    act(() => result.current.toggle('b', false))
    expect(result.current.hidden.size).toBe(2)

    rerender({keys: ['x', 'y', 'z']})
    expect(result.current.hidden.size).toBe(0)
  })

  it('keeps visibility when the series set is unchanged (stable across refresh)', () => {
    const {result, rerender} = renderHook(({keys}) => useSeriesVisibility(keys), {
      initialProps: {keys: KEYS},
    })
    act(() => result.current.toggle('b', false))
    rerender({keys: ['a', 'b', 'c']}) // same keys, new array identity (e.g. auto-refresh)
    expect(hiddenArray(result.current.hidden)).toEqual(['a', 'c'])
  })
})

describe('seriesColor', () => {
  it('maps the first ten series to the theme palette tokens', () => {
    CHART_TOKENS.forEach((token, i) => {
      expect(seriesColor(i)).toBe(token)
    })
  })

  it('generates distinct, deterministic hues beyond the palette', () => {
    const c10 = seriesColor(10)
    const c11 = seriesColor(11)
    expect(c10).toMatch(/^hsl\(\d+ 72% 60%\)$/)
    expect(c11).toMatch(/^hsl\(\d+ 72% 60%\)$/)
    expect(c10).not.toBe(c11)
    expect(seriesColor(10)).toBe(c10) // deterministic
  })
})
