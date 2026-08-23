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

import {fireEvent, render, screen} from '@testing-library/react'
import {describe, expect, it, vi} from 'vitest'
import type {AlertRouteEvaluation, AlertRoutePreviewResponse} from '@/lib/api/types'
import {AlertRoutePreviewPanel} from '../AlertRoutePreviewPanel'

const evaluation = (
  routeId: string,
  selected: boolean,
  reason: AlertRouteEvaluation['reason'],
): AlertRouteEvaluation => ({
  routeId,
  key: routeId,
  name: routeId,
  position: selected ? 0 : 1,
  enabled: true,
  matched: reason !== 'NO_GROUP_MATCHED',
  selected,
  reason,
  groups: [{
    position: 0,
    matched: selected,
    conditions: [{field: 'priority', operator: 'EQUALS', values: ['P1'], actualValue: 'P1', matched: selected}],
  }],
})

const result: AlertRoutePreviewResponse = {
  matchedRouteId: 'winner',
  evaluations: [
    evaluation('winner', true, 'SELECTED'),
    evaluation('shadowed', false, 'EARLIER_ROUTE_SELECTED'),
    evaluation('miss', false, 'NO_GROUP_MATCHED'),
  ],
  droppedMetadataKeys: ['secret'],
  decision: {
    routeId: 'winner',
    key: 'winner',
    name: 'winner',
    position: 0,
    revision: 2,
    paging: {mode: 'FIRST_EPISODE_PER_GROUP', escalationPolicyIds: [], teamIds: [], unresolvedTargets: ['team']},
    grouping: {
      behavior: 'SUGGESTED', automatic: false, groupKey: 'key', keys: ['metadata.environment'],
      tuple: {environment: 'prod'}, singleton: false, unresolvedKeys: ['metadata.region'],
      windowKind: 'ROLLING', windowSeconds: 300,
    },
    incident: {
      create: true, mode: 'TRIAGE', severity: 'SEV-2', unresolvedReferences: ['metadata.region'],
    },
    recovery: {cancelEscalations: true, declineUnacceptedTriage: false, graceSeconds: 0},
  },
}

describe('AlertRoutePreviewPanel', () => {
  it('runs supplied sample context and manages metadata rows', () => {
    const onRun = vi.fn()
    render(<AlertRoutePreviewPanel onRun={onRun} result={result} isLoading={false} editingRouteId="shadowed" />)
    fireEvent.change(screen.getByLabelText('Title'), {target: {value: 'CPU alert'}})
    fireEvent.click(screen.getByText('Add attribute'))
    const metadataValue = screen.getByPlaceholderText('value')
    metadataValue.focus()
    fireEvent.change(metadataValue, {target: {value: 'prod'}})
    expect(metadataValue).toHaveFocus()
    fireEvent.change(metadataValue, {target: {value: 'production'}})
    expect(metadataValue).toHaveFocus()
    fireEvent.click(screen.getByText('Run preview'))

    expect(onRun).toHaveBeenCalledWith({sample: expect.objectContaining({
      title: 'CPU alert',
      metadata: {'alert.condition': 'production'},
    })})
    expect(screen.getByText(/will not run/)).toBeInTheDocument()
    expect(screen.getByText(/Unresolved grouping keys/)).toBeInTheDocument()
    expect(screen.getByText(/Dropped unapproved attributes/)).toBeInTheDocument()
    fireEvent.click(screen.getByLabelText('Remove attribute'))
  })

  it('validates and runs a saved episode preview', () => {
    const onRun = vi.fn()
    render(<AlertRoutePreviewPanel onRun={onRun} isLoading={false} error="Preview failed" />)
    fireEvent.click(screen.getByText('Saved alert episode'))
    fireEvent.change(screen.getByLabelText('Alert episode ID'), {target: {value: 'bad'}})
    expect(screen.getByText('Enter a valid alert episode ID.')).toBeInTheDocument()
    const id = '11111111-1111-4111-8111-111111111111'
    fireEvent.change(screen.getByLabelText('Alert episode ID'), {target: {value: id}})
    fireEvent.click(screen.getByText('Run preview'))
    expect(onRun).toHaveBeenCalledWith({episodeId: id})
    expect(screen.getByText('Preview failed')).toBeInTheDocument()
  })

  it('explains the no-match and no-incident result', () => {
    render(
      <AlertRoutePreviewPanel
        onRun={vi.fn()}
        isLoading={false}
        result={{
          evaluations: [evaluation('disabled', false, 'ROUTE_DISABLED')],
          droppedMetadataKeys: [],
          decision: undefined,
        }}
      />
    )
    expect(screen.getByText(/No route matches/)).toBeInTheDocument()
    expect(screen.getByText(/Route is disabled/)).toBeInTheDocument()
  })
})
