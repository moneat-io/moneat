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

const API_BASE = 'http://localhost:8080'
const CONVERSATION_ID = '11111111-1111-4111-8111-111111111111'
const NEW_CONVERSATION_ID = '22222222-2222-4222-8222-222222222222'
const DELETE_CONVERSATION_ID = '33333333-3333-4333-8333-333333333333'
const SECOND_CONVERSATION_ID = '44444444-4444-4444-8444-444444444444'

describe('AI API', () => {
  beforeEach(() => {
    localStorage.clear()
    sessionStorage.clear()
    sessionStorage.setItem('authenticated', 'true')
  })

  // ──── sendChatMessage ────

  describe('sendChatMessage', () => {
    it('sends a chat message with conversationId', async () => {
      const mockResponse = {
        conversationId: CONVERSATION_ID,
        response: {
          message: 'Here is the answer',
          actions: [],
        },
      }

      server.use(
        http.post(`${API_BASE}/v1/ai/chat`, async ({ request }) => {
          const body = (await request.json()) as Record<string, unknown>
          expect(body.conversationId).toBe(CONVERSATION_ID)
          expect(body.message).toBe('What happened?')
          expect(body.currentPage).toBe('/dashboard')
          return HttpResponse.json(mockResponse)
        })
      )

      const result = await api.sendChatMessage(CONVERSATION_ID, 'What happened?', '/dashboard')
      expect(result.conversationId).toBe(CONVERSATION_ID)
      expect(result.response.message).toBe('Here is the answer')
    })

    it('sends a chat message with null conversationId for new conversation', async () => {
      server.use(
        http.post(`${API_BASE}/v1/ai/chat`, async ({ request }) => {
          const body = (await request.json()) as Record<string, unknown>
          expect(body.conversationId).toBeNull()
          return HttpResponse.json({
            conversationId: NEW_CONVERSATION_ID,
            response: {
              message: 'New conversation started',
              actions: [],
            },
          })
        })
      )

      const result = await api.sendChatMessage(null, 'Hello', '/home')
      expect(result.conversationId).toBe(NEW_CONVERSATION_ID)
      expect(result.response.message).toBe('New conversation started')
      expect(result.response.actions).toEqual([])
    })
  })

  // ──── executeAiAction ────

  describe('executeAiAction', () => {
    it('executes an AI action', async () => {
      const mockResult = {
        success: true,
        result: 'Action completed',
      }

      server.use(
        http.post(`${API_BASE}/v1/ai/execute-action`, async ({ request }) => {
          const body = (await request.json()) as Record<string, unknown>
          expect(body.conversationId).toBe(CONVERSATION_ID)
          expect(body.actionId).toBe('resolve-issue')
          expect(body.params).toEqual({ issueId: '123' })
          return HttpResponse.json(mockResult)
        })
      )

      const result = await api.executeAiAction(CONVERSATION_ID, 'resolve-issue', {
        issueId: '123',
      })
      expect(result.success).toBe(true)
    })

    it('sends empty params by default', async () => {
      server.use(
        http.post(`${API_BASE}/v1/ai/execute-action`, async ({ request }) => {
          const body = (await request.json()) as Record<string, unknown>
          expect(body.params).toEqual({})
          return HttpResponse.json({ success: true, result: '' })
        })
      )

      await api.executeAiAction(SECOND_CONVERSATION_ID, 'summarize')
    })
  })

  // ──── getAiConversations ────

  describe('getAiConversations', () => {
    it('fetches conversation list', async () => {
      const mockConversations = [
        { id: CONVERSATION_ID, title: 'Error investigation', createdAt: '2024-01-01T00:00:00Z' },
        { id: SECOND_CONVERSATION_ID, title: 'Performance analysis', createdAt: '2024-01-02T00:00:00Z' },
      ]

      server.use(
        http.get(`${API_BASE}/v1/ai/conversations`, () => {
          return HttpResponse.json(mockConversations)
        })
      )

      const result = await api.getAiConversations()
      expect(result).toHaveLength(2)
      expect(result[0].title).toBe('Error investigation')
    })
  })

  // ──── getAiConversation ────

  describe('getAiConversation', () => {
    it('fetches a single conversation by id', async () => {
      const mockConversation = {
        id: CONVERSATION_ID,
        title: 'Error investigation',
        messages: [
          { role: 'user', content: 'What errors occurred?' },
          { role: 'assistant', content: 'I found 3 errors.' },
        ],
        createdAt: '2024-01-01T00:00:00Z',
      }

      server.use(
        http.get(`${API_BASE}/v1/ai/conversations/${CONVERSATION_ID}`, () => {
          return HttpResponse.json(mockConversation)
        })
      )

      const result = await api.getAiConversation(CONVERSATION_ID)
      expect(result.id).toBe(CONVERSATION_ID)
      expect(result.messages).toHaveLength(2)
    })
  })

  // ──── deleteAiConversation ────

  describe('deleteAiConversation', () => {
    it('deletes a conversation by id', async () => {
      server.use(
        http.delete(`${API_BASE}/v1/ai/conversations/${DELETE_CONVERSATION_ID}`, () => {
          return new HttpResponse(null, { status: 204 })
        })
      )

      await api.deleteAiConversation(DELETE_CONVERSATION_ID)
    })
  })
})
