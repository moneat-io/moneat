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

import {render, screen, waitFor} from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import type {ComponentProps} from 'react'
import {describe, expect, it, vi} from 'vitest'
import {TeamEditor, type TeamFormData, type TeamEditorOption} from '../TeamEditor'

const members: TeamEditorOption[] = [
  {id: 'user-1', name: 'Dana Whitfield'},
  {id: 'user-2', name: 'Theo Park'},
]

const schedules: TeamEditorOption[] = [
  {id: 'schedule-1', name: 'Payments Primary'},
]

const policies: TeamEditorOption[] = [
  {id: 'policy-1', name: 'Sev1 Policy'},
]

function renderEditor(overrides: Partial<ComponentProps<typeof TeamEditor>> = {}) {
  const props = {
    members,
    schedules,
    policies,
    onSave: vi.fn(),
    onCancel: vi.fn(),
    ...overrides,
  }
  render(<TeamEditor {...props} />)
  return props
}

describe('TeamEditor', () => {
  it('creates a team with trimmed metadata', async () => {
    const user = userEvent.setup()
    const {onSave, onCancel} = renderEditor()

    expect(screen.getByText('No members yet.')).toBeInTheDocument()
    expect(screen.getByRole('button', {name: 'Create team'})).toBeDisabled()

    await user.type(screen.getByLabelText('Team name'), '  Payments  ')
    await user.type(screen.getByLabelText('Description'), ' Owns checkout ')
    await user.type(screen.getByLabelText('Slack channel'), ' #payments-oncall ')
    await user.type(screen.getByLabelText('Repository'), ' moneat-io/payments ')
    await user.click(screen.getByRole('button', {name: 'Create team'}))

    await waitFor(() => expect(onSave).toHaveBeenCalledTimes(1))
    expect(onSave).toHaveBeenCalledWith({
      name: 'Payments',
      description: 'Owns checkout',
      slack: '#payments-oncall',
      repo: 'moneat-io/payments',
      memberIds: [],
      onCallScheduleId: null,
      escalationPolicyId: null,
    })

    await user.click(screen.getByRole('button', {name: 'Cancel'}))
    expect(onCancel).toHaveBeenCalledTimes(1)
  })

  it('edits a team and removes selected members', async () => {
    const user = userEvent.setup()
    const initialData: TeamFormData = {
      name: 'Payments',
      description: 'Owns checkout',
      slack: '#payments-oncall',
      repo: 'moneat-io/payments',
      memberIds: ['user-1'],
      onCallScheduleId: 'schedule-1',
      escalationPolicyId: 'policy-1',
    }
    const {onSave} = renderEditor({initialData})

    expect(screen.getByText('Dana Whitfield')).toBeInTheDocument()
    await user.click(screen.getByRole('button', {name: 'Remove Dana Whitfield'}))
    await user.clear(screen.getByLabelText('Team name'))
    await user.type(screen.getByLabelText('Team name'), 'Platform')
    await user.click(screen.getByRole('button', {name: 'Save changes'}))

    await waitFor(() => expect(onSave).toHaveBeenCalledTimes(1))
    expect(onSave).toHaveBeenCalledWith({
      ...initialData,
      name: 'Platform',
      memberIds: [],
    })
  })

  it('disables actions while saving', () => {
    renderEditor({isSaving: true})

    expect(screen.getByRole('button', {name: /Saving/})).toBeDisabled()
    expect(screen.getByRole('button', {name: 'Cancel'})).toBeDisabled()
  })
})
