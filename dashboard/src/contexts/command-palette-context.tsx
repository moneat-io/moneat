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

import {useState, useCallback, useRef, useEffect, type ReactNode} from 'react'
import {
  CommandPaletteContext,
  type AiPaletteMessage,
  type AiPalettePendingConfirmation,
  type AiPaletteToolInvocation,
} from '@/contexts/CommandPaletteContext'
import {
  saveActiveChat,
  loadActiveChat,
  clearActiveChat,
  archiveChat,
  getHistory,
  type ChatSnapshot,
} from '@/lib/ai-chat-history'

const EXPIRY_MS = 60 * 60 * 1000 // 1 hour

function loadInitialActiveChat() {
  return loadActiveChat()
}

export function CommandPaletteProvider({children}: {children: ReactNode}) {
  const [open, setOpen] = useState(false)
  const initialChat = useState(loadInitialActiveChat)[0]
  const [aiMode, setAiMode] = useState(() => initialChat !== null)
  const [conversationId, setConversationId] = useState<string | null>(
    () => initialChat?.conversationId ?? null,
  )
  const [aiMessages, setAiMessages] = useState<AiPaletteMessage[]>(
    () => initialChat?.messages ?? [],
  )
  const [toolInvocations, setToolInvocations] = useState<AiPaletteToolInvocation[]>(
    () => initialChat?.toolInvocations ?? [],
  )
  const [pendingConfirmation, setPendingConfirmation] = useState<AiPalettePendingConfirmation | null>(null)
  const [chatHistory, setChatHistory] = useState<ChatSnapshot[]>(() => getHistory())
  const connectionCleanupRef = useRef<(() => void) | null>(null)
  const expiryTimerRef = useRef<ReturnType<typeof setTimeout> | null>(null)

  const clearExpiryTimer = useCallback(() => {
    if (expiryTimerRef.current) {
      clearTimeout(expiryTimerRef.current)
      expiryTimerRef.current = null
    }
  }, [])

  const buildSnapshot = useCallback((): ChatSnapshot => ({
    id: `chat-${Date.now()}`,
    conversationId,
    messages: aiMessages,
    toolInvocations,
    timestamp: Date.now(),
  }), [conversationId, aiMessages, toolInvocations])

  // Persist active chat to localStorage whenever it changes
  useEffect(() => {
    if (aiMessages.length > 0) {
      saveActiveChat({
        id: `chat-${Date.now()}`,
        conversationId,
        messages: aiMessages,
        toolInvocations,
        timestamp: Date.now(),
      })
    }
  }, [conversationId, aiMessages, toolInvocations])

  // Reset expiry timer whenever messages change
  useEffect(() => {
    clearExpiryTimer()
    if (aiMessages.length > 0) {
      expiryTimerRef.current = setTimeout(() => {
        const snapshot = buildSnapshot()
        archiveChat(snapshot)
        clearActiveChat()
        setConversationId(null)
        setAiMessages([])
        setToolInvocations([])
        setPendingConfirmation(null)
        setAiMode(false)
        setChatHistory(getHistory())
      }, EXPIRY_MS)
    }
    return clearExpiryTimer
  }, [aiMessages, clearExpiryTimer, buildSnapshot])

  const cleanupConnection = useCallback(() => {
    connectionCleanupRef.current?.()
    connectionCleanupRef.current = null
  }, [])

  const resetAiState = useCallback(() => {
    clearExpiryTimer()
    setAiMode(false)
    setConversationId(null)
    setAiMessages([])
    setToolInvocations([])
    setPendingConfirmation(null)
    clearActiveChat()
  }, [clearExpiryTimer])

  const startNewChat = useCallback(() => {
    cleanupConnection()
    if (aiMessages.length > 0) {
      const snapshot = buildSnapshot()
      archiveChat(snapshot)
      setChatHistory(getHistory())
    }
    resetAiState()
  }, [cleanupConnection, aiMessages, buildSnapshot, resetAiState])

  const restoreChat = useCallback((snapshot: ChatSnapshot) => {
    cleanupConnection()
    setConversationId(snapshot.conversationId)
    setAiMessages(snapshot.messages)
    setToolInvocations(snapshot.toolInvocations)
    setPendingConfirmation(null)
    setAiMode(true)
    saveActiveChat({...snapshot, timestamp: Date.now()})
  }, [cleanupConnection])

  const registerConnectionCleanup = useCallback((cleanup: (() => void) | null) => {
    cleanupConnection()
    connectionCleanupRef.current = cleanup
  }, [cleanupConnection])

  const openPalette = useCallback(() => setOpen(true), [])

  useEffect(() => {
    return () => {
      cleanupConnection()
    }
  }, [cleanupConnection])
  const setOpenValue = useCallback(
    (value: boolean | ((prev: boolean) => boolean)) => {
      setOpen((prev) => {
        const next = typeof value === 'function' ? value(prev) : value
        if (!next) {
          cleanupConnection()
        }
        return next
      })
    },
    [cleanupConnection],
  )
  return (
    <CommandPaletteContext.Provider
      value={{
        open,
        setOpen: setOpenValue,
        openPalette,
        aiMode,
        setAiMode,
        conversationId,
        setConversationId,
        aiMessages,
        setAiMessages,
        toolInvocations,
        setToolInvocations,
        pendingConfirmation,
        setPendingConfirmation,
        registerConnectionCleanup,
        cleanupConnection,
        resetAiState,
        chatHistory,
        startNewChat,
        restoreChat,
      }}
    >
      {children}
    </CommandPaletteContext.Provider>
  )
}
