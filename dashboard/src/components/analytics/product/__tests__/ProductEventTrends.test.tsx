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
import {describe, expect, it} from 'vitest'

import {ProductEventTrends} from '../ProductEventTrends'
import {formatCompact, formatPercent, formatSignedRatio, pointsDelta, relativeDelta} from '../format'

describe('product analytics helpers', () => {
  it('formats metric deltas and compact values', () => {
    expect(formatCompact(1_250)).toBe('1.3K')
    expect(formatPercent(41.25)).toBe('41%')
    expect(formatSignedRatio(0.12)).toBe('+12%')
    expect(formatSignedRatio(-0.03)).toBe('−3%')
    expect(formatSignedRatio(0)).toBe('0%')
    expect(relativeDelta(120, 100)).toEqual({value: '20.0%', direction: 'up'})
    expect(relativeDelta(80, 100)).toEqual({value: '20.0%', direction: 'down'})
    expect(relativeDelta(100, 100)).toEqual({value: '0.0%', direction: 'flat'})
    expect(relativeDelta(100, 0)).toBeUndefined()
    expect(pointsDelta(42, 40)).toEqual({value: '2.0pp', direction: 'up'})
    expect(pointsDelta(38, 40)).toEqual({value: '2.0pp', direction: 'down'})
    expect(pointsDelta(40, 40)).toEqual({value: '0.0pp', direction: 'flat'})
    expect(pointsDelta(40)).toBeUndefined()
  })
})

describe('ProductEventTrends', () => {
  it('renders loading, empty, and signed change states', () => {
    const {rerender} = render(<ProductEventTrends isLoading />)

    expect(document.querySelectorAll('.animate-pulse')).toHaveLength(6)

    rerender(<ProductEventTrends data={[]} />)
    expect(screen.getByText('No events for the selected period')).toBeInTheDocument()

    rerender(
      <ProductEventTrends
        data={[
          {name: 'signup_completed', count: 24, users: 18, changeRatio: 0.25},
          {name: 'invite_sent', count: 8, users: 5, changeRatio: -0.1},
          {name: 'saved_view', count: 3, users: 3, changeRatio: 0},
          {name: 'profile_opened', count: 2, users: 1},
        ]}
      />,
    )

    expect(screen.getByText('signup_completed')).toBeInTheDocument()
    expect(screen.getByText('+25%')).toBeInTheDocument()
    expect(screen.getByText('−10%')).toBeInTheDocument()
    expect(screen.getByText('0%')).toBeInTheDocument()
    expect(screen.getByText('—')).toBeInTheDocument()
  })
})
