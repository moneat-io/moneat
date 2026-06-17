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
import {RuleForm} from '../RuleForm'
import {makeRule} from './fixtures'

function noopPreview() {
  return vi.fn().mockResolvedValue({match_count: 0, samples: [], window_seconds: 300})
}

describe('RuleForm', () => {
  it('shows a validation error and does not submit an unnamed rule', () => {
    const onSubmit = vi.fn().mockResolvedValue(makeRule())
    render(<RuleForm onSubmit={onSubmit} onPreview={noopPreview()} onCancel={vi.fn()} />)
    fireEvent.click(screen.getByRole('button', {name: 'Create rule'}))
    expect(screen.getByText('Name is required')).toBeInTheDocument()
    expect(onSubmit).not.toHaveBeenCalled()
  })

  it('submits a built create request', async () => {
    const onSubmit = vi.fn().mockResolvedValue(makeRule())
    render(<RuleForm onSubmit={onSubmit} onPreview={noopPreview()} onCancel={vi.fn()} />)
    fireEvent.change(screen.getByLabelText('Name'), {target: {value: 'Failed logins'}})
    fireEvent.change(screen.getByPlaceholderText(/status:/), {target: {value: 'status:failed'}})
    fireEvent.click(screen.getByRole('button', {name: 'Create rule'}))
    await waitFor(() =>
      expect(onSubmit).toHaveBeenCalledWith(
        expect.objectContaining({name: 'Failed logins', filter: 'status:failed', type: 'threshold'}),
        undefined
      )
    )
  })

  it('hides the threshold field for new_value rules', () => {
    render(
      <RuleForm
        rule={makeRule({type: 'new_value'})}
        onSubmit={vi.fn()}
        onPreview={noopPreview()}
        onCancel={vi.fn()}
      />
    )
    expect(screen.queryByLabelText('Threshold count')).not.toBeInTheDocument()
    // Switching back to threshold reveals it.
    fireEvent.change(screen.getByLabelText('Rule type'), {target: {value: 'threshold'}})
    expect(screen.getByLabelText('Threshold count')).toBeInTheDocument()
  })

  it('uses minimum count for rate anomaly rules', () => {
    render(
      <RuleForm
        rule={makeRule({type: 'rate_anomaly', threshold_count: 20})}
        onSubmit={vi.fn()}
        onPreview={noopPreview()}
        onCancel={vi.fn()}
      />
    )
    expect(screen.getByLabelText('Minimum count')).toBeInTheDocument()
    expect(screen.queryByLabelText('Threshold count')).not.toBeInTheDocument()
  })

  it('saves then previews and renders the would-be count', async () => {
    const saved = makeRule({id: 'rule-99'})
    const onSubmit = vi.fn().mockResolvedValue(saved)
    const onPreview = vi.fn().mockResolvedValue({
      match_count: 4,
      window_seconds: 300,
      samples: [{group_values: {'user.id': 'alice'}, count: 9}],
    })
    render(<RuleForm rule={makeRule()} onSubmit={onSubmit} onPreview={onPreview} onCancel={vi.fn()} />)
    fireEvent.click(screen.getByRole('button', {name: 'Preview'}))
    await waitFor(() => expect(onPreview).toHaveBeenCalledWith('rule-99'))
    expect(onSubmit).toHaveBeenCalledTimes(1)
    expect(await screen.findByText(/Would produce 4 signals over the last 5m/)).toBeInTheDocument()
  })

  it('blocks preview on an invalid form and does not call onPreview', () => {
    const onPreview = noopPreview()
    render(<RuleForm onSubmit={vi.fn().mockResolvedValue(makeRule())} onPreview={onPreview} onCancel={vi.fn()} />)
    fireEvent.click(screen.getByRole('button', {name: 'Save draft & preview'}))
    expect(screen.getByText('Name is required')).toBeInTheDocument()
    expect(onPreview).not.toHaveBeenCalled()
  })

  it('saves an unsaved rule as a disabled draft when previewing', async () => {
    const onSubmit = vi.fn().mockResolvedValue(makeRule({id: 'rule-42'}))
    const onPreview = noopPreview()
    render(<RuleForm onSubmit={onSubmit} onPreview={onPreview} onCancel={vi.fn()} />)
    fireEvent.change(screen.getByLabelText('Name'), {target: {value: 'Failed logins'}})
    fireEvent.change(screen.getByPlaceholderText(/status:/), {target: {value: 'status:failed'}})
    // Enabled toggle is on, but the preview-save must persist a disabled draft regardless.
    fireEvent.click(screen.getByRole('switch', {name: 'Enabled'}))
    fireEvent.click(screen.getByRole('button', {name: 'Save draft & preview'}))
    await waitFor(() =>
      expect(onSubmit).toHaveBeenCalledWith(
        expect.objectContaining({name: 'Failed logins', enabled: false}),
        undefined
      )
    )
    await waitFor(() => expect(onPreview).toHaveBeenCalledWith('rule-42'))
    expect(await screen.findByText(/Draft saved/)).toBeInTheDocument()
    // Once saved, the button reverts to a plain Preview and the Enabled toggle is forced off.
    expect(screen.getByRole('button', {name: 'Preview'})).toBeInTheDocument()
    expect(screen.getByRole('switch', {name: 'Enabled'})).not.toBeChecked()
  })

  it('reuses the draft id on save after a preview, so it updates instead of creating a duplicate', async () => {
    const onSubmit = vi.fn().mockResolvedValue(makeRule({id: 'rule-42'}))
    render(<RuleForm onSubmit={onSubmit} onPreview={noopPreview()} onCancel={vi.fn()} />)
    fireEvent.change(screen.getByLabelText('Name'), {target: {value: 'Failed logins'}})
    fireEvent.change(screen.getByPlaceholderText(/status:/), {target: {value: 'status:failed'}})
    // Preview persists a disabled draft (created, so ruleId is undefined here).
    fireEvent.click(screen.getByRole('button', {name: 'Save draft & preview'}))
    await waitFor(() => expect(onSubmit).toHaveBeenCalledTimes(1))
    expect(onSubmit).toHaveBeenLastCalledWith(expect.objectContaining({name: 'Failed logins'}), undefined)
    // Saving now must target the persisted draft id (42), not create a second rule.
    fireEvent.click(screen.getByRole('button', {name: 'Save changes'}))
    await waitFor(() => expect(onSubmit).toHaveBeenCalledTimes(2))
    expect(onSubmit).toHaveBeenLastCalledWith(expect.objectContaining({name: 'Failed logins'}), 'rule-42')
  })

  it('surfaces a preview failure', async () => {
    const onSubmit = vi.fn().mockResolvedValue(makeRule({id: 'rule-12'}))
    const onPreview = vi.fn().mockRejectedValue(new Error('Invalid filter expression'))
    render(<RuleForm rule={makeRule()} onSubmit={onSubmit} onPreview={onPreview} onCancel={vi.fn()} />)
    fireEvent.click(screen.getByRole('button', {name: 'Preview'}))
    expect(await screen.findByText('Invalid filter expression')).toBeInTheDocument()
  })

  it('edits description, window, group_by, signal copy and tags', async () => {
    const onSubmit = vi.fn().mockResolvedValue(makeRule())
    render(<RuleForm rule={makeRule()} onSubmit={onSubmit} onPreview={noopPreview()} onCancel={vi.fn()} />)
    fireEvent.change(screen.getByLabelText('Description'), {target: {value: 'desc'}})
    fireEvent.change(screen.getByLabelText('Window (seconds)'), {target: {value: '600'}})
    fireEvent.change(screen.getByLabelText('Group by'), {target: {value: 'user.id, source.ip'}})
    fireEvent.change(screen.getByLabelText('Threshold count'), {target: {value: '9'}})
    fireEvent.change(screen.getByLabelText('Severity'), {target: {value: 'critical'}})
    fireEvent.change(screen.getByLabelText('Signal title'), {target: {value: 'Brute force detected'}})
    fireEvent.change(screen.getByLabelText('Signal message'), {target: {value: 'Many failures'}})
    fireEvent.change(screen.getByLabelText('Tags'), {target: {value: 'auth, brute-force'}})
    fireEvent.click(screen.getByRole('switch', {name: 'Enabled'}))
    fireEvent.click(screen.getByRole('button', {name: 'Save changes'}))
    await waitFor(() =>
      expect(onSubmit).toHaveBeenCalledWith(
        expect.objectContaining({
          description: 'desc',
          window_seconds: 600,
          group_by: ['user.id', 'source.ip'],
          threshold_count: 9,
          severity: 'critical',
          signal_title: 'Brute force detected',
          signal_message: 'Many failures',
          tags: ['auth', 'brute-force'],
          enabled: true,
        }),
        // Editing an existing rule threads its id so saves update rather than create.
        'rule-1'
      )
    )
  })

  it('labels the primary button "Save changes" when editing', () => {
    render(<RuleForm rule={makeRule()} onSubmit={vi.fn()} onPreview={noopPreview()} onCancel={vi.fn()} />)
    expect(screen.getByRole('button', {name: 'Save changes'})).toBeInTheDocument()
  })

  it('fires onCancel', () => {
    const onCancel = vi.fn()
    render(<RuleForm onSubmit={vi.fn()} onPreview={noopPreview()} onCancel={onCancel} />)
    fireEvent.click(screen.getByRole('button', {name: 'Cancel'}))
    expect(onCancel).toHaveBeenCalled()
  })
})
