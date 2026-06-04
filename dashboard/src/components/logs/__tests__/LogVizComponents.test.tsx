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

import type React from 'react'
import {fireEvent, render, screen} from '@testing-library/react'
import {describe, expect, it, vi} from 'vitest'
import {LogAggregateTable} from '@/components/logs/LogAggregateTable'
import {LogHistogram} from '@/components/logs/LogHistogram'
import {LogPieChart} from '@/components/logs/LogPieChart'
import {LogTopList} from '@/components/logs/LogTopList'
import type {LogAggregateBucket} from '@/lib/api'

vi.mock('@/hooks/useTimezone', () => ({
  useTimezone: () => ({timezone: 'UTC', updateTimezone: vi.fn()}),
}))

vi.mock('recharts', () => ({
  Bar: ({dataKey, fill}: {dataKey: string; fill: string}) => (
    <span data-fill={fill} data-testid={`bar-${dataKey}`} />
  ),
  BarChart: ({
    children,
    onClick,
  }: {
    children?: React.ReactNode
    onClick?: (state: unknown) => void
  }) => (
    <div data-testid="bar-chart">
      <button
        type="button"
        onClick={() => onClick?.({
          activePayload: [{payload: {timestamp: '2026-06-03T11:00:00.000Z'}}],
        })}
      >
        Use bucket
      </button>
      <button type="button" onClick={() => onClick?.({activePayload: []})}>
        Empty payload
      </button>
      {children}
    </div>
  ),
  CartesianGrid: () => <span data-testid="grid" />,
  Cell: ({fill}: {fill: string}) => <span data-fill={fill} data-testid="pie-cell" />,
  Legend: () => <span data-testid="legend" />,
  Pie: ({
    children,
    data,
    label,
  }: {
    children?: React.ReactNode
    data: Array<{name: string; value: number}>
    label?: (entry: {name: string; percent: number}) => string
  }) => (
    <div data-testid="pie">
      {data.map((entry, index) => (
        <span key={entry.name}>{label?.({name: entry.name, percent: index === 0 ? 0.5 : 0.125})}</span>
      ))}
      {children}
    </div>
  ),
  PieChart: ({children}: {children?: React.ReactNode}) => <div data-testid="pie-chart">{children}</div>,
  ResponsiveContainer: ({
    children,
    height,
  }: {
    children?: React.ReactNode
    height?: number | string
  }) => (
    <div data-height={height} data-testid="responsive-container">
      {children}
    </div>
  ),
  Tooltip: ({
    content,
    formatter,
    labelFormatter,
  }: {
    content?: React.ReactNode
    formatter?: (value: unknown, name: unknown) => unknown
    labelFormatter?: (value: unknown) => unknown
  }) => (
    <div data-testid="tooltip">
      {String(labelFormatter?.(Date.parse('2026-06-03T11:00:00.000Z')) ?? '')}
      {String(formatter?.(1_500_000, 'total') ?? '')}
      {String(formatter?.('not-a-number', undefined) ?? '')}
      {content}
    </div>
  ),
  XAxis: ({tickFormatter}: {tickFormatter?: (value: number) => string}) => (
    <div data-testid="x-axis">
      {tickFormatter?.(Date.parse('2026-06-03T11:00:00.000Z'))}
    </div>
  ),
  YAxis: ({tickFormatter}: {tickFormatter?: (value: number) => string}) => (
    <div data-testid="y-axis">
      {[999, 1_000, 1_500, 1_000_000, 1_500_000]
        .map((value) => tickFormatter?.(value))
        .join('|')}
    </div>
  ),
}))

const topValues = [
  {value: 'api', count: 1_500_000_000},
  {value: 'worker', count: 1_500_000},
  {value: 'web', count: 1_500},
  {value: 'cron', count: 12},
]

const histogramBuckets: LogAggregateBucket[] = [
  {timestamp: 'not-a-date', count: 99, groups: {error: 99}},
  {
    timestamp: '2026-06-03T10:00:00.000Z',
    count: 5,
    groups: {beta: 2, error: 3},
  },
  {
    timestamp: '2026-06-03T11:00:00.000Z',
    count: 3,
    groups: {alpha: 1, warn: 2},
  },
]

describe('log visualization components', () => {
  it('sorts aggregate table rows and forwards clicked values', () => {
    const onValueClick = vi.fn()

    render(
      <LogAggregateTable
        field="service"
        onValueClick={onValueClick}
        totalCount={1_501_501_512}
        values={topValues}
      />
    )

    expect(screen.getByText('1.5B')).toBeInTheDocument()
    expect(screen.getByText('1.5M')).toBeInTheDocument()
    expect(screen.getByText('1.5k')).toBeInTheDocument()
    expect(screen.getByText('12')).toBeInTheDocument()

    fireEvent.click(screen.getByText('service'))
    fireEvent.click(screen.getByText('service'))
    fireEvent.click(screen.getByText('Count'))
    fireEvent.click(screen.getByText('worker'))

    expect(onValueClick).toHaveBeenCalledWith('worker')
  })

  it('renders aggregate table empty and zero-total states', () => {
    const {rerender} = render(
      <LogAggregateTable field="host" totalCount={0} values={[]} />
    )

    expect(screen.getByText('No data for field "host"')).toBeInTheDocument()

    rerender(
      <LogAggregateTable
        field="host"
        totalCount={0}
        values={[{value: 'host-a', count: 10}]}
      />
    )
    fireEvent.click(screen.getByText('host-a'))

    expect(screen.getByText('0.0%')).toBeInTheDocument()
  })

  it('renders top lists including empty and zero-count variants', () => {
    const onValueClick = vi.fn()
    const {rerender} = render(
      <LogTopList
        field="service"
        onValueClick={onValueClick}
        totalCount={1_501_501_512}
        values={topValues}
      />
    )

    fireEvent.click(screen.getByText('api'))

    expect(screen.getByText('Top values for service')).toBeInTheDocument()
    expect(screen.getByText('1.5B')).toBeInTheDocument()
    expect(onValueClick).toHaveBeenCalledWith('api')

    rerender(<LogTopList field="service" totalCount={0} values={[{value: 'zero', count: 0}]} />)
    expect(screen.getByText('0.0%')).toBeInTheDocument()

    rerender(<LogTopList field="service" totalCount={0} values={[]} />)
    expect(screen.getByText('No data for field "service"')).toBeInTheDocument()
  })

  it('renders pie chart data and empty state', () => {
    const values = Array.from({length: 10}, (_, index) => ({
      value: `service-${index}`,
      count: index + 1,
    }))
    const {rerender} = render(<LogPieChart field="service" height={180} values={values} />)

    expect(screen.getByText('Distribution by service')).toBeInTheDocument()
    expect(screen.getByText('service-0 (50%)')).toBeInTheDocument()
    expect(screen.getAllByTestId('pie-cell')).toHaveLength(8)

    rerender(<LogPieChart field="service" values={[]} />)
    expect(screen.getByText('No data for field "service"')).toBeInTheDocument()
  })

  it('renders grouped histograms and forwards bucket clicks', () => {
    const onBucketClick = vi.fn()

    render(
      <LogHistogram
        buckets={histogramBuckets}
        interval="1h"
        onBucketClick={onBucketClick}
        rangeFrom="2026-06-03T09:00:00.000Z"
        rangeTo="2026-06-03T12:00:00.000Z"
      />
    )

    expect(screen.getByTestId('bar-error')).toBeInTheDocument()
    expect(screen.getByTestId('bar-warn')).toBeInTheDocument()
    expect(screen.getByTestId('bar-alpha')).toBeInTheDocument()
    expect(screen.getByTestId('bar-beta')).toBeInTheDocument()

    fireEvent.click(screen.getByRole('button', {name: 'Use bucket'}))
    fireEvent.click(screen.getByRole('button', {name: 'Empty payload'}))

    expect(onBucketClick).toHaveBeenCalledTimes(1)
    expect(onBucketClick).toHaveBeenCalledWith('2026-06-03T11:00:00.000Z')
  })

  it('renders ungrouped and long-range histograms', () => {
    const {container, rerender} = render(<LogHistogram buckets={[]} />)
    expect(container).toBeEmptyDOMElement()

    rerender(
      <LogHistogram
        buckets={[histogramBuckets[1]]}
        grouped={false}
        interval="5m"
        rangeFrom="bad-date"
        rangeTo="2026-06-03T12:00:00.000Z"
      />
    )
    fireEvent.click(screen.getByRole('button', {name: 'Use bucket'}))
    expect(screen.getByTestId('bar-total')).toBeInTheDocument()

    rerender(
      <LogHistogram
        buckets={[histogramBuckets[1]]}
        interval="1d"
        rangeFrom="2026-05-01T00:00:00.000Z"
        rangeTo="2026-06-03T12:00:00.000Z"
      />
    )
    expect(screen.getByTestId('x-axis')).toBeInTheDocument()
  })
})
