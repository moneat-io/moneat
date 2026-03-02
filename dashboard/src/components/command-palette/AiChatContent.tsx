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

import {useRef, type KeyboardEvent as ReactKeyboardEvent} from 'react'
import {
  Loader2,
  Plus,
  History,
  PanelRight,
  PanelBottom,
  Maximize2,
  MessageSquare,
} from 'lucide-react'
import {AiChatView} from '@/components/command-palette/AiChatView'
import {AiSuggestions} from '@/components/command-palette/AiSuggestions'
import {useCommandPalette} from '@/hooks/useCommandPalette'
import type {ChatSnapshot} from '@/lib/ai-chat-history'
import type {AiPanelMode, AiPanelOrientation} from '@/contexts/CommandPaletteContext'
import {cn} from '@/lib/utils'

interface AiChatContentProps {
  /** Controls scroll container height; 'dialog' uses a fixed max-height, 'panel' fills available space */
  variant?: 'dialog' | 'panel'
  /** Called when a mode-switch button closes the dialog */
  onClose?: () => void
}

export function AiChatContent({variant = 'dialog', onClose}: AiChatContentProps) {
  const palette = useCommandPalette()
  const aiInputRef = useRef<HTMLInputElement>(null)

  if (!palette) return null

  const {
    aiMessages,
    toolInvocations,
    pendingConfirmation,
    isStreaming,
    isConfirming,
    aiInput,
    setAiInput,
    handleAiSubmit,
    handleConfirm,
    chatHistory,
    startNewChat,
    restoreChat,
    aiPanelMode,
    aiPanelOrientation,
    setAiPanelMode,
    setAiPanelOrientation,
    setAiMode,
    setOpen,
  } = palette

  const submit = (prompt: string) => {
    void handleAiSubmit(prompt)
    setAiInput('')
    if (aiPanelMode === 'dialog') {
      setAiMode(true)
    }
  }

  const handleKeyDown = (e: ReactKeyboardEvent<HTMLInputElement>) => {
    if (e.key !== 'Enter' || !aiInput.trim() || isStreaming || isConfirming) return
    e.preventDefault()
    submit(aiInput.trim())
  }

  const switchMode = (mode: AiPanelMode, orientation?: AiPanelOrientation) => {
    if (orientation) setAiPanelOrientation(orientation)
    setAiPanelMode(mode)
    if (mode !== 'dialog') {
      setOpen(false)
      setAiMode(true)
    }
    if (mode === 'dialog') {
      setOpen(true)
      setAiMode(true)
    }
  }

  return (
    <div className={cn('flex flex-col', variant === 'panel' && 'flex-1 min-h-0')}>
      {/* Header with mode switcher */}
      <div className="flex items-center justify-between border-b px-3 py-2 shrink-0">
        <span className="text-xs font-medium text-muted-foreground">Ask AI</span>
        <div className="flex items-center gap-1">
          {aiMessages.length > 0 && (
            <button
              type="button"
              onClick={() => {
                startNewChat()
                setAiInput('')
                requestAnimationFrame(() => aiInputRef.current?.focus())
              }}
              title="New chat"
              className="flex items-center gap-1 rounded-md px-2 py-1 text-xs text-muted-foreground hover:bg-muted hover:text-foreground transition-colors"
            >
              <Plus className="h-3 w-3" />
              New
            </button>
          )}
          <div className="flex items-center rounded-md border border-border/50 overflow-hidden">
            <ModeButton
              active={aiPanelMode === 'dialog'}
              title="Dialog"
              onClick={() => switchMode('dialog')}
            >
              <MessageSquare className="h-3 w-3" />
            </ModeButton>
            <ModeButton
              active={aiPanelMode === 'split' && aiPanelOrientation === 'vertical'}
              title="Split vertical"
              onClick={() => switchMode('split', 'vertical')}
            >
              <PanelRight className="h-3 w-3" />
            </ModeButton>
            <ModeButton
              active={aiPanelMode === 'split' && aiPanelOrientation === 'horizontal'}
              title="Split horizontal"
              onClick={() => switchMode('split', 'horizontal')}
            >
              <PanelBottom className="h-3 w-3" />
            </ModeButton>
            <ModeButton
              active={aiPanelMode === 'float'}
              title="Float"
              onClick={() => switchMode('float')}
            >
              <Maximize2 className="h-3 w-3" />
            </ModeButton>
          </div>
        </div>
      </div>

      {/* Chat body */}
      <div
        className={cn(
          'overflow-y-auto',
          variant === 'dialog' ? 'max-h-[380px]' : 'flex-1',
        )}
      >
        {aiMessages.length === 0 && toolInvocations.length === 0 && !pendingConfirmation ? (
          <>
            <AiSuggestions onSelect={submit} />
            {chatHistory.length > 0 && (
              <div className="border-t px-3 py-2">
                <p className="mb-1.5 flex items-center gap-1 text-xs font-medium text-muted-foreground">
                  <History className="h-3 w-3" />
                  Recent Chats
                </p>
                <div className="space-y-1">
                  {chatHistory.map((snapshot: ChatSnapshot) => {
                    const firstUserMsg = snapshot.messages.find((m) => m.role === 'user')
                    if (!firstUserMsg) return null
                    const label =
                      firstUserMsg.content.length > 60
                        ? `${firstUserMsg.content.slice(0, 60)}…`
                        : firstUserMsg.content
                    return (
                      <button
                        key={snapshot.id}
                        type="button"
                        onClick={() => {
                          restoreChat(snapshot)
                          onClose?.()
                          requestAnimationFrame(() => aiInputRef.current?.focus())
                        }}
                        className="w-full truncate rounded-md px-2 py-1.5 text-left text-xs text-muted-foreground hover:bg-muted hover:text-foreground transition-colors"
                      >
                        {label}
                      </button>
                    )
                  })}
                </div>
              </div>
            )}
          </>
        ) : (
          <AiChatView
            messages={aiMessages}
            toolInvocations={toolInvocations}
            pendingConfirmation={pendingConfirmation}
            loading={isStreaming}
            confirming={isConfirming}
            onApprove={() => void handleConfirm(true)}
            onDeny={() => void handleConfirm(false)}
          />
        )}
      </div>

      {/* Input bar */}
      <div className="flex items-center gap-2 border-t px-3 py-2.5 shrink-0">
        <input
          ref={aiInputRef}
          type="text"
          value={aiInput}
          onChange={(e) => setAiInput(e.target.value)}
          onKeyDown={handleKeyDown}
          placeholder={aiMessages.length > 0 ? 'Ask a follow-up…' : 'Ask about your systems…'}
          className="flex-1 bg-transparent text-sm outline-none placeholder:text-muted-foreground disabled:opacity-50"
          disabled={isStreaming}
          autoFocus={variant === 'panel'}
        />
        {isStreaming && <Loader2 className="h-4 w-4 animate-spin text-muted-foreground" />}
      </div>
    </div>
  )
}

function ModeButton({
  active,
  title,
  onClick,
  children,
}: {
  active: boolean
  title: string
  onClick: () => void
  children: React.ReactNode
}) {
  return (
    <button
      type="button"
      title={title}
      onClick={onClick}
      className={cn(
        'px-1.5 py-1 text-muted-foreground transition-colors hover:text-foreground',
        active && 'bg-muted text-foreground',
      )}
    >
      {children}
    </button>
  )
}
