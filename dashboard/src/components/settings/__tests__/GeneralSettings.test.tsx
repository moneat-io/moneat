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

import {GeneralSettings} from '../GeneralSettings'

const apiMock = vi.hoisted(() => ({
  getOrganizationAccountSettings: vi.fn(),
  updateOrganizationSettings: vi.fn(),
}))
const toastMock = vi.hoisted(() => vi.fn())

vi.mock('@/lib/api', () => ({api: apiMock}))
vi.mock('@/hooks/useAuth', () => ({useAuth: () => ({user: {orgId: 'org-resource-id'}})}))
vi.mock('@/hooks/useTimezone', () => ({useTimezone: () => ({timezone: 'UTC'})}))
vi.mock('@/hooks/useToast', () => ({useToast: () => ({toast: toastMock})}))

function renderWithClient(ui: React.ReactElement) {
  const queryClient = new QueryClient({
    defaultOptions: {queries: {retry: false}, mutations: {retry: false}},
  })
  return render(<QueryClientProvider client={queryClient}>{ui}</QueryClientProvider>)
}

describe('GeneralSettings', () => {
  beforeEach(() => {
    vi.clearAllMocks()
    apiMock.getOrganizationAccountSettings.mockResolvedValue({
      id: 'org-resource-id',
      name: 'Acme Corp',
      role: 'owner',
      slug: 'acme-corp',
      defaultTimezone: 'UTC',
      dataRegion: 'us',
      createdAt: '2026-06-15T12:00:00Z',
    })
    apiMock.updateOrganizationSettings.mockResolvedValue({
      id: 'org-resource-id',
      name: 'Acme Labs',
      role: 'owner',
      slug: 'acme-corp',
      defaultTimezone: 'UTC',
      dataRegion: 'us',
      createdAt: '2026-06-15T12:00:00Z',
    })
  })

  it('renders organization details and saves edits', async () => {
    const user = userEvent.setup()
    renderWithClient(<GeneralSettings />)

    const nameInput = await screen.findByDisplayValue('Acme Corp')
    expect(screen.getByText('us')).toBeInTheDocument()
    expect(screen.getByText('org-resource-id')).toBeInTheDocument()
    expect(screen.getByRole('button', {name: 'Save changes'})).toBeDisabled()

    await user.clear(nameInput)
    await user.type(nameInput, 'Acme Labs')
    await user.click(screen.getByRole('button', {name: 'Save changes'}))

    await waitFor(() =>
      expect(apiMock.updateOrganizationSettings).toHaveBeenCalledWith(
        'org-resource-id',
        {name: 'Acme Labs', defaultTimezone: 'UTC'}
      )
    )
    expect(toastMock).toHaveBeenCalledWith({
      title: 'Workspace updated',
      description: 'Your changes have been saved.',
    })
  })
})
