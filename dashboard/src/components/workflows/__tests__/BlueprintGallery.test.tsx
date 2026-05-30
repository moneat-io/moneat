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
import type {WorkflowBlueprintSummary} from '@/lib/api'
import {BlueprintGallery} from '../BlueprintGallery'

const alerting: WorkflowBlueprintSummary = {
  key: 'error-spike',
  name: 'Error spike alert',
  description: 'Notify on error spikes',
  category: 'alerting',
  trigger_name: 'issue.created',
  tags: ['errors', 'alerting'],
}

const automation: WorkflowBlueprintSummary = {
  key: 'auto-resolve',
  name: 'Auto resolve',
  description: 'Resolve stale issues',
  category: 'automation',
  trigger_name: 'issue.created',
  tags: [],
}

describe('BlueprintGallery', () => {
  it('shows an empty state when there are no blueprints', () => {
    render(<BlueprintGallery blueprints={[]} onUseBlueprint={vi.fn()} />)
    expect(screen.getByText(/No blueprints available/i)).toBeInTheDocument()
  })

  it('groups blueprints by category and renders tags', () => {
    render(<BlueprintGallery blueprints={[automation, alerting]} onUseBlueprint={vi.fn()} />)
    expect(screen.getByRole('heading', {name: 'alerting'})).toBeInTheDocument()
    expect(screen.getByRole('heading', {name: 'automation'})).toBeInTheDocument()
    expect(screen.getByText('errors')).toBeInTheDocument()
    expect(screen.getByText('Error spike alert')).toBeInTheDocument()
  })

  it('falls back to an Other category when none is set', () => {
    const uncategorized = {...automation, category: ''}
    render(<BlueprintGallery blueprints={[uncategorized]} onUseBlueprint={vi.fn()} />)
    expect(screen.getByRole('heading', {name: 'Other'})).toBeInTheDocument()
  })

  it('invokes onUseBlueprint when the button is clicked', () => {
    const onUse = vi.fn()
    render(<BlueprintGallery blueprints={[alerting]} onUseBlueprint={onUse} />)
    fireEvent.click(screen.getByRole('button', {name: /Use blueprint/i}))
    expect(onUse).toHaveBeenCalledWith(alerting)
  })

  it('disables the pending blueprint and disables all when disabled', () => {
    const {rerender} = render(
      <BlueprintGallery blueprints={[alerting]} onUseBlueprint={vi.fn()} pendingKey="error-spike" />
    )
    expect(screen.getByRole('button', {name: /Use blueprint/i})).toBeDisabled()

    rerender(<BlueprintGallery blueprints={[alerting]} onUseBlueprint={vi.fn()} disabled />)
    expect(screen.getByRole('button', {name: /Use blueprint/i})).toBeDisabled()
  })
})
