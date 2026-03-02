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

import type {AiPaletteMessage, AiPaletteToolInvocation} from '@/contexts/CommandPaletteContext'

const ACTIVE_CHAT_KEY = 'moneat:ai-active-chat'
const CHAT_HISTORY_KEY = 'moneat:ai-chat-history'
const EXPIRY_MS = 60 * 60 * 1000 // 1 hour
const MAX_HISTORY = 3

export interface ChatSnapshot {
  id: string
  conversationId: string | null
  messages: AiPaletteMessage[]
  toolInvocations: AiPaletteToolInvocation[]
  timestamp: number
}

export function saveActiveChat(snapshot: ChatSnapshot): void {
  try {
    localStorage.setItem(ACTIVE_CHAT_KEY, JSON.stringify(snapshot))
  } catch {
    // storage full or unavailable
  }
}

export function loadActiveChat(): ChatSnapshot | null {
  try {
    const raw = localStorage.getItem(ACTIVE_CHAT_KEY)
    if (!raw) return null
    const snapshot = JSON.parse(raw) as ChatSnapshot
    if (Date.now() - snapshot.timestamp > EXPIRY_MS) {
      archiveChat(snapshot)
      clearActiveChat()
      return null
    }
    return snapshot
  } catch {
    return null
  }
}

export function clearActiveChat(): void {
  try {
    localStorage.removeItem(ACTIVE_CHAT_KEY)
  } catch {
    // ignore
  }
}

export function archiveChat(snapshot: ChatSnapshot): void {
  if (!snapshot.messages.length) return
  try {
    const history = getHistory()
    history.unshift(snapshot)
    localStorage.setItem(
      CHAT_HISTORY_KEY,
      JSON.stringify(history.slice(0, MAX_HISTORY)),
    )
  } catch {
    // storage full or unavailable
  }
}

export function getHistory(): ChatSnapshot[] {
  try {
    const raw = localStorage.getItem(CHAT_HISTORY_KEY)
    if (!raw) return []
    return JSON.parse(raw) as ChatSnapshot[]
  } catch {
    return []
  }
}
