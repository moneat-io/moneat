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

describe('onCallMethods', () => {
  beforeEach(() => {
    localStorage.clear()
    sessionStorage.clear()
    sessionStorage.setItem('authenticated', 'true')
  })

  // ──── Priorities ────

  describe('getPriorities', () => {
    it('fetches priorities list', async () => {
      const mock = [{ id: 1, level: 'P1', label: 'Critical', color: '#ff0000' }]
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
      const mock = [{ id: 1, level: 'P1', label: 'Critical', color: '#ff0000' }]
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
      const mock = [{ id: 1, name: 'Primary', rotationType: 'weekly' }]
      server.use(
        http.get(`${API_BASE}/on-call/schedules`, () => HttpResponse.json(mock))
      )
      const result = await api.getOnCallSchedules()
      expect(result).toEqual(mock)
    })
  })

  describe('getOnCallSchedule', () => {
    it('fetches a single schedule by id', async () => {
      const mock = { id: 5, name: 'Secondary', rotationType: 'daily' }
      server.use(
        http.get(`${API_BASE}/on-call/schedules/5`, () => HttpResponse.json(mock))
      )
      const result = await api.getOnCallSchedule(5)
      expect(result).toEqual(mock)
    })
  })

  describe('createOnCallSchedule', () => {
    it('sends POST to create schedule', async () => {
      const request = { name: 'New Schedule', rotationType: 'weekly' }
      const mock = { id: 10, ...request }
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
      const mock = { id: 3, name: 'Updated Schedule', rotationType: 'weekly' }
      server.use(
        http.put(`${API_BASE}/on-call/schedules/3`, async ({ request: req }) => {
          const body = await req.json() as Record<string, unknown>
          expect(body.name).toBe('Updated Schedule')
          return HttpResponse.json(mock)
        })
      )
      const result = await api.updateOnCallSchedule(3, request as never)
      expect(result).toEqual(mock)
    })
  })

  describe('deleteOnCallSchedule', () => {
    it('sends DELETE for schedule', async () => {
      server.use(
        http.delete(`${API_BASE}/on-call/schedules/3`, () =>
          new HttpResponse(null, { status: 204 })
        )
      )
      await api.deleteOnCallSchedule(3)
    })
  })

  // ──── Current On-Call & Overrides ────

  describe('getCurrentOnCall', () => {
    it('fetches current on-call user for schedule', async () => {
      const mock = { userId: 42, userName: 'alice' }
      server.use(
        http.get(`${API_BASE}/on-call/schedules/7/current`, () =>
          HttpResponse.json(mock)
        )
      )
      const result = await api.getCurrentOnCall(7)
      expect(result).toEqual(mock)
    })
  })

  describe('createOverride', () => {
    it('sends POST to create override for schedule', async () => {
      const request = { userId: 99, startTime: '2025-01-01T00:00:00Z', endTime: '2025-01-02T00:00:00Z' }
      const mock = { id: 1, scheduleId: 7, ...request }
      server.use(
        http.post(`${API_BASE}/on-call/schedules/7/overrides`, async ({ request: req }) => {
          const body = await req.json() as Record<string, unknown>
          expect(body.userId).toBe(99)
          return HttpResponse.json(mock)
        })
      )
      const result = await api.createOverride(7, request as never)
      expect(result).toEqual(mock)
    })
  })

  describe('deleteOverride', () => {
    it('sends DELETE for override', async () => {
      server.use(
        http.delete(`${API_BASE}/on-call/overrides/15`, () =>
          new HttpResponse(null, { status: 204 })
        )
      )
      await api.deleteOverride(15)
    })
  })

  // ──── Escalation Policies ────

  describe('getEscalationPolicies', () => {
    it('fetches all escalation policies', async () => {
      const mock = [{ id: 1, name: 'Default', steps: [] }]
      server.use(
        http.get(`${API_BASE}/escalation-policies`, () => HttpResponse.json(mock))
      )
      const result = await api.getEscalationPolicies()
      expect(result).toEqual(mock)
    })
  })

  describe('getEscalationPolicy', () => {
    it('fetches single escalation policy', async () => {
      const mock = { id: 2, name: 'Urgent', steps: [{ delayMinutes: 5 }] }
      server.use(
        http.get(`${API_BASE}/escalation-policies/2`, () => HttpResponse.json(mock))
      )
      const result = await api.getEscalationPolicy(2)
      expect(result).toEqual(mock)
    })
  })

  describe('createEscalationPolicy', () => {
    it('sends POST to create policy', async () => {
      const request = { name: 'New Policy', steps: [] }
      const mock = { id: 3, ...request }
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
      const mock = { id: 3, ...request }
      server.use(
        http.put(`${API_BASE}/escalation-policies/3`, async ({ request: req }) => {
          const body = await req.json() as Record<string, unknown>
          expect(body.name).toBe('Updated Policy')
          return HttpResponse.json(mock)
        })
      )
      const result = await api.updateEscalationPolicy(3, request as never)
      expect(result).toEqual(mock)
    })
  })

  describe('deleteEscalationPolicy', () => {
    it('sends DELETE for policy', async () => {
      server.use(
        http.delete(`${API_BASE}/escalation-policies/4`, () =>
          new HttpResponse(null, { status: 204 })
        )
      )
      await api.deleteEscalationPolicy(4)
    })
  })

  // ──── Incidents ────

  describe('getIncidents', () => {
    it('fetches incidents without filters', async () => {
      const mock = [{ id: 1, title: 'Server Down', status: 'TRIGGERED' }]
      server.use(
        http.get(`${API_BASE}/on-call/alerts`, () => HttpResponse.json(mock))
      )
      const result = await api.getIncidents()
      expect(result).toEqual(mock)
    })

    it('fetches incidents with filters', async () => {
      const mock = [{ id: 2, title: 'High CPU', status: 'ACKNOWLEDGED' }]
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
      const result = await api.getIncidents({
        status: 'ACKNOWLEDGED',
        priority: 'P1',
        fromDate: '2025-01-01',
        toDate: '2025-01-31',
      })
      expect(result).toEqual(mock)
    })

    it('fetches incidents with multiple statuses', async () => {
      const mock = [
        { id: 1, title: 'Server Down', status: 'TRIGGERED' },
        { id: 2, title: 'High CPU', status: 'ACKNOWLEDGED' },
      ]
      server.use(
        http.get(`${API_BASE}/on-call/alerts`, ({ request }) => {
          const url = new URL(request.url)
          expect(url.searchParams.getAll('status')).toEqual(['TRIGGERED', 'ACKNOWLEDGED'])
          expect(url.searchParams.get('priority')).toBe('P0')
          return HttpResponse.json(mock)
        })
      )
      const result = await api.getIncidents({
        status: ['TRIGGERED', 'ACKNOWLEDGED'],
        priority: 'P0',
      })
      expect(result).toEqual(mock)
    })
  })

  describe('getIncident', () => {
    it('fetches single incident', async () => {
      const mock = { id: 1, title: 'Server Down', status: 'TRIGGERED', timeline: [] }
      server.use(
        http.get(`${API_BASE}/on-call/alerts/1`, () => HttpResponse.json(mock))
      )
      const result = await api.getIncident(1)
      expect(result).toEqual(mock)
    })
  })

  describe('getIncidentTimeline', () => {
    it('fetches incident timeline', async () => {
      const mock = [{ type: 'TRIGGERED', timestamp: '2025-01-01T00:00:00Z' }]
      server.use(
        http.get(`${API_BASE}/on-call/alerts/1/timeline`, () => HttpResponse.json(mock))
      )
      const result = await api.getIncidentTimeline(1)
      expect(result).toEqual(mock)
    })
  })

  describe('acknowledgeIncident', () => {
    it('sends POST to acknowledge', async () => {
      const mock = { id: 1, status: 'ACKNOWLEDGED' }
      server.use(
        http.post(`${API_BASE}/on-call/alerts/1/acknowledge`, () =>
          HttpResponse.json(mock)
        )
      )
      const result = await api.acknowledgeIncident(1)
      expect(result).toEqual(mock)
    })
  })

  describe('resolveIncident', () => {
    it('sends POST to resolve', async () => {
      const mock = { id: 1, status: 'RESOLVED' }
      server.use(
        http.post(`${API_BASE}/on-call/alerts/1/resolve`, () =>
          HttpResponse.json(mock)
        )
      )
      const result = await api.resolveIncident(1)
      expect(result).toEqual(mock)
    })
  })

  describe('reassignIncident', () => {
    it('sends POST with toUserId', async () => {
      const mock = { id: 1, assignedTo: 50 }
      server.use(
        http.post(`${API_BASE}/on-call/alerts/1/reassign`, async ({ request }) => {
          const body = await request.json() as Record<string, unknown>
          expect(body.toUserId).toBe(50)
          return HttpResponse.json(mock)
        })
      )
      const result = await api.reassignIncident(1, 50)
      expect(result).toEqual(mock)
    })
  })

  describe('addIncidentNote', () => {
    it('sends POST with note body', async () => {
      const mock = { type: 'NOTE', content: 'Investigating' }
      server.use(
        http.post(`${API_BASE}/on-call/alerts/1/notes`, async ({ request }) => {
          const body = await request.json() as Record<string, unknown>
          expect(body.note).toBe('Investigating')
          return HttpResponse.json(mock)
        })
      )
      const result = await api.addIncidentNote(1, 'Investigating')
      expect(result).toEqual(mock)
    })
  })

  describe('viewIncident', () => {
    it('sends POST to mark incident as viewed', async () => {
      server.use(
        http.post(`${API_BASE}/on-call/alerts/1/view`, () =>
          new HttpResponse(null, { status: 204 })
        )
      )
      await api.viewIncident(1)
    })
  })

  describe('markUnavailable', () => {
    it('sends POST to mark unavailable', async () => {
      server.use(
        http.post(`${API_BASE}/on-call/alerts/1/unavailable`, () =>
          new HttpResponse(null, { status: 204 })
        )
      )
      await api.markUnavailable(1)
    })
  })

  // ──── Devices ────

  describe('registerDevice', () => {
    it('sends POST with device registration', async () => {
      const request = { token: 'fcm-token-123', platform: 'android' }
      const mock = { id: 1, token: 'fcm-token-123', platform: 'android' }
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

  describe('declareIncident', () => {
    it('sends POST to declare incident from alert', async () => {
      const data = { title: 'Outage', description: 'Full outage', severity: 'SEV-1' }
      const mock = { id: 42 }
      server.use(
        http.post(`${API_BASE}/on-call/alerts/10/declare-incident`, async ({ request }) => {
          const body = await request.json() as Record<string, unknown>
          expect(body.title).toBe('Outage')
          expect(body.severity).toBe('SEV-1')
          return HttpResponse.json(mock)
        })
      )
      const result = await api.declareIncident(10, data)
      expect(result).toEqual(mock)
    })
  })

  // ──── On-Call Incidents ────

  describe('getOnCallIncidents', () => {
    it('fetches on-call incidents without filters', async () => {
      const mock = [{ id: 1, title: 'Alert fired' }]
      server.use(
        http.get(`${API_BASE}/on-call/incidents`, () => HttpResponse.json(mock))
      )
      const result = await api.getOnCallIncidents()
      expect(result).toEqual(mock)
    })

    it('fetches on-call incidents with filters', async () => {
      const mock = [{ id: 2, title: 'Disk full' }]
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
      const mock = { id: 5, title: 'Memory leak', status: 'TRIGGERED' }
      server.use(
        http.get(`${API_BASE}/on-call/incidents/5`, () => HttpResponse.json(mock))
      )
      const result = await api.getOnCallIncident(5)
      expect(result).toEqual(mock)
    })
  })

  describe('resolveOnCallIncident', () => {
    it('sends POST with optional note to resolve on-call incident', async () => {
      const mock = { id: 5, status: 'RESOLVED' }
      server.use(
        http.post(`${API_BASE}/on-call/incidents/5/resolve`, async ({ request }) => {
          const body = await request.json() as Record<string, unknown>
          expect(body.note).toBe('Restarted workers')
          return HttpResponse.json(mock)
        })
      )
      const result = await api.resolveOnCallIncident(5, 'Restarted workers')
      expect(result).toEqual(mock)
    })
  })

  describe('getOnCallIncidentTimeline', () => {
    it('fetches on-call incident timeline', async () => {
      const mock = [{ type: 'ESCALATED', timestamp: '2025-06-01T12:00:00Z' }]
      server.use(
        http.get(`${API_BASE}/on-call/incidents/5/timeline`, () =>
          HttpResponse.json(mock)
        )
      )
      const result = await api.getOnCallIncidentTimeline(5)
      expect(result).toEqual(mock)
    })
  })

  describe('addOnCallIncidentNote', () => {
    it('sends POST with note', async () => {
      const mock = { message: 'Note added' }
      server.use(
        http.post(`${API_BASE}/on-call/incidents/5/notes`, async ({ request }) => {
          const body = await request.json() as Record<string, unknown>
          expect(body.note).toBe('Looking into it')
          return HttpResponse.json(mock)
        })
      )
      const result = await api.addOnCallIncidentNote(5, 'Looking into it')
      expect(result).toEqual(mock)
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
