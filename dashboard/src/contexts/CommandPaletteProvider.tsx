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

import {useState, useCallback, useRef, useEffect, useMemo, type ReactNode} from 'react'
import {
  CommandPaletteContext,
  type AiPaletteMessage,
  type AiPalettePendingConfirmation,
  type AiPaletteToolInvocation,
  type AiPanelMode,
  type AiPanelOrientation,
} from '@/contexts/CommandPaletteContext'
import {
  saveActiveChat,
  loadActiveChat,
  clearActiveChat,
  archiveChat,
  getHistory,
  type ChatSnapshot,
} from '@/lib/ai-chat-history'
import {confirmAiAction, streamAiAssistant, type AssistantStreamEvent} from '@/lib/mcp-chat'

const EXPIRY_MS = 60 * 60 * 1000 // 1 hour
let snapshotFallbackSequence = 0

function createSnapshotId(): string {
  snapshotFallbackSequence += 1
  return globalThis.crypto?.randomUUID?.() ?? `chat-${Date.now()}-${snapshotFallbackSequence}`
}

function loadInitialActiveChat() {
  return loadActiveChat()
}

function loadInitialPanelMode(): AiPanelMode {
  const saved = localStorage.getItem('moneat:ai-panel-mode')
  return (saved as AiPanelMode | null) ?? 'dialog'
}

function loadInitialPanelSize(): number {
  const saved = globalThis.localStorage?.getItem('moneat:ai-panel-size')
  if (!saved) return 30
  const parsed = Number.parseFloat(saved)
  return Number.isFinite(parsed) ? parsed : 30
}

function loadInitialPanelOrientation(): AiPanelOrientation {
  const saved = localStorage.getItem('moneat:ai-panel-orientation')
  return (saved as AiPanelOrientation | null) ?? 'vertical'
}

export function CommandPaletteProvider({children}: {readonly children: ReactNode}) {
  // Dialog open state
  const [open, setOpen] = useState(false)

  // Chat state — lazy-initialized from localStorage
  const [initialChat] = useState(loadInitialActiveChat)
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

  // Streaming state (shared across all panel modes)
  const [isStreaming, setIsStreaming] = useState(false)
  const [isConfirming, setIsConfirming] = useState(false)
  const [aiInput, setAiInput] = useState('')
  const streamingAssistantMessageId = useRef<string | null>(null)

  // Panel display mode — persisted to localStorage
  const [aiPanelModeRaw, setAiPanelModeRaw] = useState<AiPanelMode>(loadInitialPanelMode)
  const [aiPanelSizeRaw, setAiPanelSizeRaw] = useState<number>(loadInitialPanelSize)
  const [aiPanelOrientationRaw, setAiPanelOrientationRaw] = useState<AiPanelOrientation>(
    loadInitialPanelOrientation,
  )

  const setAiPanelMode = useCallback((mode: AiPanelMode) => {
    setAiPanelModeRaw(mode)
    localStorage.setItem('moneat:ai-panel-mode', mode)
  }, [])

  const setAiPanelSize = useCallback((size: number) => {
    setAiPanelSizeRaw(size)
    localStorage.setItem('moneat:ai-panel-size', String(size))
  }, [])

  const setAiPanelOrientation = useCallback((orientation: AiPanelOrientation) => {
    setAiPanelOrientationRaw(orientation)
    localStorage.setItem('moneat:ai-panel-orientation', orientation)
  }, [])

  // Connection cleanup
  const connectionCleanupRef = useRef<(() => void) | null>(null)
  const expiryTimerRef = useRef<ReturnType<typeof setTimeout> | null>(null)

  const clearExpiryTimer = useCallback(() => {
    if (expiryTimerRef.current) {
      clearTimeout(expiryTimerRef.current)
      expiryTimerRef.current = null
    }
  }, [])

  const buildSnapshot = useCallback(
    (): ChatSnapshot => ({
      id: createSnapshotId(),
      conversationId,
      messages: aiMessages,
      toolInvocations,
      timestamp: Date.now(),
    }),
    [conversationId, aiMessages, toolInvocations],
  )

  // Persist active chat to localStorage whenever it changes
  useEffect(() => {
    if (aiMessages.length > 0) {
      saveActiveChat(buildSnapshot())
    }
  }, [aiMessages, buildSnapshot])

  // Reset 1-hour expiry timer whenever messages change
  useEffect(() => {
    clearExpiryTimer()
    if (aiMessages.length > 0) {
      const persisted = loadActiveChat()
      const remainingMs = persisted
        ? Math.max(EXPIRY_MS - (Date.now() - persisted.timestamp), 0)
        : EXPIRY_MS
      expiryTimerRef.current = setTimeout(() => {
        archiveChat(buildSnapshot())
        clearActiveChat()
        setConversationId(null)
        setAiMessages([])
        setToolInvocations([])
        setPendingConfirmation(null)
        setAiMode(false)
        setChatHistory(getHistory())
      }, remainingMs)
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
    setAiInput('')
    clearActiveChat()
  }, [clearExpiryTimer])

  const startNewChat = useCallback(() => {
    cleanupConnection()
    if (aiMessages.length > 0) {
      archiveChat(buildSnapshot())
      setChatHistory(getHistory())
    }
    resetAiState()
  }, [cleanupConnection, aiMessages, buildSnapshot, resetAiState])

  const restoreChat = useCallback(
    (snapshot: ChatSnapshot) => {
      cleanupConnection()
      setConversationId(snapshot.conversationId)
      setAiMessages(snapshot.messages)
      setToolInvocations(snapshot.toolInvocations)
      setPendingConfirmation(null)
      setAiMode(true)
      setAiInput('')
      saveActiveChat({...snapshot, timestamp: Date.now()})
    },
    [cleanupConnection],
  )

  const registerConnectionCleanup = useCallback(
    (cleanup: (() => void) | null) => {
      cleanupConnection()
      connectionCleanupRef.current = cleanup
    },
    [cleanupConnection],
  )

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

  // ── Streaming logic ──────────────────────────────────────────────────────

  const upsertToolInvocation = useCallback(
    (
      tool: string,
      status: 'invoking' | 'completed' | 'error',
      summary?: string,
      args?: Record<string, unknown>,
    ) => {
      setToolInvocations((prev) => {
        const next = [...prev]
        const reverseIndex = [...next]
          .reverse()
          .findIndex((entry) => entry.tool === tool && entry.status === 'invoking')
        if (reverseIndex >= 0 && status !== 'invoking') {
          const index = next.length - 1 - reverseIndex
          next[index] = {...next[index], status, summary}
          return next
        }
        next.push({id: `${tool}-${Date.now()}-${next.length}`, tool, status, summary, args})
        return next
      })
    },
    [],
  )

  const appendAssistantChunk = useCallback((content: string) => {
    const messageId = streamingAssistantMessageId.current ?? `assistant-${Date.now()}`
    streamingAssistantMessageId.current = messageId
    setAiMessages((prev) => {
      const existing = prev.find((m) => m.id === messageId)
      if (existing) {
        return prev.map((m) =>
          m.id === messageId ? {...m, content: `${m.content}${content}`} : m,
        )
      }
      return [...prev, {id: messageId, role: 'assistant', content}]
    })
  }, [])

  const handleAssistantEvent = useCallback(
    (event: AssistantStreamEvent) => {
      if (event.type === 'tool_invoking') {
        upsertToolInvocation(event.tool, 'invoking', undefined, event.args)
        return
      }
      if (event.type === 'tool_result') {
        upsertToolInvocation(event.tool, event.isError ? 'error' : 'completed', event.summary)
        return
      }
      if (event.type === 'confirmation_needed') {
        setPendingConfirmation({requestId: event.requestId, tool: event.tool, args: event.args})
        return
      }
      if (event.type === 'response') {
        appendAssistantChunk(event.content)
        return
      }
      if (event.type === 'error') {
        setAiMessages((prev) => [
          ...prev,
          {id: `assistant-error-${Date.now()}`, role: 'assistant', content: event.error},
        ])
        return
      }
      if (event.type === 'done' && event.conversationId) {
        setConversationId(event.conversationId)
      }
    },
    [upsertToolInvocation, appendAssistantChunk],
  )

  const handleAiSubmit = useCallback(
    async (prompt: string) => {
      const normalizedPrompt = prompt.trim()
      if (!normalizedPrompt || isStreaming || isConfirming) return

      streamingAssistantMessageId.current = null
      setPendingConfirmation(null)
      setToolInvocations([])
      setAiMessages((prev) => [
        ...prev,
        {id: `user-${Date.now()}`, role: 'user', content: normalizedPrompt},
      ])

      const controller = new AbortController()
      registerConnectionCleanup(() => controller.abort())
      setIsStreaming(true)

      try {
        await streamAiAssistant(
          normalizedPrompt,
          conversationId,
          handleAssistantEvent,
          controller.signal,
        )
      } catch (error) {
        const isAbort =
          error instanceof DOMException || (error instanceof Error && error.name === 'AbortError')
        if (!isAbort) {
          const errorMessage = error instanceof Error ? error.message : 'Assistant request failed'
          setAiMessages((prev) => [
            ...prev,
            {id: `assistant-error-${Date.now()}`, role: 'assistant', content: errorMessage},
          ])
        }
      } finally {
        registerConnectionCleanup(null)
        setIsStreaming(false)
      }
    },
    [isStreaming, isConfirming, conversationId, handleAssistantEvent, registerConnectionCleanup],
  )

  const handleConfirm = useCallback(
    async (approve: boolean) => {
      if (!pendingConfirmation || isConfirming) return
      setIsConfirming(true)
      try {
        const response = await confirmAiAction(pendingConfirmation.requestId, approve)
        setConversationId(response.conversationId)
        setPendingConfirmation(null)
        upsertToolInvocation(response.tool, approve ? 'completed' : 'error', response.toolSummary)
        if (response.response.trim()) {
          setAiMessages((prev) => [
            ...prev,
            {id: `assistant-${Date.now()}`, role: 'assistant', content: response.response},
          ])
        }
      } catch (error) {
        const errorMessage = error instanceof Error ? error.message : 'Failed to confirm action'
        setAiMessages((prev) => [
          ...prev,
          {id: `assistant-error-${Date.now()}`, role: 'assistant', content: errorMessage},
        ])
      } finally {
        setIsConfirming(false)
      }
    },
    [pendingConfirmation, isConfirming, upsertToolInvocation],
  )

  const contextValue = useMemo(() => ({
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
    isStreaming,
    isConfirming,
    aiInput,
    setAiInput,
    handleAiSubmit,
    handleConfirm,
    aiPanelMode: aiPanelModeRaw,
    aiPanelSize: aiPanelSizeRaw,
    aiPanelOrientation: aiPanelOrientationRaw,
    setAiPanelMode,
    setAiPanelSize,
    setAiPanelOrientation,
  }), [
    open, setOpenValue, openPalette, aiMode, setAiMode, conversationId, setConversationId,
    aiMessages, setAiMessages, toolInvocations, setToolInvocations, pendingConfirmation,
    setPendingConfirmation, registerConnectionCleanup, cleanupConnection, resetAiState,
    chatHistory, startNewChat, restoreChat, isStreaming, isConfirming, aiInput, setAiInput,
    handleAiSubmit, handleConfirm, aiPanelModeRaw, aiPanelSizeRaw, aiPanelOrientationRaw,
    setAiPanelMode, setAiPanelSize, setAiPanelOrientation,
  ])

  return (
    <CommandPaletteContext.Provider value={contextValue}>
      {children}
    </CommandPaletteContext.Provider>
  )
}
