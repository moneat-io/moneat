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

import {fireEvent, screen} from '@testing-library/react'
import {afterEach, beforeEach, describe, expect, it, vi} from 'vitest'
import type {LogEntry} from '@/lib/api'
import {renderWithQueryClient} from '@/test/utils'
import {LogTable} from '@/components/logs/LogTable'
import {setDemoEpoch} from '@/lib/demo'
import {logEntry} from '@/test/fixtures/logs'

describe('LogTable', () => {
  beforeEach(() => {
    globalThis.localStorage.clear()
    globalThis.sessionStorage.clear()
    setDemoEpoch(null)
  })

  afterEach(() => {
    setDemoEpoch(null)
  })

  // ──── Exception row selection ────

  it('selects grouped exception logs with a stack trace tag for the detail sheet', () => {
    const message = logEntry(
      'message',
      '2026-05-28T10:00:00.001Z',
      'Unhandled exception while processing request'
    )
    const header = logEntry('header', '2026-05-28T10:00:00.002Z', 'java.lang.IllegalStateException: boom')
    const frame = logEntry('frame', '2026-05-28T10:00:00.003Z', '\tat com.moneat.ApplicationKt.main')
    const onSelectLog = vi.fn()

    renderWithQueryClient(
      <LogTable
        logs={[frame, header, message]}
        selectedLogId={null}
        onSelectLog={onSelectLog}
      />
    )

    fireEvent.click(screen.getByText('Unhandled exception while processing request'))

    expect(onSelectLog).toHaveBeenCalledTimes(1)
    const selectedLog = onSelectLog.mock.calls[0][0] as LogEntry
    expect(selectedLog.logId).toBe('message')
    expect(selectedLog.message).toBe('Unhandled exception while processing request')
    expect(selectedLog.tags['exception.stacktrace']).toBe(
      'java.lang.IllegalStateException: boom\n\tat com.moneat.ApplicationKt.main'
    )
  })

  // ──── Empty state ────

  it('returns no table for empty log results', () => {
    const {container} = renderWithQueryClient(
      <LogTable logs={[]} selectedLogId={null} onSelectLog={vi.fn()} />
    )

    expect(container).toBeEmptyDOMElement()
  })

  // ──── Ungrouped rendering ────

  it('renders ungrouped non-compact rows with relative timestamps and fallback fields', () => {
    setDemoEpoch(Date.parse('2026-05-28T10:00:00.000Z'))
    const logs = [
      logEntry('future', '2026-05-28T10:00:01.000Z', 'future log', {level: 'trace'}),
      logEntry('now', '2026-05-28T10:00:00.500Z', 'subsecond log', {level: 'debug'}),
      logEntry('seconds', '2026-05-28T09:59:45.000Z', 'seconds log', {level: 'info'}),
      logEntry('minutes', '2026-05-28T09:45:00.000Z', 'minutes log', {level: 'warn', host: '', service: ''}),
      logEntry('hours', '2026-05-28T06:00:00.000Z', 'hours log', {level: 'fatal'}),
      logEntry('days', '2026-05-26T10:00:00.000Z', 'days log', {level: 'custom'}),
      logEntry('invalid', 'not-a-date', '', {level: '', body: ''}),
    ]
    const onSelectLog = vi.fn()

    renderWithQueryClient(
      <LogTable
        logs={logs}
        selectedLogId="minutes"
        onSelectLog={onSelectLog}
        compact={false}
        groupExceptions={false}
      />
    )

    expect(screen.getAllByText('just now')).toHaveLength(2)
    expect(screen.getByText('15s ago')).toBeInTheDocument()
    expect(screen.getByText('15m ago')).toBeInTheDocument()
    expect(screen.getByText('4h ago')).toBeInTheDocument()
    expect(screen.getByText('2d ago')).toBeInTheDocument()
    expect(screen.getAllByText('not-a-date')).toHaveLength(3)
    expect(screen.getAllByText('-').length).toBeGreaterThanOrEqual(3)

    fireEvent.click(screen.getByText('minutes log'))

    expect(onSelectLog).toHaveBeenCalledWith(expect.objectContaining({logId: 'minutes'}))
  })

  it('keeps an existing stack trace tag when grouped exception rows are displayed', () => {
    const message = logEntry(
      'message',
      '2026-05-28T10:00:00.001Z',
      'Already tagged exception',
      {tags: {'exception.stack_trace': 'existing stack'}}
    )
    const header = logEntry('header', '2026-05-28T10:00:00.002Z', 'java.lang.IllegalStateException: boom')
    const frame = logEntry('frame', '2026-05-28T10:00:00.003Z', '\tat com.moneat.ApplicationKt.main')
    const onSelectLog = vi.fn()

    renderWithQueryClient(
      <LogTable
        logs={[frame, header, message]}
        selectedLogId={null}
        onSelectLog={onSelectLog}
      />
    )

    fireEvent.click(screen.getByText('Already tagged exception'))

    expect(onSelectLog).toHaveBeenCalledTimes(1)
    expect(onSelectLog.mock.calls[0][0]).toMatchObject({
      logId: 'message',
      tags: {'exception.stack_trace': 'existing stack'},
    })
  })
})
