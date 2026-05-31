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

import {render, screen} from '@testing-library/react'
import {describe, expect, it, vi} from 'vitest'
import {SignalDetailContent} from '../SignalDetailContent'
import {makeSignal, makeSignalDetail} from './fixtures'

describe('SignalDetailContent', () => {
  it('renders signal fields, entities and the evidence sample', () => {
    const detail = makeSignalDetail({signal: makeSignal({sample_count: 7})})
    render(<SignalDetailContent detail={detail} onTriage={vi.fn().mockResolvedValue(detail.signal)} />)
    expect(screen.getByText('Evidence sample (1)')).toBeInTheDocument()
    expect(screen.getByText(/login failed/)).toBeInTheDocument()
    expect(screen.getByText('user.id=alice')).toBeInTheDocument()
    expect(screen.getByText('1 evidence reference')).toBeInTheDocument()
  })

  it('shows the archive reason and audit activity when present', () => {
    const detail = makeSignalDetail({
      signal: makeSignal({status: 'archived', archive_reason: 'benign'}),
      audit: [
        {
          id: 1,
          action: 'status_change',
          from_status: 'open',
          to_status: 'archived',
          reason: 'benign',
          note: 'expected noise',
          created_at: '2026-05-30T03:00:00Z',
        },
      ],
    })
    render(<SignalDetailContent detail={detail} onTriage={vi.fn().mockResolvedValue(detail.signal)} />)
    expect(screen.getByText('Activity')).toBeInTheDocument()
    expect(screen.getByText('open → archived')).toBeInTheDocument()
    expect(screen.getByText(/expected noise/)).toBeInTheDocument()
  })

  it('handles an empty evidence sample', () => {
    const detail = makeSignalDetail({sample_events: [], evidence: []})
    render(<SignalDetailContent detail={detail} onTriage={vi.fn().mockResolvedValue(detail.signal)} />)
    expect(screen.getByText('No sample events')).toBeInTheDocument()
  })
})
