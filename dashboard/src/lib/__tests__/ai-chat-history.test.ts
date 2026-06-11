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

import {beforeEach, describe, expect, it} from 'vitest'
import {getHistory, loadActiveChat, saveActiveChat} from '@/lib/ai-chat-history'

const ACTIVE_CHAT_KEY = 'moneat:ai-active-chat'
const CHAT_HISTORY_KEY = 'moneat:ai-chat-history'
const CONVERSATION_ID = '11111111-1111-4111-8111-111111111111'

describe('ai chat history', () => {
  beforeEach(() => {
    globalThis.localStorage.clear()
  })

  it('loads a valid active chat with a UUID conversation ID', () => {
    saveActiveChat({
      id: 'active',
      conversationId: CONVERSATION_ID,
      messages: [],
      toolInvocations: [],
      timestamp: Date.now(),
    })

    expect(loadActiveChat()?.conversationId).toBe(CONVERSATION_ID)
  })

  it('discards active chats persisted with legacy numeric conversation IDs', () => {
    globalThis.localStorage.setItem(
      ACTIVE_CHAT_KEY,
      JSON.stringify({
        id: 'legacy',
        conversationId: 42,
        messages: [],
        toolInvocations: [],
        timestamp: Date.now(),
      }),
    )

    expect(loadActiveChat()).toBeNull()
    expect(globalThis.localStorage.getItem(ACTIVE_CHAT_KEY)).toBeNull()
  })

  it('discards malformed active chat payloads', () => {
    globalThis.localStorage.setItem(ACTIVE_CHAT_KEY, JSON.stringify(null))
    expect(loadActiveChat()).toBeNull()

    globalThis.localStorage.setItem(
      ACTIVE_CHAT_KEY,
      JSON.stringify({
        id: 'malformed',
        conversationId: CONVERSATION_ID,
        messages: [],
        timestamp: Date.now(),
      }),
    )
    expect(loadActiveChat()).toBeNull()
  })

  it('filters legacy numeric conversation IDs from archived history', () => {
    globalThis.localStorage.setItem(
      CHAT_HISTORY_KEY,
      JSON.stringify([
        {
          id: 'current',
          conversationId: CONVERSATION_ID,
          messages: [],
          toolInvocations: [],
          timestamp: Date.now(),
        },
        {
          id: 'legacy',
          conversationId: 42,
          messages: [],
          toolInvocations: [],
          timestamp: Date.now(),
        },
      ]),
    )

    expect(getHistory().map((snapshot) => snapshot.id)).toEqual(['current'])
  })
})
