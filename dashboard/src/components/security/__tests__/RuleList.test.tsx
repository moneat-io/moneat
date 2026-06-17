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
import {RuleList} from '../RuleList'
import {makeRule} from './fixtures'

describe('RuleList', () => {
  it('renders rule name, type label and severity', () => {
    render(
      <RuleList
        rules={[makeRule({type: 'new_value', severity: 'critical'})]}
        onEdit={vi.fn()}
        onToggle={vi.fn()}
        onDelete={vi.fn()}
      />
    )
    expect(screen.getByText('Brute force')).toBeInTheDocument()
    expect(screen.getByText('New value')).toBeInTheDocument()
    expect(screen.getByText('critical')).toBeInTheDocument()
  })

  it('shows an empty state', () => {
    render(<RuleList rules={[]} onEdit={vi.fn()} onToggle={vi.fn()} onDelete={vi.fn()} />)
    expect(screen.getByText('No detection rules')).toBeInTheDocument()
  })

  it('toggles enabled via the switch', () => {
    const onToggle = vi.fn()
    const rule = makeRule({enabled: false})
    render(<RuleList rules={[rule]} onEdit={vi.fn()} onToggle={onToggle} onDelete={vi.fn()} />)
    fireEvent.click(screen.getByRole('switch', {name: /toggle brute force/i}))
    expect(onToggle).toHaveBeenCalledWith(rule, true)
  })

  it('fires edit and delete callbacks', () => {
    const onEdit = vi.fn()
    const onDelete = vi.fn()
    const rule = makeRule()
    render(<RuleList rules={[rule]} onEdit={onEdit} onToggle={vi.fn()} onDelete={onDelete} />)
    fireEvent.click(screen.getByRole('button', {name: 'Edit'}))
    expect(onEdit).toHaveBeenCalledWith(rule)
    fireEvent.click(screen.getByRole('button', {name: 'Delete'}))
    expect(onDelete).toHaveBeenCalledWith(rule)
  })
})
