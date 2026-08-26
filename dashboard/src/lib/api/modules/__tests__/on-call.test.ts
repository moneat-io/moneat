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

import { describe, it, expect, beforeEach } from 'vitest'
import { http, HttpResponse } from 'msw'
import { server } from '@/test/mocks/server'
import { api } from '@/lib/api'

const API_BASE = 'http://localhost:8080/v1'
const PRIORITY_ID = '11111111-1111-4111-8111-111111111111'
const SCHEDULE_ID_PRIMARY = '22222222-2222-4222-8222-222222222222'
const SCHEDULE_ID_SECONDARY = '33333333-3333-4333-8333-333333333333'
const SCHEDULE_ID_CREATED = '44444444-4444-4444-8444-444444444444'
const OVERRIDE_ID = '55555555-5555-4555-8555-555555555555'
const POLICY_ID_DEFAULT = '66666666-6666-4666-8666-666666666666'
const POLICY_ID_URGENT = '77777777-7777-4777-8777-777777777777'
const POLICY_ID_CREATED = '88888888-8888-4888-8888-888888888888'
const POLICY_ID_DELETE = '99999999-9999-4999-8999-999999999999'
const ALERT_ID_PRIMARY = 'aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa'
const ALERT_ID_SECONDARY = 'bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb'
const USER_ID_ON_CALL = 'cccccccc-cccc-4ccc-8ccc-cccccccccccc'
const USER_ID_OVERRIDE = 'dddddddd-dddd-4ddd-8ddd-dddddddddddd'
const USER_ID_REASSIGN = 'eeeeeeee-eeee-4eee-8eee-eeeeeeeeeeee'
const DEVICE_ID = 'ffffffff-ffff-4fff-8fff-ffffffffffff'
const DECLARED_INCIDENT_ID = '12345678-1234-4234-8234-123456789abc'
const ON_CALL_INCIDENT_ID_PRIMARY = '23456789-2345-4345-8345-23456789abcd'
const ON_CALL_INCIDENT_ID_SECONDARY = '3456789a-3456-4456-8456-3456789abcde'

describe('onCallMethods', () => {
  beforeEach(() => {
    localStorage.clear()
    sessionStorage.clear()
    sessionStorage.setItem('authenticated', 'true')
  })

  // ──── Priorities ────

  describe('getPriorities', () => {
    it('fetches priorities list', async () => {
      const mock = [{ id: PRIORITY_ID, level: 'P1', label: 'Critical', color: '#ff0000' }]
      server.use(
        http.get(`${API_BASE}/priorities`, () => HttpResponse.json(mock))
      )
      const result = await api.getPriorities()
      expect(result).toEqual(mock)
    })
  })

  describe('updatePriorities', () => {
    it('sends PUT with request body', async () => {
      const request = { priorities: [{ priority: 'P1', isPageable: true, label: 'Critical' }] }
      const mock = [{ id: PRIORITY_ID, level: 'P1', label: 'Critical', color: '#ff0000' }]
      server.use(
        http.put(`${API_BASE}/priorities`, async ({ request: req }) => {
          const body = await req.json() as Record<string, unknown>
          expect(body).toEqual(request)
          return HttpResponse.json(mock)
        })
      )
      const result = await api.updatePriorities(request)
      expect(result).toEqual(mock)
    })
  })

  // ──── Business Hours ────

  describe('getBusinessHours', () => {
    it('fetches business hours', async () => {
      const mock = { timezone: 'UTC', startHour: 9, endHour: 17, workDays: [1, 2, 3, 4, 5] }
      server.use(
        http.get(`${API_BASE}/business-hours`, () => HttpResponse.json(mock))
      )
      const result = await api.getBusinessHours()
      expect(result).toEqual(mock)
    })
  })

  describe('updateBusinessHours', () => {
    it('sends PUT with business hours config', async () => {
      const request = {
        timezone: 'US/Eastern',
        enabled: true,
        windows: [
          {dayOfWeek: 1, startTime: '08:00', endTime: '18:00'},
          {dayOfWeek: 2, startTime: '08:00', endTime: '18:00'},
          {dayOfWeek: 3, startTime: '08:00', endTime: '18:00'},
          {dayOfWeek: 4, startTime: '08:00', endTime: '18:00'},
          {dayOfWeek: 5, startTime: '08:00', endTime: '18:00'},
        ],
      }
      server.use(
        http.put(`${API_BASE}/business-hours`, async ({ request: req }) => {
          const body = await req.json() as Record<string, unknown>
          expect(body).toEqual(request)
          return HttpResponse.json(request)
        })
      )
      const result = await api.updateBusinessHours(request)
      expect(result).toEqual(request)
    })
  })

  // ──── On-Call Schedules ────

  describe('getOnCallSchedules', () => {
    it('fetches all schedules', async () => {
      const mock = [{ id: SCHEDULE_ID_PRIMARY, name: 'Primary', rotationType: 'weekly' }]
      server.use(
        http.get(`${API_BASE}/on-call/schedules`, () => HttpResponse.json(mock))
      )
      const result = await api.getOnCallSchedules()
      expect(result).toEqual(mock)
    })
  })

  describe('getOnCallSchedule', () => {
    it('fetches a single schedule by id', async () => {
      const mock = { id: SCHEDULE_ID_SECONDARY, name: 'Secondary', rotationType: 'daily' }
      server.use(
        http.get(`${API_BASE}/on-call/schedules/${SCHEDULE_ID_SECONDARY}`, () => HttpResponse.json(mock))
      )
      const result = await api.getOnCallSchedule(SCHEDULE_ID_SECONDARY)
      expect(result).toEqual(mock)
    })
  })

  describe('createOnCallSchedule', () => {
    it('sends POST to create schedule', async () => {
      const request = { name: 'New Schedule', rotationType: 'weekly' }
      const mock = { id: SCHEDULE_ID_CREATED, ...request }
      server.use(
        http.post(`${API_BASE}/on-call/schedules`, async ({ request: req }) => {
          const body = await req.json() as Record<string, unknown>
          expect(body.name).toBe('New Schedule')
          return HttpResponse.json(mock)
        })
      )
      const result = await api.createOnCallSchedule(request as never)
      expect(result).toEqual(mock)
    })
  })

  describe('updateOnCallSchedule', () => {
    it('sends PUT to update schedule', async () => {
      const request = { name: 'Updated Schedule' }
      const mock = { id: SCHEDULE_ID_SECONDARY, name: 'Updated Schedule', rotationType: 'weekly' }
      server.use(
        http.put(`${API_BASE}/on-call/schedules/${SCHEDULE_ID_SECONDARY}`, async ({ request: req }) => {
          const body = await req.json() as Record<string, unknown>
          expect(body.name).toBe('Updated Schedule')
          return HttpResponse.json(mock)
        })
      )
      const result = await api.updateOnCallSchedule(SCHEDULE_ID_SECONDARY, request as never)
      expect(result).toEqual(mock)
    })
  })

  describe('deleteOnCallSchedule', () => {
    it('sends DELETE for schedule', async () => {
      server.use(
        http.delete(`${API_BASE}/on-call/schedules/${SCHEDULE_ID_SECONDARY}`, () =>
          new HttpResponse(null, { status: 204 })
        )
      )
      await api.deleteOnCallSchedule(SCHEDULE_ID_SECONDARY)
    })
  })

  // ──── Current On-Call & Overrides ────

  describe('getCurrentOnCall', () => {
    it('fetches current on-call user for schedule', async () => {
      const mock = { userId: USER_ID_ON_CALL, userName: 'alice' }
      server.use(
        http.get(`${API_BASE}/on-call/schedules/${SCHEDULE_ID_PRIMARY}/current`, () =>
          HttpResponse.json(mock)
        )
      )
      const result = await api.getCurrentOnCall(SCHEDULE_ID_PRIMARY)
      expect(result).toEqual(mock)
    })
  })

  describe('createOverride', () => {
    it('sends POST to create override for schedule', async () => {
      const request = { userId: USER_ID_OVERRIDE, startTime: '2025-01-01T00:00:00Z', endTime: '2025-01-02T00:00:00Z' }
      const mock = { id: OVERRIDE_ID, scheduleId: SCHEDULE_ID_PRIMARY, ...request }
      server.use(
        http.post(`${API_BASE}/on-call/schedules/${SCHEDULE_ID_PRIMARY}/overrides`, async ({ request: req }) => {
          const body = await req.json() as Record<string, unknown>
          expect(body.userId).toBe(USER_ID_OVERRIDE)
          return HttpResponse.json(mock)
        })
      )
      const result = await api.createOverride(SCHEDULE_ID_PRIMARY, request as never)
      expect(result).toEqual(mock)
    })
  })

  describe('deleteOverride', () => {
    it('sends DELETE for override', async () => {
      server.use(
        http.delete(`${API_BASE}/on-call/overrides/${OVERRIDE_ID}`, () =>
          new HttpResponse(null, { status: 204 })
        )
      )
      await api.deleteOverride(OVERRIDE_ID)
    })
  })

  // ──── Escalation Policies ────

  describe('getEscalationPolicies', () => {
    it('fetches all escalation policies', async () => {
      const mock = [{ id: POLICY_ID_DEFAULT, name: 'Default', steps: [] }]
      server.use(
        http.get(`${API_BASE}/escalation-policies`, () => HttpResponse.json(mock))
      )
      const result = await api.getEscalationPolicies()
      expect(result).toEqual(mock)
    })
  })

  describe('getEscalationPolicy', () => {
    it('fetches single escalation policy', async () => {
      const mock = { id: POLICY_ID_URGENT, name: 'Urgent', steps: [{ delayMinutes: 5 }] }
      server.use(
        http.get(`${API_BASE}/escalation-policies/${POLICY_ID_URGENT}`, () => HttpResponse.json(mock))
      )
      const result = await api.getEscalationPolicy(POLICY_ID_URGENT)
      expect(result).toEqual(mock)
    })
  })

  describe('createEscalationPolicy', () => {
    it('sends POST to create policy', async () => {
      const request = { name: 'New Policy', steps: [] }
      const mock = { id: POLICY_ID_CREATED, ...request }
      server.use(
        http.post(`${API_BASE}/escalation-policies`, async ({ request: req }) => {
          const body = await req.json() as Record<string, unknown>
          expect(body.name).toBe('New Policy')
          return HttpResponse.json(mock)
        })
      )
      const result = await api.createEscalationPolicy(request as never)
      expect(result).toEqual(mock)
    })
  })

  describe('updateEscalationPolicy', () => {
    it('sends PUT to update policy', async () => {
      const request = { name: 'Updated Policy', steps: [{ delayMinutes: 10 }] }
      const mock = { id: POLICY_ID_CREATED, ...request }
      server.use(
        http.put(`${API_BASE}/escalation-policies/${POLICY_ID_CREATED}`, async ({ request: req }) => {
          const body = await req.json() as Record<string, unknown>
          expect(body.name).toBe('Updated Policy')
          return HttpResponse.json(mock)
        })
      )
      const result = await api.updateEscalationPolicy(POLICY_ID_CREATED, request as never)
      expect(result).toEqual(mock)
    })
  })

  describe('deleteEscalationPolicy', () => {
    it('sends DELETE for policy', async () => {
      server.use(
        http.delete(`${API_BASE}/escalation-policies/${POLICY_ID_DELETE}`, () =>
          new HttpResponse(null, { status: 204 })
        )
      )
      await api.deleteEscalationPolicy(POLICY_ID_DELETE)
    })
  })

  // ──── Alerts ────

  describe('getAlerts', () => {
    it('fetches alerts without filters', async () => {
      const mock = [{ id: ALERT_ID_PRIMARY, title: 'Server Down', status: 'TRIGGERED' }]
      server.use(
        http.get(`${API_BASE}/on-call/alerts`, () => HttpResponse.json(mock))
      )
      const result = await api.getAlerts()
      expect(result).toEqual(mock)
    })

    it('fetches alerts with filters', async () => {
      const mock = [{ id: ALERT_ID_SECONDARY, title: 'High CPU', status: 'ACKNOWLEDGED' }]
      server.use(
        http.get(`${API_BASE}/on-call/alerts`, ({ request }) => {
          const url = new URL(request.url)
          expect(url.searchParams.get('status')).toBe('ACKNOWLEDGED')
          expect(url.searchParams.get('priority')).toBe('P1')
          expect(url.searchParams.get('fromDate')).toBe('2025-01-01')
          expect(url.searchParams.get('toDate')).toBe('2025-01-31')
          return HttpResponse.json(mock)
        })
      )
      const result = await api.getAlerts({
        status: 'ACKNOWLEDGED',
        priority: 'P1',
        fromDate: '2025-01-01',
        toDate: '2025-01-31',
      })
      expect(result).toEqual(mock)
    })

    it('fetches alerts with multiple statuses', async () => {
      const mock = [
        { id: ALERT_ID_PRIMARY, title: 'Server Down', status: 'TRIGGERED' },
        { id: ALERT_ID_SECONDARY, title: 'High CPU', status: 'ACKNOWLEDGED' },
      ]
      server.use(
        http.get(`${API_BASE}/on-call/alerts`, ({ request }) => {
          const url = new URL(request.url)
          expect(url.searchParams.getAll('status')).toEqual(['TRIGGERED', 'ACKNOWLEDGED'])
          expect(url.searchParams.get('priority')).toBe('P0')
          return HttpResponse.json(mock)
        })
      )
      const result = await api.getAlerts({
        status: ['TRIGGERED', 'ACKNOWLEDGED'],
        priority: 'P0',
      })
      expect(result).toEqual(mock)
    })
  })

  describe('getAlert', () => {
    it('fetches single alert', async () => {
      const mock = { id: ALERT_ID_PRIMARY, title: 'Server Down', status: 'TRIGGERED', timeline: [] }
      server.use(
        http.get(`${API_BASE}/on-call/alerts/${ALERT_ID_PRIMARY}`, () => HttpResponse.json(mock))
      )
      const result = await api.getAlert(ALERT_ID_PRIMARY)
      expect(result).toEqual(mock)
    })
  })

  describe('getAlertTimeline', () => {
    it('fetches alert timeline', async () => {
      const mock = [{
        id: 'timeline-event-1',
        targetId: ALERT_ID_PRIMARY,
        eventType: 'TRIGGERED',
        createdAt: '2025-01-01T00:00:00Z',
      }]
      server.use(
        http.get(`${API_BASE}/on-call/alerts/${ALERT_ID_PRIMARY}/timeline`, () => HttpResponse.json(mock))
      )
      const result = await api.getAlertTimeline(ALERT_ID_PRIMARY)
      expect(result).toEqual(mock)
    })
  })

  describe('acknowledgeAlert', () => {
    it('sends POST to acknowledge', async () => {
      const mock = { message: 'Alert acknowledged' }
      server.use(
        http.post(`${API_BASE}/on-call/alerts/${ALERT_ID_PRIMARY}/acknowledge`, () =>
          HttpResponse.json(mock)
        )
      )
      const result = await api.acknowledgeAlert(ALERT_ID_PRIMARY)
      expect(result).toEqual(mock)
    })
  })

  describe('resolveAlert', () => {
    it('sends POST to resolve', async () => {
      const mock = { message: 'Alert resolved' }
      server.use(
        http.post(`${API_BASE}/on-call/alerts/${ALERT_ID_PRIMARY}/resolve`, () =>
          HttpResponse.json(mock)
        )
      )
      const result = await api.resolveAlert(ALERT_ID_PRIMARY)
      expect(result).toEqual(mock)
    })
  })

  describe('reassignAlert', () => {
    it('sends POST with toUserId', async () => {
      const mock = { id: ALERT_ID_PRIMARY, assignedTo: USER_ID_REASSIGN }
      server.use(
        http.post(`${API_BASE}/on-call/alerts/${ALERT_ID_PRIMARY}/reassign`, async ({ request }) => {
          const body = await request.json() as Record<string, unknown>
          expect(body.toUserId).toBe(USER_ID_REASSIGN)
          return HttpResponse.json(mock)
        })
      )
      const result = await api.reassignAlert(ALERT_ID_PRIMARY, USER_ID_REASSIGN)
      expect(result).toEqual(mock)
    })
  })

  describe('addAlertNote', () => {
    it('sends POST with note body', async () => {
      const mock = { type: 'NOTE', content: 'Investigating' }
      server.use(
        http.post(`${API_BASE}/on-call/alerts/${ALERT_ID_PRIMARY}/notes`, async ({ request }) => {
          const body = await request.json() as Record<string, unknown>
          expect(body.note).toBe('Investigating')
          return HttpResponse.json(mock)
        })
      )
      const result = await api.addAlertNote(ALERT_ID_PRIMARY, 'Investigating')
      expect(result).toEqual(mock)
    })
  })

  describe('viewAlert', () => {
    it('sends POST to mark alert as viewed', async () => {
      let requested = false
      server.use(
        http.post(`${API_BASE}/on-call/alerts/${ALERT_ID_PRIMARY}/view`, () => {
          requested = true
          return new HttpResponse(null, { status: 204 })
        })
      )
      await api.viewAlert(ALERT_ID_PRIMARY)
      expect(requested).toBe(true)
    })
  })

  describe('markAlertUnavailable', () => {
    it('sends POST to mark unavailable', async () => {
      let requested = false
      server.use(
        http.post(`${API_BASE}/on-call/alerts/${ALERT_ID_PRIMARY}/unavailable`, () => {
          requested = true
          return new HttpResponse(null, { status: 204 })
        })
      )
      await api.markAlertUnavailable(ALERT_ID_PRIMARY)
      expect(requested).toBe(true)
    })
  })

  // ──── Devices ────

  describe('registerDevice', () => {
    it('sends POST with device registration', async () => {
      const request = { token: 'fcm-token-123', platform: 'android' }
      const mock = { id: DEVICE_ID, token: 'fcm-token-123', platform: 'android' }
      server.use(
        http.post(`${API_BASE}/devices`, async ({ request: req }) => {
          const body = await req.json() as Record<string, unknown>
          expect(body.token).toBe('fcm-token-123')
          return HttpResponse.json(mock)
        })
      )
      const result = await api.registerDevice(request as never)
      expect(result).toEqual(mock)
    })
  })

  describe('unregisterDevice', () => {
    it('sends DELETE with encoded token', async () => {
      server.use(
        http.delete(`${API_BASE}/devices/:token`, ({ params }) => {
          expect(params.token).toBe('fcm-token-123')
          return new HttpResponse(null, { status: 204 })
        })
      )
      await api.unregisterDevice('fcm-token-123')
    })
  })

  // ──── Declare Incident ────

  describe('declareIncidentFromAlert', () => {
    it('sends POST to declare incident from alert', async () => {
      const data = { title: 'Outage', description: 'Full outage', severity: 'SEV-1' }
      const mock = { id: DECLARED_INCIDENT_ID }
      server.use(
        http.post(`${API_BASE}/on-call/alerts/${ALERT_ID_PRIMARY}/declare-incident`, async ({ request }) => {
          const body = await request.json() as Record<string, unknown>
          expect(body.title).toBe('Outage')
          expect(body.severity).toBe('SEV-1')
          return HttpResponse.json(mock)
        })
      )
      const result = await api.declareIncidentFromAlert(ALERT_ID_PRIMARY, data)
      expect(result).toEqual(mock)
    })
  })

  // ──── Native incident entitlement capabilities ────

  describe('getNativeIncidentCapabilities', () => {
    it('parses an enabled capability payload', async () => {
      server.use(
        http.get(`${API_BASE}/on-call/incident-response/capabilities`, () =>
          HttpResponse.json({
            enabled: true,
            entitlementEnabled: true,
            plan: 'TEAM',
            entitlementReason: null,
            quotas: {
              native_incidents: {limit: 250, used: 10, remaining: 240, exhausted: false},
            },
            externalProviderPassthroughAffected: false,
          })
        )
      )
      const result = await api.getNativeIncidentCapabilities()
      expect(result).toEqual({
        enabled: true,
        entitlementEnabled: true,
        plan: 'TEAM',
        entitlementReason: null,
        quotas: {
          native_incidents: {limit: 250, used: 10, remaining: 240, exhausted: false},
        },
        externalProviderPassthroughAffected: false,
      })
    })

    it('preserves entitlement reason while reporting disabled', async () => {
      server.use(
        http.get(`${API_BASE}/on-call/incident-response/capabilities`, () =>
          HttpResponse.json({
            enabled: false,
            entitlementEnabled: false,
            plan: 'FREE',
            entitlementReason: 'Upgrade the plan',
            externalProviderPassthroughAffected: false,
          })
        )
      )
      const result = await api.getNativeIncidentCapabilities()
      expect(result.enabled).toBe(false)
      expect(result.entitlementEnabled).toBe(false)
      expect(result.entitlementReason).toBe('Upgrade the plan')
    })

    it('fails closed and normalizes an unexpected payload', async () => {
      server.use(
        http.get(`${API_BASE}/on-call/incident-response/capabilities`, () =>
          HttpResponse.json({enabled: 'yes', state: 'SOMETHING_NEW'})
        )
      )
      const result = await api.getNativeIncidentCapabilities()
      expect(result).toEqual({
        enabled: false,
        entitlementEnabled: false,
        plan: 'UNKNOWN',
        entitlementReason: null,
        quotas: {},
        externalProviderPassthroughAffected: false,
      })
    })

    it('requires explicit entitlement for enabled capability responses', async () => {
      server.use(
        http.get(`${API_BASE}/on-call/incident-response/capabilities`, () =>
          HttpResponse.json({enabled: true, plan: 'TEAM'})
        )
      )
      const result = await api.getNativeIncidentCapabilities()
      expect(result.enabled).toBe(false)
      expect(result.entitlementEnabled).toBe(false)
    })

    it('fails closed when the plan entitlement is disabled', async () => {
      server.use(
        http.get(`${API_BASE}/on-call/incident-response/capabilities`, () =>
          HttpResponse.json({
            enabled: false,
            entitlementEnabled: false,
            plan: 'FREE',
            entitlementReason: 'Upgrade the plan',
            quotas: {},
          })
        )
      )
      const result = await api.getNativeIncidentCapabilities()
      expect(result.enabled).toBe(false)
      expect(result.entitlementEnabled).toBe(false)
      expect(result.entitlementReason).toBe('Upgrade the plan')
    })
  })

  // ──── On-Call Incidents ────

  describe('getOnCallIncidents', () => {
    it('fetches on-call incidents without filters', async () => {
      const mock = [{ id: ON_CALL_INCIDENT_ID_PRIMARY, title: 'Alert fired' }]
      server.use(
        http.get(`${API_BASE}/on-call/incidents`, () => HttpResponse.json(mock))
      )
      const result = await api.getOnCallIncidents()
      expect(result).toEqual(mock)
    })

    it('fetches on-call incidents with filters', async () => {
      const mock = [{ id: ON_CALL_INCIDENT_ID_SECONDARY, title: 'Disk full' }]
      server.use(
        http.get(`${API_BASE}/on-call/incidents`, ({ request }) => {
          const url = new URL(request.url)
          expect(url.searchParams.get('status')).toBe('TRIGGERED')
          expect(url.searchParams.get('severity')).toBe('SEV-2')
          return HttpResponse.json(mock)
        })
      )
      const result = await api.getOnCallIncidents({ status: 'TRIGGERED', severity: 'SEV-2' })
      expect(result).toEqual(mock)
    })
  })

  describe('getOnCallIncident', () => {
    it('fetches single on-call incident', async () => {
      const mock = { id: ON_CALL_INCIDENT_ID_PRIMARY, title: 'Memory leak', status: 'TRIGGERED' }
      server.use(
        http.get(`${API_BASE}/on-call/incidents/${ON_CALL_INCIDENT_ID_PRIMARY}`, () => HttpResponse.json(mock))
      )
      const result = await api.getOnCallIncident(ON_CALL_INCIDENT_ID_PRIMARY)
      expect(result).toEqual(mock)
    })
  })

  describe('incident update reminders', () => {
    it('publishes updates and controls reminder state', async () => {
      const update = {
        message: 'Mitigation is rolling out',
        customerImpact: 'Checkout requests are degraded',
        nextUpdateAt: '2026-08-25T01:00:00Z',
        expectedVersion: 4,
      }
      const request = {
        message: 'Please share the next update',
        dueAt: '2026-08-25T00:30:00Z',
        expectedVersion: 5,
      }
      const response = { id: ON_CALL_INCIDENT_ID_PRIMARY, status: 'ACTIVE' }
      server.use(
        http.post(
          `${API_BASE}/on-call/incidents/${ON_CALL_INCIDENT_ID_PRIMARY}/updates`,
          async ({ request: req }) => {
            expect(await req.json()).toEqual(update)
            return HttpResponse.json(response)
          }
        ),
        http.post(
          `${API_BASE}/on-call/incidents/${ON_CALL_INCIDENT_ID_PRIMARY}/update-requests`,
          async ({ request: req }) => {
            expect(await req.json()).toEqual(request)
            return HttpResponse.json(response)
          }
        ),
        http.post(
          `${API_BASE}/on-call/incidents/${ON_CALL_INCIDENT_ID_PRIMARY}/update-reminders/pause`,
          async ({ request: req }) => {
            expect(await req.json()).toEqual({ expectedVersion: 6 })
            return HttpResponse.json(response)
          }
        ),
        http.post(
          `${API_BASE}/on-call/incidents/${ON_CALL_INCIDENT_ID_PRIMARY}/update-reminders/resume`,
          async ({ request: req }) => {
            expect(await req.json()).toEqual({})
            return HttpResponse.json(response)
          }
        )
      )

      expect(await api.publishOnCallIncidentUpdate(ON_CALL_INCIDENT_ID_PRIMARY, update)).toEqual(response)
      expect(await api.requestOnCallIncidentUpdate(ON_CALL_INCIDENT_ID_PRIMARY, request)).toEqual(response)
      expect(
        await api.pauseOnCallIncidentUpdateReminders(ON_CALL_INCIDENT_ID_PRIMARY, { expectedVersion: 6 })
      ).toEqual(response)
      expect(await api.resumeOnCallIncidentUpdateReminders(ON_CALL_INCIDENT_ID_PRIMARY)).toEqual(response)
    })
  })

  describe('resolveOnCallIncident', () => {
    it('sends POST with optional note to resolve on-call incident', async () => {
      const mock = { id: ON_CALL_INCIDENT_ID_PRIMARY, status: 'RESOLVED' }
      server.use(
        http.post(`${API_BASE}/on-call/incidents/${ON_CALL_INCIDENT_ID_PRIMARY}/resolve`, async ({ request }) => {
          const body = await request.json() as Record<string, unknown>
          expect(body.note).toBe('Restarted workers')
          return HttpResponse.json(mock)
        })
      )
      const result = await api.resolveOnCallIncident(ON_CALL_INCIDENT_ID_PRIMARY, 'Restarted workers')
      expect(result).toEqual(mock)
    })
  })

  describe('getOnCallIncidentTimeline', () => {
    it('fetches on-call incident timeline', async () => {
      const mock = [{ type: 'ESCALATED', timestamp: '2025-06-01T12:00:00Z' }]
      server.use(
        http.get(`${API_BASE}/on-call/incidents/${ON_CALL_INCIDENT_ID_PRIMARY}/timeline`, () =>
          HttpResponse.json(mock)
        )
      )
      const result = await api.getOnCallIncidentTimeline(ON_CALL_INCIDENT_ID_PRIMARY)
      expect(result).toEqual(mock)
    })
  })

  describe('addOnCallIncidentNote', () => {
    it('sends POST with note', async () => {
      const mock = { message: 'Note added' }
      server.use(
        http.post(`${API_BASE}/on-call/incidents/${ON_CALL_INCIDENT_ID_PRIMARY}/notes`, async ({ request }) => {
          const body = await request.json() as Record<string, unknown>
          expect(body.note).toBe('Looking into it')
          return HttpResponse.json(mock)
        })
      )
      const result = await api.addOnCallIncidentNote(ON_CALL_INCIDENT_ID_PRIMARY, 'Looking into it')
      expect(result).toEqual(mock)
    })
  })

  // ──── Incident modeling: declaration, timeline, responders, sources, config ────

  const INCIDENT_ID = ON_CALL_INCIDENT_ID_PRIMARY
  const EVENT_ID = 'e1111111-1111-4111-8111-111111111111'
  const ROLE_ID = 'e2222222-2222-4222-8222-222222222222'
  const USER_ID = 'e3333333-3333-4333-8333-333333333333'
  const SOURCE_ID = 'e4444444-4444-4444-8444-444444444444'
  const TYPE_ID = 'e5555555-5555-4555-8555-555555555555'
  const FIELD_ID = 'e6666666-6666-4666-8666-666666666666'
  const FORM_ID = 'e7777777-7777-4777-8777-777777777777'

  describe('declareOnCallIncident', () => {
    it('POSTs the full declaration payload', async () => {
      const mock = { id: INCIDENT_ID, title: 'Global outage', status: 'ACTIVE' }
      server.use(
        http.post(`${API_BASE}/on-call/incidents`, async ({ request }) => {
          const body = await request.json() as Record<string, unknown>
          expect(body.title).toBe('Global outage')
          expect(body.mode).toBe('RETROSPECTIVE')
          expect(body.visibility).toBe('PRIVATE')
          expect(body.initialStatus).toBe('TRIAGE')
          expect(body.incidentTypeId).toBe(TYPE_ID)
          expect(body.fields).toEqual({ region: 'us-east' })
          return HttpResponse.json(mock, { status: 201 })
        })
      )
      const result = await api.declareOnCallIncident({
        title: 'Global outage',
        severity: 'SEV-1',
        mode: 'RETROSPECTIVE',
        visibility: 'PRIVATE',
        initialStatus: 'TRIAGE',
        incidentTypeId: TYPE_ID,
        fields: { region: 'us-east' },
      })
      expect(result).toEqual(mock)
    })
  })

  describe('declareIncidentFromAlert with full payload', () => {
    it('passes mode, visibility, and fields through to the alert declare path', async () => {
      const mock = { id: INCIDENT_ID }
      server.use(
        http.post(`${API_BASE}/on-call/alerts/${ALERT_ID_PRIMARY}/declare-incident`, async ({ request }) => {
          const body = await request.json() as Record<string, unknown>
          expect(body.mode).toBe('LIVE')
          expect(body.visibility).toBe('ORGANIZATION')
          expect(body.fields).toEqual({ impact: 'high' })
          return HttpResponse.json(mock, { status: 201 })
        })
      )
      const result = await api.declareIncidentFromAlert(ALERT_ID_PRIMARY, {
        title: 'From alert',
        severity: 'SEV-2',
        mode: 'LIVE',
        visibility: 'ORGANIZATION',
        fields: { impact: 'high' },
      })
      expect(result).toEqual(mock)
    })
  })

  describe('getOnCallIncidentTimeline with filters', () => {
    it('serialises event type, provenance, visibility, and includeDeleted', async () => {
      const mock = [{ id: EVENT_ID, eventType: 'DECLARED', provenance: 'REST', visibility: 'ORGANIZATION' }]
      server.use(
        http.get(`${API_BASE}/on-call/incidents/${INCIDENT_ID}/timeline`, ({ request }) => {
          const url = new URL(request.url)
          expect(url.searchParams.getAll('eventType')).toEqual(['DECLARED', 'NOTE_ADDED'])
          expect(url.searchParams.getAll('provenance')).toEqual(['REST'])
          expect(url.searchParams.getAll('visibility')).toEqual(['PRIVATE'])
          expect(url.searchParams.get('includeDeleted')).toBe('true')
          return HttpResponse.json(mock)
        })
      )
      const result = await api.getOnCallIncidentTimeline(INCIDENT_ID, {
        eventType: ['DECLARED', 'NOTE_ADDED'],
        provenance: ['REST'],
        visibility: ['PRIVATE'],
        includeDeleted: true,
      })
      expect(result).toEqual(mock)
    })
  })

  describe('exportOnCallIncidentTimeline', () => {
    it('GETs the timeline export', async () => {
      const mock = { incidentId: INCIDENT_ID, exportedAt: '2026-06-05T00:00:00Z', events: [] }
      server.use(
        http.get(`${API_BASE}/on-call/incidents/${INCIDENT_ID}/timeline/export`, () => HttpResponse.json(mock))
      )
      expect(await api.exportOnCallIncidentTimeline(INCIDENT_ID)).toEqual(mock)
    })
  })

  describe('getIncidentTimelineRevisions', () => {
    it('GETs revisions for an event', async () => {
      const mock = [{ id: 'rev-1', revision: 1, action: 'EDIT' }]
      server.use(
        http.get(`${API_BASE}/on-call/incidents/${INCIDENT_ID}/timeline/${EVENT_ID}/revisions`, () =>
          HttpResponse.json(mock)
        )
      )
      expect(await api.getIncidentTimelineRevisions(INCIDENT_ID, EVENT_ID)).toEqual(mock)
    })
  })

  describe('editIncidentTimelineEvent', () => {
    it('PATCHes the event with corrections and a reason', async () => {
      const mock = { id: EVENT_ID, eventType: 'STATUS_CHANGED' }
      server.use(
        http.patch(`${API_BASE}/on-call/incidents/${INCIDENT_ID}/timeline/${EVENT_ID}`, async ({ request }) => {
          const body = await request.json() as Record<string, unknown>
          expect(body.eventType).toBe('STATUS_CHANGED')
          expect(body.visibility).toBe('PARTICIPANTS')
          expect(body.reason).toBe('correcting the record')
          return HttpResponse.json(mock)
        })
      )
      const result = await api.editIncidentTimelineEvent(INCIDENT_ID, EVENT_ID, {
        eventType: 'STATUS_CHANGED',
        visibility: 'PARTICIPANTS',
        reason: 'correcting the record',
      })
      expect(result).toEqual(mock)
    })
  })

  describe('annotateIncidentTimelineEvent', () => {
    it('POSTs an annotation', async () => {
      const mock = { id: EVENT_ID, annotation: 'context' }
      server.use(
        http.post(`${API_BASE}/on-call/incidents/${INCIDENT_ID}/timeline/${EVENT_ID}/annotation`, async ({ request }) => {
          const body = await request.json() as Record<string, unknown>
          expect(body.annotation).toBe('context')
          return HttpResponse.json(mock)
        })
      )
      expect(
        await api.annotateIncidentTimelineEvent(INCIDENT_ID, EVENT_ID, { annotation: 'context' })
      ).toEqual(mock)
    })
  })

  describe('reorderIncidentTimeline', () => {
    it('POSTs the ordered event ids', async () => {
      const mock = [{ id: EVENT_ID }]
      server.use(
        http.post(`${API_BASE}/on-call/incidents/${INCIDENT_ID}/timeline/reorder`, async ({ request }) => {
          const body = await request.json() as Record<string, unknown>
          expect(body.eventIds).toEqual([EVENT_ID, 'e1111111-1111-4111-8111-111111111112'])
          return HttpResponse.json(mock)
        })
      )
      const result = await api.reorderIncidentTimeline(INCIDENT_ID, [
        EVENT_ID,
        'e1111111-1111-4111-8111-111111111112',
      ])
      expect(result).toEqual(mock)
    })
  })

  describe('deleteIncidentTimelineEvent', () => {
    it('DELETEs the event with a reason', async () => {
      const mock = { message: 'Timeline event deleted' }
      server.use(
        http.delete(`${API_BASE}/on-call/incidents/${INCIDENT_ID}/timeline/${EVENT_ID}`, async ({ request }) => {
          const body = await request.json() as Record<string, unknown>
          expect(body.reason).toBe('duplicate')
          return HttpResponse.json(mock)
        })
      )
      expect(await api.deleteIncidentTimelineEvent(INCIDENT_ID, EVENT_ID, 'duplicate')).toEqual(mock)
    })
  })

  describe('restoreIncidentTimelineEvent', () => {
    it('POSTs a restore', async () => {
      const mock = { message: 'Timeline event restored' }
      server.use(
        http.post(`${API_BASE}/on-call/incidents/${INCIDENT_ID}/timeline/${EVENT_ID}/restore`, () =>
          HttpResponse.json(mock)
        )
      )
      expect(await api.restoreIncidentTimelineEvent(INCIDENT_ID, EVENT_ID)).toEqual(mock)
    })
  })

  describe('incident sources', () => {
    it('lists sources', async () => {
      const mock = [{ id: SOURCE_ID, sourceType: 'URL', sourceKey: 'https://x', metadata: {}, createdAt: '' }]
      server.use(
        http.get(`${API_BASE}/on-call/incidents/${INCIDENT_ID}/sources`, () => HttpResponse.json(mock))
      )
      expect(await api.getIncidentSources(INCIDENT_ID)).toEqual(mock)
    })

    it('links a source and returns the updated list', async () => {
      const mock = [{ id: SOURCE_ID, sourceType: 'URL', sourceKey: 'https://runbook', metadata: {}, createdAt: '' }]
      server.use(
        http.post(`${API_BASE}/on-call/incidents/${INCIDENT_ID}/sources`, async ({ request }) => {
          const body = await request.json() as Record<string, unknown>
          expect(body.sourceType).toBe('URL')
          expect(body.sourceUrl).toBe('https://runbook')
          return HttpResponse.json(mock, { status: 201 })
        })
      )
      const result = await api.linkIncidentSource(INCIDENT_ID, {
        sourceType: 'URL',
        sourceKey: 'https://runbook',
        sourceUrl: 'https://runbook',
      })
      expect(result).toEqual(mock)
    })

    it('unlinks a source', async () => {
      const mock = { message: 'Incident source removed' }
      server.use(
        http.delete(`${API_BASE}/on-call/incidents/${INCIDENT_ID}/sources/${SOURCE_ID}`, () =>
          HttpResponse.json(mock)
        )
      )
      expect(await api.unlinkIncidentSource(INCIDENT_ID, SOURCE_ID)).toEqual(mock)
    })
  })

  describe('incident roles and participants', () => {
    it('lists role assignments', async () => {
      const mock = [{ id: 'a1', assigneeUserId: USER_ID }]
      server.use(
        http.get(`${API_BASE}/on-call/incidents/${INCIDENT_ID}/roles`, () => HttpResponse.json(mock))
      )
      expect(await api.getIncidentRoleAssignments(INCIDENT_ID)).toEqual(mock)
    })

    it('assigns a role with expected version', async () => {
      const mock = [{ id: 'a1', assigneeUserId: USER_ID }]
      server.use(
        http.post(`${API_BASE}/on-call/incidents/${INCIDENT_ID}/roles/${ROLE_ID}/assign`, async ({ request }) => {
          const body = await request.json() as Record<string, unknown>
          expect(body.userId).toBe(USER_ID)
          expect(body.expectedVersion).toBe(4)
          return HttpResponse.json(mock)
        })
      )
      expect(await api.assignIncidentRole(INCIDENT_ID, ROLE_ID, USER_ID, 4)).toEqual(mock)
    })

    it('claims a role', async () => {
      const mock = [{ id: 'a1' }]
      server.use(
        http.post(`${API_BASE}/on-call/incidents/${INCIDENT_ID}/roles/${ROLE_ID}/claim`, async ({ request }) => {
          const body = await request.json() as Record<string, unknown>
          expect(body.expectedVersion).toBe(2)
          return HttpResponse.json(mock)
        })
      )
      expect(await api.claimIncidentRole(INCIDENT_ID, ROLE_ID, 2)).toEqual(mock)
    })

    it('unassigns a role', async () => {
      const mock = { message: 'Incident role unassigned' }
      server.use(
        http.delete(`${API_BASE}/on-call/incidents/${INCIDENT_ID}/roles/${ROLE_ID}`, () =>
          HttpResponse.json(mock)
        )
      )
      expect(await api.unassignIncidentRole(INCIDENT_ID, ROLE_ID)).toEqual(mock)
    })

    it('hands over a role with a note', async () => {
      const mock = [{ id: 'a2' }]
      server.use(
        http.post(`${API_BASE}/on-call/incidents/${INCIDENT_ID}/roles/${ROLE_ID}/handover`, async ({ request }) => {
          const body = await request.json() as Record<string, unknown>
          expect(body.userId).toBe(USER_ID)
          expect(body.note).toBe('taking over')
          return HttpResponse.json(mock)
        })
      )
      expect(
        await api.handoverIncidentRole(INCIDENT_ID, ROLE_ID, { userId: USER_ID, note: 'taking over' })
      ).toEqual(mock)
    })

    it('lists participants', async () => {
      const mock = [{ id: 'p1', userId: USER_ID, type: 'PARTICIPANT' }]
      server.use(
        http.get(`${API_BASE}/on-call/incidents/${INCIDENT_ID}/participants`, () => HttpResponse.json(mock))
      )
      expect(await api.getIncidentParticipants(INCIDENT_ID)).toEqual(mock)
    })

    it('joins and observes an incident', async () => {
      const joined = [{ id: 'p1', userId: USER_ID, type: 'PARTICIPANT' }]
      const observing = [{ id: 'p1', userId: USER_ID, type: 'OBSERVER' }]
      server.use(
        http.post(`${API_BASE}/on-call/incidents/${INCIDENT_ID}/participants`, () => HttpResponse.json(joined)),
        http.post(`${API_BASE}/on-call/incidents/${INCIDENT_ID}/observers`, () => HttpResponse.json(observing))
      )
      expect(await api.joinIncident(INCIDENT_ID, { expectedVersion: 1 })).toEqual(joined)
      expect(await api.observeIncident(INCIDENT_ID)).toEqual(observing)
    })

    it('leaves an incident', async () => {
      const mock = { message: 'Incident participant removed' }
      server.use(
        http.delete(`${API_BASE}/on-call/incidents/${INCIDENT_ID}/participants/${USER_ID}`, () =>
          HttpResponse.json(mock)
        )
      )
      expect(await api.leaveIncident(INCIDENT_ID, USER_ID)).toEqual(mock)
    })
  })

  describe('incident configuration', () => {
    it('lists and creates incident types', async () => {
      server.use(
        http.get(`${API_BASE}/on-call/incident-configuration/types`, () =>
          HttpResponse.json([{ id: TYPE_ID, key: 'security', name: 'Security', version: 1, enabled: true }])
        ),
        http.post(`${API_BASE}/on-call/incident-configuration/types`, async ({ request }) => {
          const body = await request.json() as Record<string, unknown>
          expect(body.key).toBe('security')
          return HttpResponse.json({ id: TYPE_ID, key: 'security', name: 'Security', version: 1, enabled: true }, { status: 201 })
        })
      )
      expect((await api.getIncidentTypes()).length).toBe(1)
      const created = await api.createIncidentType({ key: 'security', name: 'Security' })
      expect(created.id).toBe(TYPE_ID)
    })

    it('lists and creates custom fields with options', async () => {
      server.use(
        http.get(`${API_BASE}/on-call/incident-configuration/fields`, () =>
          HttpResponse.json([{ id: FIELD_ID, key: 'region', name: 'Region', version: 1, valueType: 'SELECT', options: [] }])
        ),
        http.post(`${API_BASE}/on-call/incident-configuration/fields`, async ({ request }) => {
          const body = await request.json() as Record<string, unknown>
          expect(body.valueType).toBe('SELECT')
          expect((body.options as unknown[]).length).toBe(1)
          return HttpResponse.json({ id: FIELD_ID, key: 'region', name: 'Region', version: 1, valueType: 'SELECT', options: [] }, { status: 201 })
        })
      )
      expect((await api.getIncidentCustomFields()).length).toBe(1)
      const created = await api.createIncidentCustomField({
        key: 'region',
        name: 'Region',
        valueType: 'SELECT',
        options: [{ value: 'us', label: 'US', position: 0 }],
      })
      expect(created.id).toBe(FIELD_ID)
    })

    it('lists forms by stage and creates a form', async () => {
      server.use(
        http.get(`${API_BASE}/on-call/incident-configuration/forms`, ({ request }) => {
          const url = new URL(request.url)
          expect(url.searchParams.get('stage')).toBe('DECLARATION')
          return HttpResponse.json([{ id: FORM_ID, stage: 'DECLARATION', name: 'Declare', version: 1, fields: [] }])
        }),
        http.post(`${API_BASE}/on-call/incident-configuration/forms`, async ({ request }) => {
          const body = await request.json() as Record<string, unknown>
          expect(body.stage).toBe('DECLARATION')
          return HttpResponse.json({ id: FORM_ID, stage: 'DECLARATION', name: 'Declare', version: 1, fields: [] }, { status: 201 })
        })
      )
      expect((await api.getIncidentForms('DECLARATION')).length).toBe(1)
      const created = await api.createIncidentForm({
        stage: 'DECLARATION',
        name: 'Declare',
        fields: [{ fieldId: FIELD_ID, position: 0 }],
      })
      expect(created.id).toBe(FORM_ID)
    })

    it('lists and creates responder roles', async () => {
      server.use(
        http.get(`${API_BASE}/on-call/incident-configuration/roles`, () =>
          HttpResponse.json([{ id: ROLE_ID, key: 'scribe', name: 'Scribe', version: 1, responsibilities: ['record'], required: false, default: false }])
        ),
        http.post(`${API_BASE}/on-call/incident-configuration/roles`, async ({ request }) => {
          const body = await request.json() as Record<string, unknown>
          expect(body.responsibilities).toEqual(['record'])
          return HttpResponse.json({ id: ROLE_ID, key: 'scribe', name: 'Scribe', version: 1, responsibilities: ['record'], required: false, default: false }, { status: 201 })
        })
      )
      expect((await api.getIncidentRoles()).length).toBe(1)
      const created = await api.createIncidentRole({ key: 'scribe', name: 'Scribe', responsibilities: ['record'] })
      expect(created.id).toBe(ROLE_ID)
    })
  })

  // ──── Phone Number ────

  describe('updatePhoneNumber', () => {
    it('sends PUT with phone number', async () => {
      const mock = { message: 'Phone number updated' }
      server.use(
        http.put(`${API_BASE}/user/phone-number`, async ({ request }) => {
          const body = await request.json() as Record<string, unknown>
          expect(body.phoneNumber).toBe('+15551234567')
          return HttpResponse.json(mock)
        })
      )
      const result = await api.updatePhoneNumber('+15551234567')
      expect(result).toEqual(mock)
    })
  })

  describe('deletePhoneNumber', () => {
    it('sends DELETE to remove phone number', async () => {
      const mock = { message: 'Phone number deleted' }
      server.use(
        http.delete(`${API_BASE}/user/phone-number`, () =>
          HttpResponse.json(mock)
        )
      )
      const result = await api.deletePhoneNumber()
      expect(result).toEqual(mock)
    })
  })

  // ──── Caller Number ────

  describe('getCallerNumber', () => {
    it('fetches the caller number', async () => {
      const mock = { phoneNumber: '+18005551234' }
      server.use(
        http.get(`${API_BASE}/on-call/caller-number`, () =>
          HttpResponse.json(mock)
        )
      )
      const result = await api.getCallerNumber()
      expect(result).toEqual(mock)
    })
  })

  // ──── On-Call Contact ────

  describe('getOnCallContact', () => {
    it('fetches on-call contact settings', async () => {
      const mock = { phoneNumber: '+15551234567', consentAccepted: true, consentVersion: '1.0' }
      server.use(
        http.get(`${API_BASE}/user/on-call-contact`, () =>
          HttpResponse.json(mock)
        )
      )
      const result = await api.getOnCallContact()
      expect(result).toEqual(mock)
    })
  })

  describe('updateOnCallContact', () => {
    it('sends PUT with contact settings', async () => {
      const request = { phoneNumber: '+15559876543', consentAccepted: true, consentVersion: '1.0' }
      const mock = { message: 'Contact updated' }
      server.use(
        http.put(`${API_BASE}/user/on-call-contact`, async ({ request: req }) => {
          const body = await req.json() as Record<string, unknown>
          expect(body.phoneNumber).toBe('+15559876543')
          expect(body.consentAccepted).toBe(true)
          return HttpResponse.json(mock)
        })
      )
      const result = await api.updateOnCallContact(request)
      expect(result).toEqual(mock)
    })
  })

  describe('deleteOnCallContact', () => {
    it('sends DELETE to remove on-call contact', async () => {
      const mock = { message: 'Contact deleted' }
      server.use(
        http.delete(`${API_BASE}/user/on-call-contact`, () =>
          HttpResponse.json(mock)
        )
      )
      const result = await api.deleteOnCallContact()
      expect(result).toEqual(mock)
    })
  })
})
