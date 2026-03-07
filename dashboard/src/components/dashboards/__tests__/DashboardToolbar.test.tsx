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
import {describe, it, expect, vi} from 'vitest'
import {render, screen} from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import {DashboardToolbar} from '../DashboardToolbar'

// Mock TanStack Router's Link
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
  timeRange: {from: 'now-24h', to: 'now'},
  onTimeRangeChange: vi.fn(),
  autoRefresh: false,
  onAutoRefreshChange: vi.fn(),
  variableValues: {},
  onVariableChange: vi.fn(),
  onVariableSettings: vi.fn(),
}

describe('DashboardToolbar', () => {
  it('renders the dashboard title', () => {
    render(<DashboardToolbar {...defaultProps} />)
    expect(screen.getByText('Test Dashboard')).toBeInTheDocument()
  })

  it('renders back link to dashboards', () => {
    render(<DashboardToolbar {...defaultProps} />)
    const backLink = document.querySelector('a[href="/dashboards"]')
    expect(backLink).toBeInTheDocument()
  })

  it('shows icon action buttons when not editing', () => {
    render(<DashboardToolbar {...defaultProps} />)
    expect(screen.queryByText('Done')).not.toBeInTheDocument()
    expect(screen.queryByText('Widget')).not.toBeInTheDocument()
    // Time range controls still present
    expect(screen.getByText('1h')).toBeInTheDocument()
  })

  it('shows Widget and Done buttons when editing', () => {
    render(<DashboardToolbar {...defaultProps} isEditing={true} />)
    expect(screen.getByText('Widget')).toBeInTheDocument()
    expect(screen.getByText('Done')).toBeInTheDocument()
    expect(screen.queryByText('Edit')).not.toBeInTheDocument()
    expect(screen.queryByText('Export')).not.toBeInTheDocument()
  })

  it('calls onToggleEdit when edit button clicked', async () => {
    const user = userEvent.setup()
    const onToggleEdit = vi.fn()
    render(<DashboardToolbar {...defaultProps} onToggleEdit={onToggleEdit} />)
    // Edit is the last button (icon-only pencil): [presets×6, refresh, export, edit]
    const buttons = screen.getAllByRole('button')
    await user.click(buttons[buttons.length - 1])
    expect(onToggleEdit).toHaveBeenCalled()
  })

  it('calls onSave when Done button clicked', async () => {
    const user = userEvent.setup()
    const onSave = vi.fn()
    render(<DashboardToolbar {...defaultProps} isEditing={true} onSave={onSave} />)
    await user.click(screen.getByText('Done'))
    expect(onSave).toHaveBeenCalled()
  })

  it('calls onAddWidget when Widget button clicked', async () => {
    const user = userEvent.setup()
    const onAddWidget = vi.fn()
    render(<DashboardToolbar {...defaultProps} isEditing={true} onAddWidget={onAddWidget} />)
    await user.click(screen.getByText('Widget'))
    expect(onAddWidget).toHaveBeenCalled()
  })

  it('calls onExport when export button clicked', async () => {
    const user = userEvent.setup()
    const onExport = vi.fn()
    render(<DashboardToolbar {...defaultProps} onExport={onExport} />)
    // Export is second-to-last button (icon-only download): [presets×6, refresh, export, edit]
    const buttons = screen.getAllByRole('button')
    await user.click(buttons[buttons.length - 2])
    expect(onExport).toHaveBeenCalled()
  })

  it('renders time range preset buttons', () => {
    render(<DashboardToolbar {...defaultProps} />)
    expect(screen.getByText('15m')).toBeInTheDocument()
    expect(screen.getByText('1h')).toBeInTheDocument()
    expect(screen.getByText('4h')).toBeInTheDocument()
    expect(screen.getByText('24h')).toBeInTheDocument()
    expect(screen.getByText('7d')).toBeInTheDocument()
    expect(screen.getByText('30d')).toBeInTheDocument()
  })

  it('calls onTimeRangeChange when time preset clicked', async () => {
    const user = userEvent.setup()
    const onTimeRangeChange = vi.fn()
    render(<DashboardToolbar {...defaultProps} onTimeRangeChange={onTimeRangeChange} />)
    await user.click(screen.getByText('1h'))
    expect(onTimeRangeChange).toHaveBeenCalledWith({from: 'now-1h', to: 'now'})
  })

  it('highlights active time range', () => {
    render(<DashboardToolbar {...defaultProps} timeRange={{from: 'now-1h', to: 'now'}} />)
    const btn1h = screen.getByText('1h')
    expect(btn1h.className).toContain('bg-background')
  })

  it('calls onAutoRefreshChange when refresh button clicked', async () => {
    const user = userEvent.setup()
    const onAutoRefreshChange = vi.fn()
    render(<DashboardToolbar {...defaultProps} onAutoRefreshChange={onAutoRefreshChange} />)
    // The refresh button doesn't have text, find it by role
    const buttons = screen.getAllByRole('button')
    // Auto-refresh button is one of them
    const refreshBtn = buttons.find(btn => !btn.textContent)
    if (refreshBtn) {
      await user.click(refreshBtn)
      expect(onAutoRefreshChange).toHaveBeenCalledWith(true)
    }
  })

  it('allows title editing when in edit mode', async () => {
    const user = userEvent.setup()
    const onTitleChange = vi.fn()
    render(
      <DashboardToolbar {...defaultProps} isEditing={true} onTitleChange={onTitleChange} />
    )
    // Click title to enter edit mode
    await user.click(screen.getByText('Test Dashboard'))
    const input = screen.getByDisplayValue('Test Dashboard')
    expect(input).toBeInTheDocument()

    // Change title and blur
    await user.clear(input)
    await user.type(input, 'New Title')
    await user.tab()
    expect(onTitleChange).toHaveBeenCalledWith('New Title')
  })

  it('shows Variables button when editing', () => {
    render(<DashboardToolbar {...defaultProps} isEditing={true} />)
    expect(screen.getByText('Variables')).toBeInTheDocument()
  })

  it('calls onVariableSettings when Variables button clicked', async () => {
    const user = userEvent.setup()
    const onVariableSettings = vi.fn()
    render(<DashboardToolbar {...defaultProps} isEditing={true} onVariableSettings={onVariableSettings} />)
    await user.click(screen.getByText('Variables'))
    expect(onVariableSettings).toHaveBeenCalled()
  })

  it('does not show Variables button when not editing', () => {
    render(<DashboardToolbar {...defaultProps} isEditing={false} />)
    expect(screen.queryByText('Variables')).not.toBeInTheDocument()
  })
})
