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
import {AlertRouteConditionsSection} from '../AlertRouteConditionsSection'
import {AlertRouteGroupingSection} from '../AlertRouteGroupingSection'
import {AlertRouteIncidentSection} from '../AlertRouteIncidentSection'
import {AlertRoutePagingSection} from '../AlertRoutePagingSection'
import {DurationField} from '../DurationField'

describe('alert route editor sections', () => {
  it('adds and removes condition groups and condition rows', () => {
    const onChange = vi.fn()
    const {rerender} = render(
      <AlertRouteConditionsSection
        groups={[{clientKey: 'group-1', conditions: [
          {clientKey: 'condition-1', field: 'priority', operator: 'EQUALS', values: ['P1']},
        ]}]}
        onChange={onChange}
      />
    )
    fireEvent.click(screen.getByText('Add condition group'))
    fireEvent.click(screen.getByText('Add condition'))
    expect(onChange).toHaveBeenCalledTimes(2)

    rerender(
      <AlertRouteConditionsSection
        groups={[
          {clientKey: 'group-1', conditions: [
            {clientKey: 'condition-1', field: 'metadata', operator: 'IS_SET', values: [], metadataKey: 'environment'},
          ]},
          {clientKey: 'group-2', conditions: [
            {clientKey: 'condition-2', field: 'title', operator: 'CONTAINS', values: ['outage']},
            {clientKey: 'condition-3', field: 'status', operator: 'EQUALS', values: ['FIRING']},
          ]},
        ]}
        onChange={onChange}
      />
    )
    const textValue = screen.getAllByLabelText('Condition value')[0]
    textValue.focus()
    fireEvent.change(textValue, {target: {value: 'outage, degraded'}})
    expect(textValue).toHaveFocus()
    fireEvent.click(screen.getByLabelText('Remove condition group 2'))
    fireEvent.click(screen.getAllByLabelText('Remove condition')[0])
    expect(screen.getByText('or')).toBeInTheDocument()
  })

  it('renders paging target variants and dispatches add/remove changes', () => {
    const onChange = vi.fn()
    const {rerender} = render(
      <AlertRoutePagingSection paging={{mode: 'NONE', targets: []}} escalationPolicies={[]} teams={[]} onChange={onChange} />
    )
    expect(screen.queryByText('Add target')).not.toBeInTheDocument()

    rerender(
      <AlertRoutePagingSection
        paging={{mode: 'EVERY_EPISODE', targets: [
          {clientKey: 'target-1', kind: 'ESCALATION_POLICY', escalationPolicyId: 'policy'},
          {clientKey: 'target-2', kind: 'TEAM', teamId: 'team'},
          {clientKey: 'target-3', kind: 'OWNERSHIP_DERIVED', ownershipSource: 'SERVICE'},
        ]}}
        escalationPolicies={[{id: 'policy', name: 'Primary'}]}
        teams={[{id: 'team', name: 'Platform'}]}
        onChange={onChange}
      />
    )
    expect(screen.getByLabelText('Escalation policy')).toBeInTheDocument()
    expect(screen.getByLabelText('Team')).toBeInTheDocument()
    expect(screen.getByLabelText('Ownership source')).toBeInTheDocument()
    fireEvent.click(screen.getByText('Add target'))
    fireEvent.click(screen.getAllByLabelText('Remove target')[0])
    expect(onChange).toHaveBeenCalledTimes(2)
  })

  it('edits grouping keys and duration units', () => {
    const onChange = vi.fn()
    render(
      <AlertRouteGroupingSection
        grouping={{behavior: 'AUTOMATIC', keys: ['ownership.service'], windowKind: 'FIXED', windowSeconds: 300}}
        onChange={onChange}
      />
    )
    fireEvent.change(screen.getByLabelText('Add grouping key'), {target: {value: 'metadata.environment'}})
    fireEvent.click(screen.getByText('Add'))
    fireEvent.click(screen.getByLabelText('Remove grouping key ownership.service'))
    expect(onChange).toHaveBeenCalledTimes(2)

    const onDuration = vi.fn()
    const {rerender} = render(
      <DurationField id="duration" ariaLabel="Duration" seconds={120} minSeconds={0} onChange={onDuration} />
    )
    fireEvent.change(screen.getByLabelText('Duration amount'), {target: {value: '3'}})
    expect(onDuration).toHaveBeenCalled()
    fireEvent.change(screen.getByLabelText('Duration amount'), {target: {value: ''}})
    expect(screen.getByLabelText('Duration unit')).toHaveTextContent('minutes')
    fireEvent.change(screen.getByLabelText('Duration amount'), {target: {value: '10'}})
    expect(onDuration).toHaveBeenLastCalledWith(600)
    rerender(<DurationField id="duration" ariaLabel="Duration" seconds={7200} minSeconds={0} onChange={onDuration} />)
    expect(screen.getByLabelText('Duration amount')).toHaveValue(2)
    rerender(<DurationField id="duration" ariaLabel="Duration" seconds={300} minSeconds={1} onChange={onDuration} />)
    fireEvent.change(screen.getByLabelText('Duration amount'), {target: {value: '0'}})
    expect(onDuration).toHaveBeenLastCalledWith(1)
    expect(screen.getByLabelText('Duration amount')).toHaveValue(1)
    expect(screen.getByLabelText('Duration unit')).toHaveTextContent('seconds')
  })

  it('renders incident and recovery modes and dispatches native controls', () => {
    const onIncidentChange = vi.fn()
    const onRecoveryChange = vi.fn()
    const {rerender} = render(
      <AlertRouteIncidentSection
        incident={{create: false, mode: 'TRIAGE'}}
        recovery={{cancelEscalations: false, declineUnacceptedTriage: false, graceSeconds: 0}}
        incidentTypeOptions={[]}
        onIncidentChange={onIncidentChange}
        onRecoveryChange={onRecoveryChange}
      />
    )
    expect(screen.getByRole('status')).toHaveTextContent('will not create incidents while this option is off')
    fireEvent.click(screen.getByLabelText('Create an incident for matched alerts'))
    fireEvent.click(screen.getByLabelText('Cancel active escalations when the group recovers'))
    fireEvent.click(screen.getByLabelText('Decline triage incidents that were never accepted'))
    expect(onIncidentChange).toHaveBeenCalled()
    expect(onRecoveryChange).toHaveBeenCalledTimes(2)

    rerender(
      <AlertRouteIncidentSection
        incident={{
          create: true,
          mode: 'ACTIVE',
          severity: 'SEV-1',
          incidentType: 'retired-type',
          titleTemplate: '{{alert.title}}',
          summaryTemplate: '{{alert.description}}',
        }}
        recovery={{cancelEscalations: true, declineUnacceptedTriage: true, graceSeconds: 60}}
        incidentTypeOptions={[{value: 'operational', label: 'Operational'}]}
        onIncidentChange={onIncidentChange}
        onRecoveryChange={onRecoveryChange}
      />
    )
    expect(screen.queryByRole('status')).not.toBeInTheDocument()
    fireEvent.change(screen.getByLabelText('Title template (optional)'), {target: {value: 'New title'}})
    fireEvent.change(screen.getByLabelText('Summary template (optional)'), {target: {value: 'New summary'}})
    expect(screen.getByText('Active incidents require a severity.')).toBeInTheDocument()
  })
})
