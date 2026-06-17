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
import {describe, expect, it} from 'vitest'
import type {WorkflowAuditEntry} from '@/lib/api'
import {WorkflowAuditTimeline} from '../WorkflowAuditTimeline'

describe('WorkflowAuditTimeline', () => {
  it('renders an empty state with no entries', () => {
    render(<WorkflowAuditTimeline entries={[]} />)
    expect(screen.getByText(/No audit activity recorded/i)).toBeInTheDocument()
  })

  it('renders the action, actor, and detail rows', () => {
    const entries: WorkflowAuditEntry[] = [
      {
        id: 'a1',
        workflow_id: '33333333-3333-4333-8333-333333333333',
        action: 'workflow.published',
        actor_user_id: '44444444-4444-4444-8444-444444444444',
        detail: {version: '2', name: 'Pager'},
        created_at: '2026-05-01T00:00:00Z',
      },
    ]
    render(<WorkflowAuditTimeline entries={entries} />)
    expect(screen.getByText('workflow.published')).toBeInTheDocument()
    expect(screen.getByText('User #44444444-4444-4444-8444-444444444444')).toBeInTheDocument()
    expect(screen.getByText('version')).toBeInTheDocument()
    expect(screen.getByText('2')).toBeInTheDocument()
  })

  it('labels system actors and omits empty detail', () => {
    const entries: WorkflowAuditEntry[] = [
      {
        id: 'a2',
        action: 'workflow.run',
        detail: {},
        created_at: '2026-05-02T00:00:00Z',
      },
    ]
    render(<WorkflowAuditTimeline entries={entries} />)
    expect(screen.getByText('System')).toBeInTheDocument()
    expect(screen.queryByRole('definition')).not.toBeInTheDocument()
  })
})
