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

const API_BASE = `${import.meta.env.VITE_BACKEND_URL || ''}/v1`

export interface AssistantToolInvokingEvent {
  type: 'tool_invoking'
  tool: string
  args: Record<string, unknown>
}

export interface AssistantToolResultEvent {
  type: 'tool_result'
  tool: string
  summary: string
  isError?: boolean
}

export interface AssistantConfirmationNeededEvent {
  type: 'confirmation_needed'
  requestId: string
  conversationId: string
  tool: string
  args: Record<string, unknown>
}

export interface AssistantResponseEvent {
  type: 'response'
  content: string
}

export interface AssistantErrorEvent {
  type: 'error'
  error: string
}

export interface AssistantDoneEvent {
  type: 'done'
  conversationId: string
}

export type AssistantStreamEvent =
  | AssistantToolInvokingEvent
  | AssistantToolResultEvent
  | AssistantConfirmationNeededEvent
  | AssistantResponseEvent
  | AssistantErrorEvent
  | AssistantDoneEvent

export interface ConfirmAiActionResponse {
  conversationId: string
  requestId: string
  approved: boolean
  tool: string
  toolSummary: string
  response: string
  nextRequestId?: string | null
}

export async function streamAiAssistant(
  message: string,
  conversationId: string | null,
  onEvent: (event: AssistantStreamEvent) => void,
  signal?: AbortSignal,
  projectId?: string | null,
): Promise<void> {
  const impersonateToken = sessionStorage.getItem('impersonate_token')
  const authHeaders: Record<string, string> = impersonateToken
    ? {'Authorization': `Bearer ${impersonateToken}`}
    : {}
  const response = await fetch(`${API_BASE}/ai/assistant/stream`, {
    method: 'POST',
    headers: {'Content-Type': 'application/json', ...authHeaders},
    credentials: 'include',
    body: JSON.stringify({
      message,
      conversationId: conversationId ?? undefined,
      projectId: projectId ?? undefined,
    }),
    signal,
  })

  if (!response.ok || !response.body) {
    const err = await response.json().catch(() => ({error: 'Assistant stream failed'}))
    throw new Error(err.error || `Assistant stream error: ${response.status}`)
  }

  const reader = response.body.getReader()
  const decoder = new TextDecoder()
  let buffer = ''

  while (true) {
    const {value, done} = await reader.read()
    if (done) break
    buffer += decoder.decode(value, {stream: true})
    const lines = buffer.split('\n')
    buffer = lines.pop() ?? ''

    for (const line of lines) {
      if (!line.startsWith('data: ')) continue
      const payload = line.slice(6)
      try {
        onEvent(JSON.parse(payload) as AssistantStreamEvent)
      } catch {
        // ignore malformed chunks
      }
    }
  }
}

export async function confirmAiAction(
  requestId: string,
  approve = true,
): Promise<ConfirmAiActionResponse> {
  const impersonateToken = sessionStorage.getItem('impersonate_token')
  const authHeaders: Record<string, string> = impersonateToken
    ? {'Authorization': `Bearer ${impersonateToken}`}
    : {}
  const response = await fetch(`${API_BASE}/ai/assistant/confirm`, {
    method: 'POST',
    headers: {'Content-Type': 'application/json', ...authHeaders},
    credentials: 'include',
    body: JSON.stringify({requestId, approve}),
  })

  if (!response.ok) {
    const err = await response.json().catch(() => ({error: 'Assistant confirmation failed'}))
    throw new Error(err.error || `Assistant confirmation error: ${response.status}`)
  }

  return response.json() as Promise<ConfirmAiActionResponse>
}
