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
import type {AlertRouteEvaluation, AlertRoutePreviewResponse} from '@/lib/api/types'
import {
  createEmptyAlertRouteForm,
  formToRequest,
  overlappingEarlierRoutes,
  validateAlertRouteForm,
} from '../alertRouteModel'

describe('alert route request mapping and validation', () => {
  it('normalizes values and includes the expected revision without stale target fields', () => {
    const form = createEmptyAlertRouteForm()
    form.key = ' payments-route '
    form.name = ' Payments '
    form.description = '  '
    form.conditionGroups[0].conditions[0].values = [' P1 ', '']
    form.paging = {
      mode: 'EVERY_EPISODE',
      targets: [{clientKey: 'target', kind: 'TEAM', teamId: 'team-id', escalationPolicyId: 'stale'}],
    }
    form.grouping.keys = [' Ownership.Service ', 'metadata.environment']

    const request = formToRequest(form, 7)
    expect(request).toMatchObject({
      key: 'payments-route',
      name: 'Payments',
      description: null,
      expectedRevision: 7,
      conditionGroups: [{conditions: [{values: ['P1']}]}],
      paging: {targets: [{kind: 'TEAM', teamId: 'team-id'}]},
      grouping: {keys: ['ownership.service', 'metadata.environment']},
    })
    expect(JSON.stringify(request)).not.toContain('clientKey')
  })

  it('requires valid route fields, paging targets, grouping windows, and active severity', () => {
    const form = createEmptyAlertRouteForm()
    form.key = 'Bad Key'
    form.name = ' '
    form.paging = {mode: 'FIRST_EPISODE_PER_GROUP', targets: []}
    form.grouping.windowSeconds = 0
    form.grouping.keys = ['service']
    form.incident = {create: true, mode: 'ACTIVE'}

    expect(validateAlertRouteForm(form)).toEqual(expect.arrayContaining([
      expect.stringMatching(/route key/i),
      expect.stringMatching(/route name/i),
      expect.stringMatching(/paging target/i),
      expect.stringMatching(/grouping window/i),
      expect.stringMatching(/supported scoped field/i),
      expect.stringMatching(/requires a severity/i),
    ]))
  })
})

describe('alert route preview interpretation', () => {
  const evaluation = (
    routeId: string,
    selected: boolean,
    reason: AlertRouteEvaluation['reason'],
  ): AlertRouteEvaluation => ({
    routeId,
    key: routeId,
    name: routeId,
    position: 0,
    enabled: true,
    matched: true,
    selected,
    reason,
    groups: [],
  })

  it('identifies matching routes shadowed by an earlier selection', () => {
    const preview: AlertRoutePreviewResponse = {
      matchedRouteId: 'first',
      evaluations: [
        evaluation('first', true, 'SELECTED'),
        evaluation('shadowed', false, 'EARLIER_ROUTE_SELECTED'),
        {...evaluation('unmatched', false, 'NO_GROUP_MATCHED'), matched: false},
      ],
      droppedMetadataKeys: [],
    }

    expect(overlappingEarlierRoutes(preview).map((route) => route.routeId)).toEqual(['shadowed'])
  })
})
