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

import {describe, expect, it} from 'vitest'
import {groupExceptionLogs} from '@/components/logs/groupExceptionLogs'
import {logEntry} from '@/test/fixtures/logs'

describe('groupExceptionLogs', () => {
  // ──── Descending log rows ────

  it('attaches newer stack frames to the clicked exception message row', () => {
    const message = logEntry(
      'message',
      '2026-05-28T10:00:00.001Z',
      'Unhandled exception while processing request'
    )
    const header = logEntry('header', '2026-05-28T10:00:00.002Z', 'java.lang.IllegalStateException: boom')
    const frameOne = logEntry('frame-1', '2026-05-28T10:00:00.003Z', '\tat io.ktor.server.Application.handle')
    const frameTwo = logEntry('frame-2', '2026-05-28T10:00:00.004Z', '\tat com.moneat.ApplicationKt.main')

    const groups = groupExceptionLogs([frameTwo, frameOne, header, message])

    expect(groups).toHaveLength(1)
    expect(groups[0].logs.map((log) => log.logId)).toEqual(['message', 'header', 'frame-1', 'frame-2'])
    expect(groups[0].mergedMessage).toContain('Unhandled exception while processing request')
    expect(groups[0].mergedMessage).toContain('java.lang.IllegalStateException: boom')
  })

  // ──── Ascending log rows ────

  it('keeps existing chronological exception grouping behavior', () => {
    const message = logEntry('message', '2026-05-28T10:00:00.001Z', 'Background job failed')
    const header = logEntry('header', '2026-05-28T10:00:00.002Z', 'kotlin.IllegalStateException: boom')
    const frame = logEntry('frame', '2026-05-28T10:00:00.003Z', '    at com.moneat.jobs.JobRunner.run')

    const groups = groupExceptionLogs([message, header, frame])

    expect(groups).toHaveLength(1)
    expect(groups[0].logs.map((log) => log.logId)).toEqual(['message', 'header', 'frame'])
    expect(groups[0].mergedMessage).toBe(
      'Background job failed\nkotlin.IllegalStateException: boom\nat com.moneat.jobs.JobRunner.run'
    )
  })
})
