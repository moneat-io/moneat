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
import {SignalFilterBar} from '../SignalFilterBar'

describe('SignalFilterBar', () => {
  it('selects a severity when its chip is clicked', () => {
    const onChange = vi.fn()
    render(<SignalFilterBar filters={{}} onChange={onChange} sources={['agent_runtime']} />)
    fireEvent.click(screen.getByRole('button', {name: 'high'}))
    expect(onChange).toHaveBeenCalledWith({severity: 'high'})
  })

  it('toggles an active filter off when clicked again', () => {
    const onChange = vi.fn()
    render(<SignalFilterBar filters={{status: 'open'}} onChange={onChange} sources={[]} />)
    const openChip = screen.getByRole('button', {name: 'Open'})
    expect(openChip).toHaveAttribute('aria-pressed', 'true')
    fireEvent.click(openChip)
    expect(onChange).toHaveBeenCalledWith({status: undefined})
  })

  it('renders source chips and selects them', () => {
    const onChange = vi.fn()
    render(<SignalFilterBar filters={{}} onChange={onChange} sources={['agent_runtime', 'detection']} />)
    fireEvent.click(screen.getByRole('button', {name: 'detection'}))
    expect(onChange).toHaveBeenCalledWith({source: 'detection'})
  })

  it('hides the source group when there are no sources', () => {
    render(<SignalFilterBar filters={{}} onChange={vi.fn()} sources={[]} />)
    expect(screen.queryByText('Source')).not.toBeInTheDocument()
  })
})
