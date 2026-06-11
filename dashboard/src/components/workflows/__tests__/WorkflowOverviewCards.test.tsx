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
import type {WorkflowOverviewResponse} from '@/lib/api'
import {WorkflowOverviewCards} from '../WorkflowOverviewCards'

const overview: WorkflowOverviewResponse = {
  total_workflows: 5,
  enabled_workflows: 3,
  published_workflows: 2,
  runs_last_30d: 1200,
  success_rate: 0.95,
  failed_last_30d: 6,
  top_workflows: [
    {workflow_id: '11111111-1111-4111-8111-111111111111', name: 'Pager', run_count: 40},
    {workflow_id: '22222222-2222-4222-8222-222222222222', name: 'Slack notify', run_count: 12},
  ],
}

describe('WorkflowOverviewCards', () => {
  it('renders the metrics with success rate as a percentage', () => {
    render(<WorkflowOverviewCards overview={overview} />)
    expect(screen.getByText('Success rate')).toBeInTheDocument()
    expect(screen.getByText('95%')).toBeInTheDocument()
    expect(screen.getByText('1,200')).toBeInTheDocument()
    expect(screen.getByText('Pager')).toBeInTheDocument()
    expect(screen.getByText('40 runs')).toBeInTheDocument()
  })

  it('renders zero/empty states', () => {
    const empty: WorkflowOverviewResponse = {
      total_workflows: 0,
      enabled_workflows: 0,
      published_workflows: 0,
      runs_last_30d: 0,
      success_rate: 0,
      failed_last_30d: 0,
      top_workflows: [],
    }
    render(<WorkflowOverviewCards overview={empty} />)
    expect(screen.getByText('0%')).toBeInTheDocument()
    expect(screen.getByText(/No runs recorded yet/i)).toBeInTheDocument()
  })
})
