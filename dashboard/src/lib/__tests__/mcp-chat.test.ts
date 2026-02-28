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
// MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
// See the GNU Affero General Public License for more details.
//
// You should have received a copy of the GNU Affero General Public License
// along with this program. If not, see <https://www.gnu.org/licenses/>.

import {afterEach, describe, expect, it, vi} from 'vitest'
import {confirmAiAction, streamAiAssistant, type AssistantStreamEvent} from '@/lib/mcp-chat'

describe('mcp-chat', () => {
  afterEach(() => {
    vi.unstubAllGlobals()
    vi.restoreAllMocks()
  })

  it('parses assistant SSE events', async () => {
    const encoder = new TextEncoder()
    const stream = new ReadableStream({
      start(controller) {
        controller.enqueue(
          encoder.encode('data: {"type":"tool_invoking","tool":"list_issues","args":{"limit":5}}\n\n'),
        )
        controller.enqueue(
          encoder.encode('data: {"type":"response","content":"Found issues"}\n\n'),
        )
        controller.enqueue(
          encoder.encode('data: {"type":"done","conversationId":"conv-1"}\n\n'),
        )
        controller.close()
      },
    })

    const fetchMock = vi.fn().mockResolvedValue(
      new Response(stream, {
        status: 200,
        headers: {'Content-Type': 'text/event-stream'},
      }),
    )
    vi.stubGlobal('fetch', fetchMock)

    const events: AssistantStreamEvent[] = []
    await streamAiAssistant('show issues', null, (event) => events.push(event))

    expect(events).toHaveLength(3)
    expect(events[0].type).toBe('tool_invoking')
    expect(events[1].type).toBe('response')
    expect(events[2].type).toBe('done')
  })

  it('sends confirmation request payload', async () => {
    const fetchMock = vi.fn().mockResolvedValue(
      new Response(
        JSON.stringify({
          conversationId: 'conv-2',
          requestId: 'req-1',
          approved: true,
          tool: 'create_host',
          toolSummary: 'Created host edge-01',
          response: 'Host has been created.',
        }),
        {status: 200, headers: {'Content-Type': 'application/json'}},
      ),
    )
    vi.stubGlobal('fetch', fetchMock)

    const result = await confirmAiAction('req-1', true)
    expect(result.requestId).toBe('req-1')
    expect(result.approved).toBe(true)

    const [, options] = fetchMock.mock.calls[0] as [string, RequestInit]
    expect(options.method).toBe('POST')
    expect(options.body).toContain('"requestId":"req-1"')
  })
})
