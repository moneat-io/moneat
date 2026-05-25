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
import {pivotData, valueKeySeries} from '../widgetSeries'

describe('widgetSeries', () => {
  it('keeps full data keys while displaying the shortest unique label dimensions', () => {
    const data = [
      {
        time_bucket: 1,
        service: 'moneat-backend',
        endpoint: 'api.moneat.io:443',
        environment: 'moneat-prod-backend',
        queue: 'moneat:dd:dbm:dlq',
        queue_type: 'dlq',
        worker: 'DBM',
        depth: 12,
      },
      {
        time_bucket: 1,
        service: 'moneat-backend',
        endpoint: 'api.moneat.io:443',
        environment: 'moneat-prod-backend',
        queue: 'moneat:dd:dbm:queue',
        queue_type: 'primary',
        worker: 'DBM',
        depth: 4,
      },
      {
        time_bucket: 1,
        service: 'moneat-backend',
        endpoint: 'api.moneat.io:443',
        environment: 'moneat-prod-backend',
        queue: 'moneat:analytics:queue',
        queue_type: 'primary',
        worker: 'Analytics',
        depth: 7,
      },
    ]

    const result = pivotData(
      data,
      'time_bucket',
      ['service', 'endpoint', 'environment', 'queue', 'queue_type', 'worker'],
      ['depth'],
    )

    expect(result.series.map((series) => series.name)).toEqual([
      'dlq, DBM',
      'primary, DBM',
      'primary, Analytics',
    ])
    expect(result.series[0].key).toBe(
      'moneat-backend, api.moneat.io:443, moneat-prod-backend, moneat:dd:dbm:dlq, dlq, DBM',
    )
    expect(result.series[0].name).not.toContain('moneat-backend')
    expect(result.pivoted[0][result.series[0].key]).toBe(12)
  })

  it('falls back to the available label when every label value is constant', () => {
    const result = pivotData(
      [{time_bucket: 1, worker: 'DBM', depth: 12}],
      'time_bucket',
      ['worker'],
      ['depth'],
    )

    expect(result.series).toEqual([
      {key: 'DBM', name: 'DBM'},
    ])
  })

  it('returns unpivoted data when label keys are absent', () => {
    const data = [{time_bucket: 1, queue_depth: 12}]

    expect(pivotData(data, 'time_bucket', [], ['queue_depth'])).toEqual({
      pivoted: data,
      series: [{key: 'queue_depth', name: 'queue depth'}],
    })
  })

  it('sorts pivoted rows by timestamp', () => {
    const result = pivotData(
      [
        {time_bucket: 2, worker: 'DBM', depth: 4},
        {time_bucket: 1, worker: 'DBM', depth: 12},
      ],
      'time_bucket',
      ['worker'],
      ['depth'],
    )

    expect(result.pivoted.map((row) => row.time_bucket)).toEqual([1, 2])
  })

  it('uses readable names for unpivoted numeric series', () => {
    expect(valueKeySeries(['queue_depth', 'dlq_depth'])).toEqual([
      {key: 'queue_depth', name: 'queue depth'},
      {key: 'dlq_depth', name: 'dlq depth'},
    ])
  })
})
