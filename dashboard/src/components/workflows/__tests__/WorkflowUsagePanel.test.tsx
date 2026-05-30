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
import type {WorkflowUsageResponse} from '@/lib/api'
import {WorkflowUsagePanel} from '../WorkflowUsagePanel'

describe('WorkflowUsagePanel', () => {
  it('renders a metered plan with a progress bar and remaining runs', () => {
    const usage: WorkflowUsageResponse = {
      period: '2026-05',
      used: 250,
      limit: 1000,
      remaining: 750,
      unlimited: false,
    }
    render(<WorkflowUsagePanel usage={usage} />)
    expect(screen.getByText('2026-05')).toBeInTheDocument()
    expect(screen.getByText('250')).toBeInTheDocument()
    expect(screen.getByText('/ 1,000')).toBeInTheDocument()
    expect(screen.getByText(/750 runs remaining/i)).toBeInTheDocument()
    expect(screen.getByTestId('usage-bar')).toHaveStyle({width: '25%'})
  })

  it('renders an unlimited plan without a progress bar', () => {
    const usage: WorkflowUsageResponse = {
      period: '2026-05',
      used: 99,
      limit: null,
      remaining: null,
      unlimited: true,
    }
    render(<WorkflowUsagePanel usage={usage} />)
    expect(screen.getByText('unlimited')).toBeInTheDocument()
    expect(screen.getByText(/no run limit/i)).toBeInTheDocument()
    expect(screen.queryByTestId('usage-bar')).not.toBeInTheDocument()
  })

  it('handles a null limit that is not flagged unlimited', () => {
    const usage: WorkflowUsageResponse = {
      period: '2026-05',
      used: 5,
      limit: null,
      remaining: null,
      unlimited: false,
    }
    render(<WorkflowUsagePanel usage={usage} />)
    expect(screen.getByText('/ unlimited')).toBeInTheDocument()
    expect(screen.getByText(/not tracked for this plan/i)).toBeInTheDocument()
    expect(screen.queryByTestId('usage-bar')).not.toBeInTheDocument()
  })
})
