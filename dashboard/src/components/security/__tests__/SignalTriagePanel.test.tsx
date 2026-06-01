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

import {fireEvent, render, screen, waitFor} from '@testing-library/react'
import {describe, expect, it, vi} from 'vitest'
import {SignalTriagePanel} from '../SignalTriagePanel'
import {makeSignal} from './fixtures'

describe('SignalTriagePanel', () => {
  it('sends a review transition without a reason', async () => {
    const onTriage = vi.fn().mockResolvedValue(makeSignal({status: 'under_review'}))
    render(<SignalTriagePanel signal={makeSignal({status: 'open'})} onTriage={onTriage} />)
    fireEvent.click(screen.getByRole('button', {name: 'Start review'}))
    await waitFor(() => expect(onTriage).toHaveBeenCalledWith({status: 'under_review'}))
  })

  it('requires an archive reason before archiving', async () => {
    const onTriage = vi.fn().mockResolvedValue(makeSignal())
    render(<SignalTriagePanel signal={makeSignal({status: 'open'})} onTriage={onTriage} />)

    // First click reveals the reason picker but does not submit.
    fireEvent.click(screen.getByRole('button', {name: 'Archive'}))
    expect(onTriage).not.toHaveBeenCalled()
    expect(screen.getByLabelText('Archive reason')).toBeInTheDocument()

    // Confirming without a reason surfaces an error.
    fireEvent.click(screen.getByRole('button', {name: 'Confirm archive'}))
    expect(onTriage).not.toHaveBeenCalled()
    expect(screen.getByText(/select an archive reason/i)).toBeInTheDocument()

    // Choosing a reason then confirming sends it.
    fireEvent.change(screen.getByLabelText('Archive reason'), {target: {value: 'false_positive'}})
    fireEvent.click(screen.getByRole('button', {name: 'Confirm archive'}))
    await waitFor(() =>
      expect(onTriage).toHaveBeenCalledWith({status: 'archived', reason: 'false_positive'})
    )
  })

  it('offers reopen from archived with no reason', async () => {
    const onTriage = vi.fn().mockResolvedValue(makeSignal({status: 'open'}))
    render(<SignalTriagePanel signal={makeSignal({status: 'archived'})} onTriage={onTriage} />)
    expect(screen.queryByRole('button', {name: 'Archive'})).not.toBeInTheDocument()
    fireEvent.click(screen.getByRole('button', {name: 'Reopen'}))
    await waitFor(() => expect(onTriage).toHaveBeenCalledWith({status: 'open'}))
  })

  it('adds a standalone note', async () => {
    const onTriage = vi.fn().mockResolvedValue(makeSignal())
    render(<SignalTriagePanel signal={makeSignal({status: 'open'})} onTriage={onTriage} />)
    fireEvent.change(screen.getByPlaceholderText(/investigation note/i), {target: {value: 'looking into it'}})
    fireEvent.click(screen.getByRole('button', {name: 'Add note'}))
    await waitFor(() => expect(onTriage).toHaveBeenCalledWith({note: 'looking into it'}))
  })
})
