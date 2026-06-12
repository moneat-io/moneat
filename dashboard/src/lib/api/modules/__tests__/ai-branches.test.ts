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

import { describe, it, expect, beforeEach, vi } from 'vitest'
import { http, HttpResponse } from 'msw'
import { server } from '@/test/mocks/server'
import { api } from '@/lib/api'

const API_BASE = 'http://localhost:8080'

function sseBody(events: string[]): ReadableStream<Uint8Array> {
  const encoder = new TextEncoder()
  return new ReadableStream({
    start(controller) {
      for (const e of events) {
        controller.enqueue(encoder.encode(e))
      }
      controller.close()
    },
  })
}

describe('AI API – branch coverage', () => {
  beforeEach(() => {
    localStorage.clear()
    sessionStorage.clear()
    sessionStorage.setItem('authenticated', 'true')
  })

  // ──── streamAiSearch – success path ────

  describe('streamAiSearch – success path', () => {
    it('parses SSE events from streamed response', async () => {
      const events: unknown[] = []
      server.use(
        http.post(`${API_BASE}/v1/ai/chat/stream`, () => {
          return new HttpResponse(
            sseBody([
              'data: {"type":"token","content":"hello"}\n\n',
              'data: {"type":"done"}\n\n',
            ]),
            {
              headers: { 'Content-Type': 'text/event-stream' },
            }
          )
        })
      )

      await api.streamAiSearch(
        { message: 'test', currentPage: '/dash' },
        (event) => events.push(event)
      )
      expect(events).toHaveLength(2)
      expect(events[0]).toEqual({ type: 'token', content: 'hello' })
    })

    it('handles multi-chunk SSE with split lines', async () => {
      const events: unknown[] = []
      server.use(
        http.post(`${API_BASE}/v1/ai/chat/stream`, () => {
          return new HttpResponse(
            sseBody([
              'data: {"type":"tok',
              'en","content":"hi"}\n\ndata: {"type":"done"}\n\n',
            ]),
            {
              headers: { 'Content-Type': 'text/event-stream' },
            }
          )
        })
      )

      await api.streamAiSearch({ message: 'x' }, (e) => events.push(e))
      expect(events).toHaveLength(2)
    })

    it('skips non-data lines and malformed JSON', async () => {
      const events: unknown[] = []
      server.use(
        http.post(`${API_BASE}/v1/ai/chat/stream`, () => {
          return new HttpResponse(
            sseBody([
              'event: ping\n',
              'data: not-json\n',
              'data: {"type":"valid"}\n',
              ': comment line\n\n',
            ]),
            {
              headers: { 'Content-Type': 'text/event-stream' },
            }
          )
        })
      )

      await api.streamAiSearch({ message: 'y' }, (e) => events.push(e))
      expect(events).toHaveLength(1)
      expect(events[0]).toEqual({ type: 'valid' })
    })

    it('sends optional conversationId and timeRange', async () => {
      const conversationId = '55555555-5555-4555-8555-555555555555'
      server.use(
        http.post(`${API_BASE}/v1/ai/chat/stream`, async ({ request }) => {
          const body = (await request.json()) as Record<string, unknown>
          expect(body.conversationId).toBe(conversationId)
          expect(body.timeRange).toBe('24h')
          return new HttpResponse(sseBody(['data: {"type":"done"}\n\n']), {
            headers: { 'Content-Type': 'text/event-stream' },
          })
        })
      )

      await api.streamAiSearch(
        { message: 'q', conversationId, timeRange: '24h' },
        () => {}
      )
    })
  })

  // ──── streamAiSearch – error paths ────

  describe('streamAiSearch – error paths', () => {
    it('throws when response is not ok with error body', async () => {
      server.use(
        http.post(`${API_BASE}/v1/ai/chat/stream`, () => {
          return HttpResponse.json(
            { error: 'Rate limited' },
            { status: 429 }
          )
        })
      )

      await expect(
        api.streamAiSearch({ message: 'fail' }, () => {})
      ).rejects.toThrow('Rate limited')
    })

    it('throws with status code when error body has no error field', async () => {
      server.use(
        http.post(`${API_BASE}/v1/ai/chat/stream`, () => {
          return HttpResponse.json({ other: 'data' }, { status: 500 })
        })
      )

      await expect(
        api.streamAiSearch({ message: 'fail2' }, () => {})
      ).rejects.toThrow('Stream error: 500')
    })

    it('throws default error when response.json() fails', async () => {
      server.use(
        http.post(`${API_BASE}/v1/ai/chat/stream`, () => {
          return new HttpResponse('not json', {
            status: 502,
            headers: { 'Content-Type': 'text/plain' },
          })
        })
      )

      await expect(
        api.streamAiSearch({ message: 'fail3' }, () => {})
      ).rejects.toThrow('Stream failed')
    })

    it('throws when response body is null', async () => {
      const originalFetch = globalThis.fetch
      globalThis.fetch = vi.fn().mockResolvedValue({
        ok: true,
        body: null,
        json: () => Promise.resolve({ error: 'No body' }),
      })

      try {
        await expect(
          api.streamAiSearch({ message: 'nobody' }, () => {})
        ).rejects.toThrow('No body')
      } finally {
        globalThis.fetch = originalFetch
      }
    })
  })

  // ──── streamAiConfirm – success path ────

  describe('streamAiConfirm – success path', () => {
    it('streams confirmation events', async () => {
      const events: unknown[] = []
      server.use(
        http.post(`${API_BASE}/v1/ai/chat/confirm`, () => {
          return new HttpResponse(
            sseBody([
              'data: {"type":"progress","percent":50}\n\n',
              'data: {"type":"done"}\n\n',
            ]),
            {
              headers: { 'Content-Type': 'text/event-stream' },
            }
          )
        })
      )

      await api.streamAiConfirm('42', (e) => events.push(e))
      expect(events).toHaveLength(2)
      expect(events[0]).toEqual({ type: 'progress', percent: 50 })
    })
  })

  // ──── streamAiConfirm – error paths ────

  describe('streamAiConfirm – error paths', () => {
    it('throws when response is not ok with error body', async () => {
      server.use(
        http.post(`${API_BASE}/v1/ai/chat/confirm`, () => {
          return HttpResponse.json(
            { error: 'Snapshot expired' },
            { status: 400 }
          )
        })
      )

      await expect(api.streamAiConfirm('1', () => {})).rejects.toThrow(
        'Snapshot expired'
      )
    })

    it('throws with status code when error body has no error field', async () => {
      server.use(
        http.post(`${API_BASE}/v1/ai/chat/confirm`, () => {
          return HttpResponse.json({}, { status: 503 })
        })
      )

      await expect(api.streamAiConfirm('2', () => {})).rejects.toThrow(
        'Confirm error: 503'
      )
    })

    it('throws default error when response.json() fails', async () => {
      server.use(
        http.post(`${API_BASE}/v1/ai/chat/confirm`, () => {
          return new HttpResponse('bad', {
            status: 500,
            headers: { 'Content-Type': 'text/plain' },
          })
        })
      )

      await expect(api.streamAiConfirm('3', () => {})).rejects.toThrow(
        'Confirm failed'
      )
    })

    it('throws when response body is null', async () => {
      const originalFetch = globalThis.fetch
      globalThis.fetch = vi.fn().mockResolvedValue({
        ok: true,
        body: null,
        json: () => Promise.resolve({}),
      })

      try {
        await expect(api.streamAiConfirm('4', () => {})).rejects.toThrow(
          'Confirm error'
        )
      } finally {
        globalThis.fetch = originalFetch
      }
    })
  })

  // ──── readSseStream – remaining buffer ────

  describe('readSseStream – remaining buffer on done', () => {
    it('flushes remaining buffer when stream ends', async () => {
      const events: unknown[] = []
      server.use(
        http.post(`${API_BASE}/v1/ai/chat/stream`, () => {
          return new HttpResponse(
            sseBody(['data: {"type":"final"}']),
            {
              headers: { 'Content-Type': 'text/event-stream' },
            }
          )
        })
      )

      await api.streamAiSearch({ message: 'buf' }, (e) => events.push(e))
      expect(events).toHaveLength(1)
      expect(events[0]).toEqual({ type: 'final' })
    })
  })
})
