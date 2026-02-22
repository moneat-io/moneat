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
import {render, screen} from '@testing-library/react'
import {TopListWidget} from '../TopListWidget'

describe('TopListWidget', () => {
  it('renders nothing when data is empty', () => {
    const {container} = render(<TopListWidget data={[]} />)
    expect(container.firstChild).toBeNull()
  })

  it('renders rows with labels and values', () => {
    const data = [
      {endpoint: '/api/users', count: 100},
      {endpoint: '/api/orders', count: 80},
      {endpoint: '/api/health', count: 20},
    ]
    render(<TopListWidget data={data} />)
    expect(screen.getByText('/api/users')).toBeInTheDocument()
    expect(screen.getByText('/api/orders')).toBeInTheDocument()
    expect(screen.getByText('/api/health')).toBeInTheDocument()
    expect(screen.getByText('100')).toBeInTheDocument()
    expect(screen.getByText('80')).toBeInTheDocument()
    expect(screen.getByText('20')).toBeInTheDocument()
  })

  it('limits display to 20 items', () => {
    const data = Array.from({length: 25}, (_, i) => ({
      name: `item-${i}`,
      value: 25 - i,
    }))
    render(<TopListWidget data={data} />)
    expect(screen.getByText('item-0')).toBeInTheDocument()
    expect(screen.getByText('item-19')).toBeInTheDocument()
    expect(screen.queryByText('item-20')).not.toBeInTheDocument()
  })

  it('renders percentage bar widths correctly', () => {
    const data = [
      {name: 'Max', value: 100},
      {name: 'Half', value: 50},
    ]
    const {container} = render(<TopListWidget data={data} />)
    const bars = container.querySelectorAll('.bg-primary\\/10')
    expect(bars).toHaveLength(2)
    expect((bars[0] as HTMLElement).style.width).toBe('100%')
    expect((bars[1] as HTMLElement).style.width).toBe('50%')
  })

  it('handles numeric-only data', () => {
    const data = [
      {x: 10, y: 100},
      {x: 20, y: 200},
    ]
    const {container} = render(<TopListWidget data={data} />)
    expect(container.querySelectorAll('.relative').length).toBeGreaterThan(0)
  })
})
