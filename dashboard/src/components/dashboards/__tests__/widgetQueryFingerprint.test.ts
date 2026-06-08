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
import type {QueryDsl} from '@/lib/api'
import {widgetQueryFingerprint} from '../widgetQueryFingerprint'

const baseQuery: QueryDsl = {
  dataSource: 'logs',
  metrics: [{function: 'count', alias: 'error_logs'}],
  groupBy: [{field: 'timestamp', type: 'time', interval: 'auto'}],
  filters: [{field: 'level', op: 'eq', value: 'error'}],
  limit: 5000,
  timeRange: {from: 'now-24h', to: 'now'},
}

describe('widgetQueryFingerprint', () => {
  it('changes when dashboard widget filters change', () => {
    const filteredQuery: QueryDsl = {
      ...baseQuery,
      filters: [
        ...baseQuery.filters,
        {field: 'service', op: 'eq', value: 'moneat-backend'},
      ],
    }

    expect(widgetQueryFingerprint([filteredQuery])).not.toBe(widgetQueryFingerprint([baseQuery]))
  })

  it('changes when metric aliases change', () => {
    const renamedMetricQuery: QueryDsl = {
      ...baseQuery,
      metrics: [{function: 'count', alias: 'backend_errors'}],
    }

    expect(widgetQueryFingerprint([renamedMetricQuery])).not.toBe(widgetQueryFingerprint([baseQuery]))
  })
})
