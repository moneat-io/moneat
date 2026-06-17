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

import React from 'react'
import {describe, it, expect, vi, beforeEach} from 'vitest'
import {render, screen} from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import {DashboardToolbar} from '../DashboardToolbar'
import type {DashboardVariable} from '@/lib/api'

globalThis.HTMLElement.prototype.scrollIntoView = vi.fn()
globalThis.HTMLElement.prototype.hasPointerCapture = vi.fn()
globalThis.HTMLElement.prototype.setPointerCapture = vi.fn()
globalThis.HTMLElement.prototype.releasePointerCapture = vi.fn()
globalThis.ResizeObserver ??= class {
  observe() {}
  unobserve() {}
  disconnect() {}
}

vi.mock('@tanstack/react-router', () => ({
  Link: ({children, to, ...props}: {children: React.ReactNode; to: string; [key: string]: unknown}) => (
    <a href={to} {...props}>{children}</a>
  ),
}))

const defaultProps = {
  title: 'Test Dashboard',
  isEditing: false,
  onToggleEdit: vi.fn(),
  onSave: vi.fn(),
  onTitleChange: vi.fn(),
  onAddWidget: vi.fn(),
  onExport: vi.fn(),
  onDuplicate: vi.fn(),
  onDelete: vi.fn(),
  onToggleFavorite: vi.fn(),
  onSetDefault: vi.fn(),
  timeRange: {from: 'now-24h', to: 'now'},
  onTimeRangeChange: vi.fn(),
  refreshMs: 0,
  onRefreshMsChange: vi.fn(),
  onRefreshNow: vi.fn(),
  variableValues: {},
  onVariableChange: vi.fn(),
  onVariableSettings: vi.fn(),
}

function renderToolbar(props: Record<string, unknown> = {}) {
  return render(<DashboardToolbar {...defaultProps} {...props} />)
}

beforeEach(() => {
  vi.clearAllMocks()
})

describe('DashboardToolbar – title editing', () => {
  it('does not enter title edit mode when not editing', async () => {
    const user = userEvent.setup()
    renderToolbar({isEditing: false})
    await user.click(screen.getByText('Test Dashboard'))
    expect(screen.queryByDisplayValue('Test Dashboard')).not.toBeInTheDocument()
  })

  it('enters edit mode and saves on Enter', async () => {
    const user = userEvent.setup()
    const onTitleChange = vi.fn()
    renderToolbar({isEditing: true, onTitleChange})
    await user.click(screen.getByText('Test Dashboard'))
    const input = screen.getByDisplayValue('Test Dashboard')
    await user.clear(input)
    await user.type(input, 'Renamed{Enter}')
    expect(onTitleChange).toHaveBeenCalledWith('Renamed')
  })

  it('does not call onTitleChange when unchanged on blur', async () => {
    const user = userEvent.setup()
    const onTitleChange = vi.fn()
    renderToolbar({isEditing: true, onTitleChange})
    await user.click(screen.getByText('Test Dashboard'))
    await user.tab()
    expect(onTitleChange).not.toHaveBeenCalled()
  })

  it('does not call onTitleChange when trimmed to empty', async () => {
    const user = userEvent.setup()
    const onTitleChange = vi.fn()
    renderToolbar({isEditing: true, onTitleChange})
    await user.click(screen.getByText('Test Dashboard'))
    const input = screen.getByDisplayValue('Test Dashboard')
    await user.clear(input)
    await user.type(input, '   ')
    await user.tab()
    expect(onTitleChange).not.toHaveBeenCalled()
  })

  it('uses a button affordance only when editing', () => {
    const {rerender} = renderToolbar({isEditing: false})
    expect(screen.getByRole('heading', {name: 'Test Dashboard'})).toBeInTheDocument()
    expect(screen.queryByRole('button', {name: 'Test Dashboard'})).not.toBeInTheDocument()
    rerender(<DashboardToolbar {...defaultProps} isEditing />)
    expect(screen.getByRole('button', {name: 'Test Dashboard'})).toBeInTheDocument()
  })
})

describe('DashboardToolbar – manage variables', () => {
  const textboxVar: DashboardVariable = {
    name: 'env', label: 'Environment', type: 'textbox', options: [], current: 'production',
  }

  it('does not render a variables row without variables when not editing', () => {
    renderToolbar({variables: undefined})
    expect(screen.queryByText('Environment')).not.toBeInTheDocument()
  })

  it('shows the manage-variables gear only when editing', () => {
    const {rerender} = renderToolbar({isEditing: false, variables: [textboxVar]})
    expect(screen.queryByRole('button', {name: 'Manage variables'})).not.toBeInTheDocument()
    rerender(<DashboardToolbar {...defaultProps} isEditing variables={[textboxVar]} />)
    expect(screen.getByRole('button', {name: 'Manage variables'})).toBeInTheDocument()
  })

  it('keeps Add widget / Done when editing without a variable manager', () => {
    renderToolbar({isEditing: true, onVariableSettings: undefined})
    expect(screen.queryByRole('button', {name: 'Manage variables'})).not.toBeInTheDocument()
    expect(screen.getByRole('button', {name: 'Add widget'})).toBeInTheDocument()
    expect(screen.getByText('Done')).toBeInTheDocument()
  })
})

describe('DashboardToolbar – textbox variables', () => {
  const textboxVar: DashboardVariable = {
    name: 'env', label: 'Environment', type: 'textbox', options: [], current: 'production',
  }

  it('renders an inline input seeded from variableValues', () => {
    renderToolbar({variables: [textboxVar], variableValues: {env: 'staging'}})
    expect(screen.getByText('Environment')).toBeInTheDocument()
    expect(screen.getByDisplayValue('staging')).toBeInTheDocument()
  })

  it('falls back to current then default then empty', () => {
    const {rerender} = renderToolbar({variables: [textboxVar], variableValues: {}})
    expect(screen.getByDisplayValue('production')).toBeInTheDocument()

    const withDefault: DashboardVariable = {name: 'zone', label: 'Zone', type: 'textbox', options: [], default_value: 'us-central1'}
    rerender(<DashboardToolbar {...defaultProps} variables={[withDefault]} variableValues={{}} />)
    expect(screen.getByDisplayValue('us-central1')).toBeInTheDocument()

    const empty: DashboardVariable = {name: 'bare', label: 'Bare', type: 'textbox', options: []}
    rerender(<DashboardToolbar {...defaultProps} variables={[empty]} variableValues={{}} />)
    expect(screen.getByPlaceholderText('bare')).toHaveValue('')
  })

  it('reports edits via onVariableChange', async () => {
    const user = userEvent.setup()
    const onVariableChange = vi.fn()
    renderToolbar({variables: [textboxVar], variableValues: {env: 'prod'}, onVariableChange})
    const input = screen.getByDisplayValue('prod')
    await user.type(input, '!')
    expect(onVariableChange).toHaveBeenCalledWith('env', 'prod!')
  })

  it('uses the variable name as the label when no label is set', () => {
    const noLabel: DashboardVariable = {name: 'region', label: null, type: 'textbox', options: []}
    renderToolbar({variables: [noLabel], variableValues: {}})
    expect(screen.getByText('region')).toBeInTheDocument()
  })
})

describe('DashboardToolbar – constant variables', () => {
  it('shows the value but offers no picker', () => {
    const constantVar: DashboardVariable = {name: 'dc', label: 'DC', type: 'constant', options: [], current: 'us-east'}
    renderToolbar({variables: [constantVar], variableValues: {}})
    expect(screen.getByText('us-east')).toBeInTheDocument()
    expect(screen.queryByRole('button', {name: 'Variable DC'})).not.toBeInTheDocument()
  })
})

describe('DashboardToolbar – single-select variables', () => {
  const selectVar: DashboardVariable = {
    name: 'env', label: 'Environment', type: 'custom', options: ['production', 'staging'], current: 'production',
  }

  it('opens a picker and reports the chosen value', async () => {
    const user = userEvent.setup()
    const onVariableChange = vi.fn()
    renderToolbar({variables: [selectVar], variableValues: {env: 'production'}, onVariableChange})
    await user.click(screen.getByRole('button', {name: 'Variable Environment'}))
    await user.click(await screen.findByText('staging'))
    expect(onVariableChange).toHaveBeenCalledWith('env', 'staging')
  })

  it('renders "all" for the $__all selection', () => {
    const allVar: DashboardVariable = {...selectVar, include_all: true, options: ['$__all', 'production']}
    renderToolbar({variables: [allVar], variableValues: {env: '$__all'}})
    expect(screen.getByText('all')).toBeInTheDocument()
  })
})

describe('DashboardToolbar – multi-select variables', () => {
  const multiVar: DashboardVariable = {
    name: 'pod', label: 'Pod', type: 'custom', multi: true, options: ['a', 'b', 'c'],
  }

  it('summarises the selection as "N of M"', () => {
    renderToolbar({variables: [multiVar], variableValues: {pod: 'a,b'}})
    expect(screen.getByText('2 of 3')).toBeInTheDocument()
  })

  it('applies a comma-joined selection', async () => {
    const user = userEvent.setup()
    const onVariableChange = vi.fn()
    renderToolbar({variables: [multiVar], variableValues: {pod: ''}, onVariableChange})
    await user.click(screen.getByRole('button', {name: 'Variable Pod'}))
    const checkboxes = await screen.findAllByRole('checkbox')
    await user.click(checkboxes[0])
    await user.click(checkboxes[1])
    await user.click(screen.getByRole('button', {name: 'Apply'}))
    expect(onVariableChange).toHaveBeenCalledWith('pod', 'a,b')
  })
})
