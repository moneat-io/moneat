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

import {useState, useCallback, useRef, type ReactNode} from 'react'
import {
  CommandPaletteContext,
  type AiPaletteMessage,
  type AiPalettePendingConfirmation,
  type AiPaletteToolInvocation,
} from '@/contexts/CommandPaletteContext'

export function CommandPaletteProvider({children}: {children: ReactNode}) {
  const [open, setOpen] = useState(false)
  const [aiMode, setAiMode] = useState(false)
  const [conversationId, setConversationId] = useState<string | null>(null)
  const [aiMessages, setAiMessages] = useState<AiPaletteMessage[]>([])
  const [toolInvocations, setToolInvocations] = useState<AiPaletteToolInvocation[]>([])
  const [pendingConfirmation, setPendingConfirmation] = useState<AiPalettePendingConfirmation | null>(null)
  const connectionCleanupRef = useRef<(() => void) | null>(null)

  const cleanupConnection = useCallback(() => {
    connectionCleanupRef.current?.()
    connectionCleanupRef.current = null
  }, [])

  const resetAiState = useCallback(() => {
    setAiMode(false)
    setConversationId(null)
    setAiMessages([])
    setToolInvocations([])
    setPendingConfirmation(null)
  }, [])

  const registerConnectionCleanup = useCallback((cleanup: (() => void) | null) => {
    cleanupConnection()
    connectionCleanupRef.current = cleanup
  }, [cleanupConnection])

  const openPalette = useCallback(() => setOpen(true), [])
  const setOpenValue = useCallback(
    (value: boolean | ((prev: boolean) => boolean)) => {
      setOpen((prev) => {
        const next = typeof value === 'function' ? value(prev) : value
        if (!next) {
          cleanupConnection()
          resetAiState()
        }
        return next
      })
    },
    [cleanupConnection, resetAiState],
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
      }}
    >
      {children}
    </CommandPaletteContext.Provider>
  )
}
