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

import {fireEvent, render, screen} from '@testing-library/react'
import {describe, expect, it, vi} from 'vitest'
import {SignalsTable} from '../SignalsTable'
import {makeSignal} from './fixtures'

describe('SignalsTable', () => {
  it('renders rows with severity, status label, source and samples', () => {
    render(
      <SignalsTable
        signals={[makeSignal({status: 'under_review', source: 'agent_runtime', sample_count: 7})]}
        onSelect={vi.fn()}
      />
    )
    expect(screen.getByText('Repeated failed logins')).toBeInTheDocument()
    expect(screen.getByText('Under review')).toBeInTheDocument()
    expect(screen.getByText('agent runtime')).toBeInTheDocument()
    expect(screen.getByText('7')).toBeInTheDocument()
  })

  it('shows an empty state with no signals', () => {
    render(<SignalsTable signals={[]} onSelect={vi.fn()} />)
    expect(screen.getByText('No signals')).toBeInTheDocument()
  })

  it('calls onSelect with the clicked signal', () => {
    const onSelect = vi.fn()
    const signal = makeSignal({id: 42})
    render(<SignalsTable signals={[signal]} onSelect={onSelect} />)
    fireEvent.click(screen.getByText('Repeated failed logins'))
    expect(onSelect).toHaveBeenCalledWith(signal)
  })
})
