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

export interface AiChatResponseData {
  message: string
  actions?: AiAction[]
  clarifications?: AiClarification[]
  data_queries?: AiDataQuery[]
  links?: AiLink[]
  context_needed?: string[]
}

export interface AiAction {
  id: string
  type: string
  label: string
  method: string
  endpoint: string
  params?: Record<string, string>
}

export interface AiClarification {
  id: string
  question: string
  field: string
  options?: { label: string; value: string }[]
  default?: string
}

export interface AiDataQuery {
  id: string
  description: string
  endpoint: string
  params?: Record<string, string>
}

export interface AiLink {
  label: string
  url: string
}

export interface AiChatResponse {
  conversationId: number
  response: AiChatResponseData
  model?: string
  tokensUsed?: number
}

export interface AiActionResult {
  success: boolean
  message: string
  data?: Record<string, string>
}

export interface AiConversationSummary {
  id: number
  title: string | null
  createdAt: string
  updatedAt: string
}

export interface AiMessageDto {
  id: number
  role: string
  content: string
  pageContext?: string
  model?: string
  tokensUsed?: number
  createdAt: string
}

export interface AiConversationDetail {
  id: number
  title: string | null
  messages: AiMessageDto[]
  createdAt: string
  updatedAt: string
}

export interface AiSseSearchProgress {
  phase: 'searching'
  source: string
  status: string
  count?: number
}

export interface AiSseContextReady {
  phase: 'context_ready'
  snapshotId: number
  totalTokens: number
  sources: Record<string, number>
}

export interface AiSseResponseChunk {
  phase: 'response'
  content: string
  done?: boolean
}

export interface AiSseError {
  phase: 'error'
  error: string
}

export type AiSseEvent =
  | AiSseSearchProgress
  | AiSseContextReady
  | AiSseResponseChunk
  | AiSseError
