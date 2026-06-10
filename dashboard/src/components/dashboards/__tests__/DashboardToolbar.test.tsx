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

// Radix popovers/menus rely on APIs jsdom does not implement.
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

describe('DashboardToolbar', () => {
  it('renders the title and a back link to dashboards', () => {
    renderToolbar()
    expect(screen.getByText('Test Dashboard')).toBeInTheDocument()
    expect(document.querySelector('a[href="/dashboards"]')).toBeInTheDocument()
  })

  it('shows the "edited …" recency label when an updatedAt is supplied', () => {
    renderToolbar({updatedAt: new Date(Date.now() - 2 * 3600 * 1000).toISOString()})
    expect(screen.getByText(/edited .*ago/i)).toBeInTheDocument()
  })

  it('marks the dashboard as Home when it is the default', () => {
    renderToolbar({isDefault: true})
    expect(screen.getByText('Home')).toBeInTheDocument()
  })

  describe('favorite', () => {
    it('toggles favorite when the star is clicked', async () => {
      const user = userEvent.setup()
      const onToggleFavorite = vi.fn()
      renderToolbar({onToggleFavorite})
      await user.click(screen.getByRole('button', {name: 'Add to favorites'}))
      expect(onToggleFavorite).toHaveBeenCalled()
    })

    it('reflects the favorited state with a filled (amber) star', () => {
      renderToolbar({isFavorited: true})
      const button = screen.getByRole('button', {name: 'Remove from favorites'})
      expect(button.className).toContain('text-amber-500')
    })
  })

  describe('view vs edit actions', () => {
    it('shows Edit / Share / More actions when not editing', () => {
      renderToolbar()
      expect(screen.getByRole('button', {name: 'Edit dashboard'})).toBeInTheDocument()
      expect(screen.getByRole('button', {name: 'Share dashboard'})).toBeInTheDocument()
      expect(screen.getByRole('button', {name: 'More actions'})).toBeInTheDocument()
      expect(screen.queryByText('Done')).not.toBeInTheDocument()
      expect(screen.queryByRole('button', {name: 'Add widget'})).not.toBeInTheDocument()
    })

    it('shows Add widget / Done when editing', () => {
      renderToolbar({isEditing: true})
      expect(screen.getByRole('button', {name: 'Add widget'})).toBeInTheDocument()
      expect(screen.getByText('Done')).toBeInTheDocument()
      expect(screen.queryByRole('button', {name: 'Edit dashboard'})).not.toBeInTheDocument()
      expect(screen.queryByRole('button', {name: 'More actions'})).not.toBeInTheDocument()
    })

    it('calls onToggleEdit / onSave / onAddWidget from their buttons', async () => {
      const user = userEvent.setup()
      const onToggleEdit = vi.fn()
      const {rerender} = renderToolbar({onToggleEdit})
      await user.click(screen.getByRole('button', {name: 'Edit dashboard'}))
      expect(onToggleEdit).toHaveBeenCalled()

      const onSave = vi.fn()
      const onAddWidget = vi.fn()
      rerender(<DashboardToolbar {...defaultProps} isEditing onSave={onSave} onAddWidget={onAddWidget} />)
      await user.click(screen.getByRole('button', {name: 'Add widget'}))
      expect(onAddWidget).toHaveBeenCalled()
      await user.click(screen.getByRole('button', {name: 'Done'}))
      expect(onSave).toHaveBeenCalled()
    })
  })

  describe('time range', () => {
    it('shows the resolved label and window on the trigger', () => {
      renderToolbar()
      expect(screen.getByText('Past 24 hours')).toBeInTheDocument()
      expect(screen.getByText('now-24h → now')).toBeInTheDocument()
    })

    it('opens the preset list and reports the chosen preset', async () => {
      const user = userEvent.setup()
      const onTimeRangeChange = vi.fn()
      renderToolbar({onTimeRangeChange})
      await user.click(screen.getByRole('button', {name: 'Time range'}))
      expect(await screen.findByText('Last 7 days')).toBeInTheDocument()
      await user.click(screen.getByText('Last 1 hour'))
      expect(onTimeRangeChange).toHaveBeenCalledWith({from: 'now-1h', to: 'now'})
    })

    it('applies a custom range from the from/to fields', async () => {
      const user = userEvent.setup()
      const onTimeRangeChange = vi.fn()
      renderToolbar({onTimeRangeChange})
      await user.click(screen.getByRole('button', {name: 'Time range'}))
      const from = await screen.findByLabelText('From')
      await user.clear(from)
      await user.type(from, 'now-2h')
      await user.click(screen.getByRole('button', {name: 'Apply'}))
      expect(onTimeRangeChange).toHaveBeenCalledWith({from: 'now-2h', to: 'now'})
    })
  })

  describe('refresh', () => {
    it('triggers an immediate refresh', async () => {
      const user = userEvent.setup()
      const onRefreshNow = vi.fn()
      renderToolbar({onRefreshNow})
      await user.click(screen.getByRole('button', {name: 'Refresh now'}))
      expect(onRefreshNow).toHaveBeenCalled()
    })

    it('selects an auto-refresh interval', async () => {
      const user = userEvent.setup()
      const onRefreshMsChange = vi.fn()
      renderToolbar({onRefreshMsChange})
      await user.click(screen.getByRole('button', {name: 'Auto-refresh interval'}))
      await user.click(await screen.findByText('30s'))
      expect(onRefreshMsChange).toHaveBeenCalledWith(30_000)
    })

    it('highlights the active interval', () => {
      renderToolbar({refreshMs: 30_000})
      expect(screen.getByRole('button', {name: 'Auto-refresh interval'}).className).toContain('text-primary')
    })
  })

  describe('more-actions menu', () => {
    it.each([
      ['Duplicate', 'onDuplicate'],
      ['Export JSON', 'onExport'],
      ['Set as home', 'onSetDefault'],
      ['Manage variables', 'onVariableSettings'],
      ['Delete dashboard', 'onDelete'],
    ])('runs %s', async (label, handler) => {
      const user = userEvent.setup()
      const fn = vi.fn()
      renderToolbar({[handler]: fn})
      await user.click(screen.getByRole('button', {name: 'More actions'}))
      await user.click(await screen.findByText(label))
      expect(fn).toHaveBeenCalled()
    })
  })

  describe('share', () => {
    it('copies a deep link carrying the current time range', async () => {
      const user = userEvent.setup()
      // userEvent installs its own clipboard stub on setup; spy on that.
      const writeText = vi.spyOn(navigator.clipboard, 'writeText')
      renderToolbar()
      await user.click(screen.getByRole('button', {name: 'Share dashboard'}))
      expect(writeText).toHaveBeenCalledWith(expect.stringContaining('from=now-24h'))
    })
  })
})
