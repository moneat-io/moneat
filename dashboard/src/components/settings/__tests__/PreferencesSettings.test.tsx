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

import {QueryClient, QueryClientProvider} from '@tanstack/react-query'
import {render, screen, waitFor} from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import {beforeEach, describe, expect, it, vi} from 'vitest'

import {PreferencesSettings} from '../PreferencesSettings'
import {asDateFormat, hasSidebarChanges, sortedSidebarKeys} from '../preferenceSettingsModel'

const apiMock = vi.hoisted(() => ({
  isAuthenticated: vi.fn(() => true),
  getCurrentUser: vi.fn(),
  updateSidebarPreferences: vi.fn(),
  updateUserPreferences: vi.fn(),
  updateUserTimezone: vi.fn(),
}))

vi.mock('@/lib/api', () => ({api: apiMock}))
vi.mock('@/components/ThemePicker', () => ({ThemePicker: () => <div data-testid="theme-picker" />}))
vi.mock('@/hooks/useToast', () => ({useToast: () => ({toast: vi.fn()})}))

function renderWithClient(ui: React.ReactElement) {
  const queryClient = new QueryClient({
    defaultOptions: {queries: {retry: false}, mutations: {retry: false}},
  })
  return render(<QueryClientProvider client={queryClient}>{ui}</QueryClientProvider>)
}

describe('PreferencesSettings', () => {
  beforeEach(() => {
    vi.clearAllMocks()
    globalThis.localStorage.clear()
    globalThis.localStorage.setItem('dateFormat', 'not-a-real-format')
    apiMock.getCurrentUser.mockResolvedValue({
      id: 'user-1',
      email: 'ada@example.com',
      emailVerified: true,
      onboardingCompleted: true,
      timezone: 'America/New_York',
      dateFormat: 'iso',
      sidebarHiddenItems: ['dashboards'],
    })
    apiMock.updateSidebarPreferences.mockResolvedValue({hiddenItems: []})
    apiMock.updateUserPreferences.mockResolvedValue(undefined)
    apiMock.updateUserTimezone.mockResolvedValue({timezone: 'UTC'})
  })

  it('renders server-backed preferences and saves sidebar visibility changes', async () => {
    const user = userEvent.setup()
    renderWithClient(<PreferencesSettings />)

    expect(await screen.findByTestId('theme-picker')).toBeInTheDocument()
    expect(screen.getByText('Date format')).toBeInTheDocument()
    expect(screen.getByText(/Preview:/)).toBeInTheDocument()

    await user.click(screen.getAllByRole('switch')[0])
    await user.click(screen.getByRole('button', {name: /Hide all/}))
    await user.click(await screen.findByRole('button', {name: /Save changes/}))

    await waitFor(() => expect(apiMock.updateSidebarPreferences).toHaveBeenCalled())
    const savedItems = apiMock.updateSidebarPreferences.mock.calls.at(-1)?.[0] as string[]
    expect(savedItems).toEqual(expect.arrayContaining(['dashboards']))
    expect(savedItems.length).toBeGreaterThan(5)
  })

  it('normalizes date format and sidebar preference model values', () => {
    expect(asDateFormat('medium')).toBe('medium')
    expect(asDateFormat('iso')).toBe('iso')
    expect(asDateFormat('dmy')).toBe('dmy')
    expect(asDateFormat('relative')).toBeUndefined()
    expect(asDateFormat(null)).toBeUndefined()
    expect(sortedSidebarKeys(['traces', 'dashboards', 'alerts'])).toEqual(['alerts', 'dashboards', 'traces'])
    expect(hasSidebarChanges(['alerts', 'dashboards'], ['dashboards', 'alerts'])).toBe(false)
    expect(hasSidebarChanges(['alerts'], ['dashboards', 'alerts'])).toBe(true)
  })
})
