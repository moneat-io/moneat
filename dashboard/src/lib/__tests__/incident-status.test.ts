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
import {CheckCircle2, Zap} from 'lucide-react'
import {incidentStatusConfig, isResolvableIncidentStatus} from '@/lib/incident-status'

describe('incidentStatusConfig', () => {
  it('maps the migrated ACTIVE status to the live danger badge', () => {
    const config = incidentStatusConfig('ACTIVE')
    expect(config.variant).toBe('danger')
    expect(config.label).toBe('Active')
    expect(config.tone).toBe('danger')
    expect(config.icon).toBe(Zap)
  })

  it('maps RESOLVED to the success badge', () => {
    const config = incidentStatusConfig('RESOLVED')
    expect(config.variant).toBe('success')
    expect(config.label).toBe('Resolved')
    expect(config.icon).toBe(CheckCircle2)
  })

  it.each([
    ['TRIAGE', 'warning', 'Triage'],
    ['POST_INCIDENT', 'info', 'Post-incident'],
    ['CLOSED', 'neutral', 'Closed'],
    ['CANCELLED', 'neutral', 'Cancelled'],
    ['DECLINED', 'neutral', 'Declined'],
  ])('maps the canonical %s status to a %s badge', (status, variant, label) => {
    const config = incidentStatusConfig(status)
    expect(config.variant).toBe(variant)
    expect(config.label).toBe(label)
  })

  it('falls back to a neutral badge with a humanized label for legacy or unknown statuses', () => {
    // OPEN was migrated to ACTIVE and should no longer arrive on the wire.
    const legacy = incidentStatusConfig('OPEN')
    expect(legacy.variant).toBe('neutral')
    expect(legacy.label).toBe('Open')

    const unknown = incidentStatusConfig('SOME_NEW_STATE')
    expect(unknown.variant).toBe('neutral')
    expect(unknown.label).toBe('Some new state')

    expect(incidentStatusConfig('').label).toBe('Unknown')
  })
})

describe('isResolvableIncidentStatus', () => {
  it('only allows resolving an ACTIVE incident', () => {
    expect(isResolvableIncidentStatus('ACTIVE')).toBe(true)
    for (const status of ['TRIAGE', 'RESOLVED', 'POST_INCIDENT', 'CLOSED', 'CANCELLED', 'DECLINED', 'OPEN']) {
      expect(isResolvableIncidentStatus(status)).toBe(false)
    }
  })
})
