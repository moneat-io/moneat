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

import {createContext} from 'react'

export interface AiPaletteMessage {
  id: string
  role: 'user' | 'assistant'
  content: string
}

export interface AiPaletteToolInvocation {
  id: string
  tool: string
  status: 'invoking' | 'completed' | 'error'
  summary?: string
  args?: Record<string, unknown>
}

export interface AiPalettePendingConfirmation {
  requestId: string
  tool: string
  args: Record<string, unknown>
}

export interface CommandPaletteContextValue {
  open: boolean
  setOpen: (open: boolean | ((prev: boolean) => boolean)) => void
  openPalette: () => void
  aiMode: boolean
  setAiMode: (value: boolean) => void
  conversationId: string | null
  setConversationId: (id: string | null) => void
  aiMessages: AiPaletteMessage[]
  setAiMessages: (value: AiPaletteMessage[] | ((prev: AiPaletteMessage[]) => AiPaletteMessage[])) => void
  toolInvocations: AiPaletteToolInvocation[]
  setToolInvocations: (
    value: AiPaletteToolInvocation[] |
      ((prev: AiPaletteToolInvocation[]) => AiPaletteToolInvocation[])
  ) => void
  pendingConfirmation: AiPalettePendingConfirmation | null
  setPendingConfirmation: (
    value: AiPalettePendingConfirmation | null |
      ((prev: AiPalettePendingConfirmation | null) => AiPalettePendingConfirmation | null)
  ) => void
  registerConnectionCleanup: (cleanup: (() => void) | null) => void
  cleanupConnection: () => void
  resetAiState: () => void
}

export const CommandPaletteContext = createContext<CommandPaletteContextValue | null>(null)
