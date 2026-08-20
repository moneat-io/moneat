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

import {afterEach, describe, expect, it, vi} from 'vitest'
import {fireEvent, render, screen, waitFor} from '@testing-library/react'
import {CopyField} from '../copy-field'

// These tests drive the button with fireEvent rather than userEvent, because
// userEvent.setup() installs its own navigator.clipboard stub over ours.

const originalClipboard = Object.getOwnPropertyDescriptor(globalThis.navigator, 'clipboard')

function setClipboard(clipboard: unknown) {
  Object.defineProperty(globalThis.navigator, 'clipboard', {configurable: true, value: clipboard})
}

afterEach(() => {
  if (originalClipboard) {
    Object.defineProperty(globalThis.navigator, 'clipboard', originalClipboard)
  } else {
    delete (globalThis.navigator as {clipboard?: unknown}).clipboard
  }
})

describe('CopyField', () => {
  it('labels the read-only value and copies it', async () => {
    const writeText = vi.fn().mockResolvedValue(undefined)
    setClipboard({writeText})
    const onCopied = vi.fn()

    render(<CopyField label="DSN" value="https://abc123@api.moneat.io/1" onCopied={onCopied} />)

    const input = screen.getByLabelText('DSN') as HTMLInputElement
    expect(input.value).toBe('https://abc123@api.moneat.io/1')
    expect(input.readOnly).toBe(true)

    fireEvent.click(screen.getByRole('button', {name: 'Copy dsn'}))

    expect(writeText).toHaveBeenCalledWith('https://abc123@api.moneat.io/1')
    await waitFor(() => expect(screen.getByRole('button', {name: 'DSN copied'})).toBeInTheDocument())
    expect(onCopied).toHaveBeenCalledTimes(1)
  })

  it('stays silent when the clipboard write is rejected', async () => {
    const writeText = vi.fn().mockRejectedValue(new Error('denied'))
    setClipboard({writeText})
    const onCopied = vi.fn()

    render(<CopyField label="DSN" value="https://abc123@api.moneat.io/1" onCopied={onCopied} />)
    fireEvent.click(screen.getByRole('button', {name: 'Copy dsn'}))

    await waitFor(() => expect(writeText).toHaveBeenCalled())
    expect(onCopied).not.toHaveBeenCalled()
    expect(screen.getByRole('button', {name: 'Copy dsn'})).toBeInTheDocument()
    expect(screen.queryByRole('button', {name: 'DSN copied'})).not.toBeInTheDocument()
  })

  it('stays silent when no clipboard is available', async () => {
    setClipboard(undefined)
    const onCopied = vi.fn()

    render(<CopyField label="OTLP endpoint" value="https://api.moneat.io" onCopied={onCopied} />)
    fireEvent.click(screen.getByRole('button', {name: 'Copy otlp endpoint'}))

    await Promise.resolve()
    expect(onCopied).not.toHaveBeenCalled()
    expect(screen.getByRole('button', {name: 'Copy otlp endpoint'})).toBeInTheDocument()
  })
})
