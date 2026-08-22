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
import {
  PROVENANCE_META,
  TIMELINE_PROVENANCES,
  buildFormFieldInput,
  buildTimelineEditPayload,
  evaluateFieldCondition,
  incidentModeMeta,
  incidentVisibilityMeta,
  sourceTypeLabel,
  summarizeTimelineDetails,
  timelineEventStyle,
} from '../incident-modeling'

describe('evaluateFieldCondition (backend-aligned, fail-closed)', () => {
  it('shows the field when the condition is empty', () => {
    expect(evaluateFieldCondition(undefined, {})).toBe(true)
    expect(evaluateFieldCondition({}, {a: 1})).toBe(true)
  })

  it('matches typed equality for numbers and multi-select arrays', () => {
    expect(evaluateFieldCondition({fieldKey: 'sev', equals: 2}, {sev: 2})).toBe(true)
    expect(evaluateFieldCondition({fieldKey: 'sev', equals: 2}, {sev: 3})).toBe(false)
    // A string "2" must not match the numeric 2 (typed equality).
    expect(evaluateFieldCondition({fieldKey: 'sev', equals: 2}, {sev: '2'})).toBe(false)
    expect(evaluateFieldCondition({fieldKey: 'tags', equals: ['a', 'b']}, {tags: ['a', 'b']})).toBe(true)
    expect(evaluateFieldCondition({fieldKey: 'tags', equals: ['a', 'b']}, {tags: ['b', 'a']})).toBe(false)
  })

  it('fails closed for malformed conditions (missing fieldKey or equals)', () => {
    expect(evaluateFieldCondition({equals: 'x'}, {})).toBe(false)
    expect(evaluateFieldCondition({fieldKey: 'sev'}, {sev: 'anything'})).toBe(false)
    expect(evaluateFieldCondition({fieldKey: 5 as unknown as string, equals: 'x'}, {})).toBe(false)
  })
})

describe('buildFormFieldInput (typed defaults & conditions)', () => {
  it('preserves numeric and array values instead of stringifying', () => {
    const numberField = buildFormFieldInput({
      fieldId: 'f1',
      position: 0,
      visible: true,
      required: true,
      helpText: '  ',
      defaultValue: 7,
      conditionFieldKey: '',
      conditionEquals: undefined,
    })
    expect(numberField.defaultValue).toBe(7)
    expect(numberField.helpText).toBeUndefined()
    expect(numberField.condition).toBeUndefined()

    const multi = buildFormFieldInput({
      fieldId: 'f2',
      position: 1,
      visible: true,
      required: false,
      helpText: 'pick',
      defaultValue: ['us', 'eu'],
      conditionFieldKey: 'sev',
      conditionEquals: 2,
    })
    expect(multi.defaultValue).toEqual(['us', 'eu'])
    expect(multi.condition).toEqual({fieldKey: 'sev', equals: 2})
  })

  it('drops empty defaults and incomplete conditions', () => {
    const input = buildFormFieldInput({
      fieldId: 'f3',
      position: 2,
      visible: true,
      required: false,
      helpText: '',
      defaultValue: '',
      conditionFieldKey: 'sev',
      conditionEquals: undefined,
    })
    expect(input.defaultValue).toBeUndefined()
    expect(input.condition).toBeUndefined()
  })
})

describe('buildTimelineEditPayload', () => {
  const original = {
    eventType: 'NOTE_ADDED',
    visibility: 'ORGANIZATION' as const,
    occurredAtLocal: '2026-06-05T12:00',
    details: {note: 'hi'},
  }

  it('omits originalOccurredAt when the local input is unchanged', () => {
    const result = buildTimelineEditPayload(original, {
      eventType: 'NOTE_ADDED',
      visibility: 'ORGANIZATION',
      occurredAtLocal: '2026-06-05T12:00',
      detailsText: JSON.stringify({note: 'hi'}, null, 2),
      reason: '',
    })
    expect('payload' in result).toBe(true)
    if ('payload' in result) {
      expect(result.payload.originalOccurredAt).toBeUndefined()
      expect(result.payload.details).toBeUndefined()
      expect(result.payload.eventType).toBeUndefined()
    }
  })

  it('sends originalOccurredAt only when the local input changed', () => {
    const result = buildTimelineEditPayload(original, {
      eventType: 'NOTE_ADDED',
      visibility: 'PARTICIPANTS',
      occurredAtLocal: '2026-06-05T13:30',
      detailsText: JSON.stringify({note: 'hi'}, null, 2),
      reason: 'fix time',
    })
    expect('payload' in result).toBe(true)
    if ('payload' in result) {
      expect(result.payload.originalOccurredAt).toBe(new Date('2026-06-05T13:30').toISOString())
      expect(result.payload.visibility).toBe('PARTICIPANTS')
      expect(result.payload.reason).toBe('fix time')
    }
  })

  it('rejects details that are not a JSON object', () => {
    expect(buildTimelineEditPayload(original, {
      eventType: 'NOTE_ADDED',
      visibility: 'ORGANIZATION',
      occurredAtLocal: '2026-06-05T12:00',
      detailsText: '[1,2,3]',
      reason: '',
    })).toEqual({error: expect.stringMatching(/object/i)})

    expect(buildTimelineEditPayload(original, {
      eventType: 'NOTE_ADDED',
      visibility: 'ORGANIZATION',
      occurredAtLocal: '2026-06-05T12:00',
      detailsText: 'null',
      reason: '',
    })).toEqual({error: expect.stringMatching(/object/i)})

    expect(buildTimelineEditPayload(original, {
      eventType: 'NOTE_ADDED',
      visibility: 'ORGANIZATION',
      occurredAtLocal: '2026-06-05T12:00',
      detailsText: '{ not json',
      reason: '',
    })).toEqual({error: expect.stringMatching(/valid json/i)})
  })
})

describe('summarizeTimelineDetails', () => {
  const resolve = (id?: string) => (id === 'u-grace' ? 'Grace' : id === 'u-lin' ? 'Lin' : 'someone')

  it('summarizes role, participant, status, and alert events from real details', () => {
    expect(
      summarizeTimelineDetails('ROLE_ASSIGNED', {role: 'Commander', assigneeUserId: 'u-grace'}, resolve).summary,
    ).toBe('Commander → Grace')
    expect(
      summarizeTimelineDetails('ROLE_HANDED_OVER', {role: 'Scribe', fromUserId: 'u-grace', toUserId: 'u-lin'}, resolve)
        .summary,
    ).toBe('Scribe: Grace → Lin')
    expect(summarizeTimelineDetails('RESOLVED', {previousStatus: 'ACTIVE'}, resolve).summary).toBe('From Active')
    expect(summarizeTimelineDetails('ALERT_LINKED', {alertTitle: 'Payments outage'}, resolve).summary).toBe(
      'Payments outage',
    )
  })

  it('provides a key/value fallback for unknown events but hides private and plumbing keys', () => {
    const {summary, fallback} = summarizeTimelineDetails(
      'SLACK_SELECTION',
      {channel: '#incidents', selection: 'rollback', origin: 'SLACK', instructions: 'SECRET', roleId: 'r-1'},
      resolve,
    )
    expect(summary).toBeNull()
    const keys = fallback.map(([key]) => key.toLowerCase())
    expect(keys).toContain('channel')
    expect(keys).toContain('selection')
    expect(keys).not.toContain('origin')
    expect(keys.some((k) => k.includes('instruction'))).toBe(false)
    expect(fallback.some(([, value]) => value === 'SECRET')).toBe(false)
  })

  it('leaves notes to the dedicated note block', () => {
    expect(summarizeTimelineDetails('NOTE_ADDED', {note: 'hello'}, resolve)).toEqual({summary: null, fallback: []})
  })
})

describe('style + enum safety', () => {
  it('never uses charts-only ramp classes for timeline event chrome', () => {
    const types = [
      'DECLARED',
      'TRIAGED',
      'ACCEPTED',
      'RESOLVED',
      'ROLE_ASSIGNED',
      'ROLE_HANDED_OVER',
      'SOURCE_LINKED',
      'ALERT_LINKED',
      'NOTIFICATION_SENT',
      'UNKNOWN_EVENT',
    ]
    for (const type of types) {
      const style = timelineEventStyle(type)
      expect(style.color).not.toMatch(/chart/)
      expect(style.bgColor).not.toMatch(/chart/)
    }
  })

  it('does not use the cyan accent for provenance metadata badges', () => {
    for (const provenance of TIMELINE_PROVENANCES) {
      expect(PROVENANCE_META[provenance].variant).not.toBe('accent')
    }
  })

  it('falls back safely for unknown mode / visibility / source enum values', () => {
    expect(incidentModeMeta('SIMULATION')).toEqual({label: 'Simulation', description: '', variant: 'neutral'})
    expect(incidentVisibilityMeta('TEAM_ONLY').label).toBe('Team only')
    expect(sourceTypeLabel('PAGERDUTY_INCIDENT')).toBe('Pagerduty incident')
  })
})
