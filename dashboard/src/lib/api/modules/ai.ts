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

import type { ApiClientCore } from '../client'
import type {
  AiChatResponse,
  AiActionResult,
  AiConversationSummary,
  AiConversationDetail,
  AiSseEvent,
} from '../types'

function parseSseDataLines(lines: string[], onEvent: (event: AiSseEvent) => void): void {
  for (const line of lines) {
    if (!line.startsWith('data: ')) continue
    try {
      const event = JSON.parse(line.slice(6)) as AiSseEvent
      onEvent(event)
    } catch {
      /* skip malformed events */
    }
  }
}

async function readSseStream(response: Response, onEvent: (event: AiSseEvent) => void): Promise<void> {
  const reader = response.body!.getReader()
  const decoder = new TextDecoder()
  let buffer = ''

  while (true) {
    const { value, done } = await reader.read()
    if (done) {
      buffer += decoder.decode(undefined, { stream: false })
      parseSseDataLines(buffer.split('\n'), onEvent)
      break
    }
    buffer += decoder.decode(value, { stream: true })
    const lines = buffer.split('\n')
    buffer = lines.pop() ?? ''
    parseSseDataLines(lines, onEvent)
  }
}

export function aiMethods(core: ApiClientCore) {
  const base = core.API_BASE

  return {
    sendChatMessage: (
      conversationId: number | null,
      message: string,
      currentPage: string
    ) =>
      core.request<AiChatResponse>(`${base}/ai/chat`, {
        method: 'POST',
        body: JSON.stringify({ conversationId, message, currentPage }),
      }),

    executeAiAction: (
      conversationId: number,
      actionId: string,
      params: Record<string, string> = {}
    ) =>
      core.request<AiActionResult>(`${base}/ai/execute-action`, {
        method: 'POST',
        body: JSON.stringify({ conversationId, actionId, params }),
      }),

    getAiConversations: () =>
      core.request<AiConversationSummary[]>(`${base}/ai/conversations`),

    getAiConversation: (id: number) =>
      core.request<AiConversationDetail>(`${base}/ai/conversations/${id}`),

    deleteAiConversation: (id: number) =>
      core.request<void>(`${base}/ai/conversations/${id}`, {
        method: 'DELETE',
      }),

    streamAiSearch: async (
      request: {
        conversationId?: number | null
        message: string
        currentPage?: string
        timeRange?: string
      },
      onEvent: (event: AiSseEvent) => void
    ) => {
      const response = await core.fetchWithAuth(`${base}/ai/chat/stream`, {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify(request),
      })

      if (!response.ok || !response.body) {
        const err = await response.json().catch(() => ({ error: 'Stream failed' }))
        throw new Error((err as { error?: string }).error || `Stream error: ${response.status}`)
      }

      await readSseStream(response, onEvent)
    },

    streamAiConfirm: async (
      snapshotId: number,
      onEvent: (event: AiSseEvent) => void
    ) => {
      const response = await core.fetchWithAuth(`${base}/ai/chat/confirm`, {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ snapshotId }),
      })

      if (!response.ok || !response.body) {
        const err = await response.json().catch(() => ({ error: 'Confirm failed' }))
        throw new Error((err as { error?: string }).error || `Confirm error: ${response.status}`)
      }

      await readSseStream(response, onEvent)
    },
  }
}
