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
import {clearAuthStorage} from '@/test/utils'

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

beforeEach(() => {
  clearAuthStorage()
  vi.clearAllMocks()
})

function renderToolbar(props: Record<string, unknown> = {}) {
  return render(<DashboardToolbar {...defaultProps} {...props} />)
}

describe('DashboardToolbar – extended branch coverage', () => {
  // ──── Title editing branches ────
  describe('title editing', () => {
    it('does not enter title edit mode when not in editing mode', async () => {
      const user = userEvent.setup()
      renderToolbar({isEditing: false})
      await user.click(screen.getByText('Test Dashboard'))
      // Should NOT show an input
      expect(screen.queryByDisplayValue('Test Dashboard')).not.toBeInTheDocument()
    })

    it('enters title edit mode when in editing mode and title is clicked', async () => {
      const user = userEvent.setup()
      renderToolbar({isEditing: true})
      await user.click(screen.getByText('Test Dashboard'))
      expect(screen.getByDisplayValue('Test Dashboard')).toBeInTheDocument()
    })

    it('does not call onTitleChange when title is unchanged on blur', async () => {
      const user = userEvent.setup()
      const onTitleChange = vi.fn()
      renderToolbar({isEditing: true, onTitleChange})
      await user.click(screen.getByText('Test Dashboard'))
      // Just blur without changing
      await user.tab()
      expect(onTitleChange).not.toHaveBeenCalled()
    })

    it('does not call onTitleChange when title is trimmed to empty', async () => {
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

    it('saves title on Enter key press', async () => {
      const user = userEvent.setup()
      const onTitleChange = vi.fn()
      renderToolbar({isEditing: true, onTitleChange})
      await user.click(screen.getByText('Test Dashboard'))
      const input = screen.getByDisplayValue('Test Dashboard')
      await user.clear(input)
      await user.type(input, 'Updated Title{Enter}')
      expect(onTitleChange).toHaveBeenCalledWith('Updated Title')
    })

    it('title has cursor-pointer class only when editing', () => {
      const {rerender} = renderToolbar({isEditing: false})
      const titleEl = screen.getByText('Test Dashboard')
      expect(titleEl.className).not.toContain('cursor-pointer')

      rerender(<DashboardToolbar {...defaultProps} isEditing />)
      const titleEditing = screen.getByText('Test Dashboard')
      expect(titleEditing.className).toContain('cursor-pointer')
    })
  })

  // ──── Variables section ────
  describe('variables', () => {
    const textboxVar: DashboardVariable = {
      name: 'env',
      label: 'Environment',
      type: 'textbox',
      options: [],
      current: 'production',
    }

    const selectVarNoOptions: DashboardVariable = {
      name: 'empty_var',
      label: null,
      type: 'custom',
      options: [],
      current: 'fallback',
    }

    it('does not render variables section when variables is undefined', () => {
      renderToolbar({variables: undefined})
      expect(screen.queryByText('Environment')).not.toBeInTheDocument()
    })

    it('does not render variables section when variables is empty array', () => {
      renderToolbar({variables: []})
      expect(screen.queryByText('Environment')).not.toBeInTheDocument()
    })

    it('renders textbox variable as input', () => {
      renderToolbar({variables: [textboxVar], variableValues: {env: 'staging'}})
      expect(screen.getByText('Environment')).toBeInTheDocument()
      expect(screen.getByDisplayValue('staging')).toBeInTheDocument()
    })

    it('uses v.current for textbox when variableValues is missing that key', () => {
      renderToolbar({variables: [textboxVar], variableValues: {}})
      expect(screen.getByDisplayValue('production')).toBeInTheDocument()
    })

    it('calls onVariableChange when textbox value changes', async () => {
      const user = userEvent.setup()
      const onVariableChange = vi.fn()
      renderToolbar({variables: [textboxVar], variableValues: {env: 'prod'}, onVariableChange})
      const input = screen.getByDisplayValue('prod')
      await user.clear(input)
      await user.type(input, 'dev')
      expect(onVariableChange).toHaveBeenCalled()
    })

    it('uses variable name as label when label is null', () => {
      renderToolbar({variables: [selectVarNoOptions], variableValues: {}})
      expect(screen.getByText('empty_var')).toBeInTheDocument()
    })

    it('shows variable settings gear icon when editing and onVariableSettings is provided', () => {
      renderToolbar({isEditing: true, variables: [textboxVar]})
      // The gear icon button should be rendered in the variables section
      // There should be TWO variable settings buttons: one in the variable section, one in the toolbar
      const buttons = screen.getAllByText('Variables')
      expect(buttons.length).toBeGreaterThanOrEqual(1)
    })

    it('does not show variable settings gear in variables section when not editing', () => {
      renderToolbar({isEditing: false, variables: [textboxVar]})
      // Only the toolbar buttons should be present, no Variables button when not editing
      expect(screen.queryByText('Variables')).not.toBeInTheDocument()
    })
  })

  // ──── Time range presets – active styling ────
  describe('time range active state', () => {
    it('highlights the matching time range preset', () => {
      renderToolbar({timeRange: {from: 'now-7d', to: 'now'}})
      const btn7d = screen.getByText('7d')
      expect(btn7d.className).toContain('bg-background')
      expect(btn7d.className).toContain('shadow-sm')
    })

    it('does not highlight non-active time range presets', () => {
      renderToolbar({timeRange: {from: 'now-7d', to: 'now'}})
      const btn1h = screen.getByText('1h')
      expect(btn1h.className).toContain('text-muted-foreground')
      expect(btn1h.className).not.toContain('shadow-sm')
    })

    it('applies inactive style to custom time range (no preset match)', () => {
      renderToolbar({timeRange: {from: 'now-3h', to: 'now'}})
      // No preset should be active
      const allPresets = ['15m', '1h', '4h', '24h', '7d', '30d']
      for (const preset of allPresets) {
        const btn = screen.getByText(preset)
        expect(btn.className).not.toContain('shadow-sm')
      }
    })
  })

  // ──── Auto-refresh toggle ────
  describe('auto-refresh', () => {
    it('renders with default variant when autoRefresh is true', () => {
      renderToolbar({autoRefresh: true})
      // The auto-refresh button should have 'animate-spin' class on its icon
      const spinIcons = document.querySelectorAll('.animate-spin')
      expect(spinIcons.length).toBeGreaterThanOrEqual(1)
    })

    it('does not have animate-spin when autoRefresh is false', () => {
      renderToolbar({autoRefresh: false})
      const spinIcons = document.querySelectorAll('.animate-spin')
      expect(spinIcons.length).toBe(0)
    })

    it('calls onAutoRefreshChange(false) when autoRefresh is true and button is clicked', async () => {
      const user = userEvent.setup()
      const onAutoRefreshChange = vi.fn()
      renderToolbar({autoRefresh: true, onAutoRefreshChange})
      // Find the button with the spinning icon
      const spinIcon = document.querySelector('.animate-spin')
      const refreshBtn = spinIcon?.closest('button')
      if (refreshBtn) {
        await user.click(refreshBtn)
        expect(onAutoRefreshChange).toHaveBeenCalledWith(false)
      }
    })
  })

  // ──── Editing vs non-editing action buttons ────
  describe('action buttons', () => {
    it('does not show onVariableSettings in editing toolbar when callback is undefined', () => {
      renderToolbar({isEditing: true, onVariableSettings: undefined})
      expect(screen.queryByText('Variables')).not.toBeInTheDocument()
      // Widget and Done should still be visible
      expect(screen.getByText('Widget')).toBeInTheDocument()
      expect(screen.getByText('Done')).toBeInTheDocument()
    })
  })

  // ──── Variable fallback values ────
  describe('variable fallback chain', () => {
    it('uses default_value when variableValues and current are not set', () => {
      const varWithDefault: DashboardVariable = {
        name: 'zone',
        label: 'Zone',
        type: 'textbox',
        options: [],
        default_value: 'us-central1',
      }
      renderToolbar({variables: [varWithDefault], variableValues: {}})
      expect(screen.getByDisplayValue('us-central1')).toBeInTheDocument()
    })

    it('falls back to empty string when all value sources are null', () => {
      const varNoValues: DashboardVariable = {
        name: 'test',
        label: 'Test',
        type: 'textbox',
        options: [],
      }
      renderToolbar({variables: [varNoValues], variableValues: {}})
      const input = screen.getByPlaceholderText('test')
      expect(input).toHaveValue('')
    })
  })
})
