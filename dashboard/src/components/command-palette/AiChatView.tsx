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

import {useEffect, useRef} from 'react'
import ReactMarkdown from 'react-markdown'
import type {
  AiPaletteMessage,
  AiPalettePendingConfirmation,
  AiPaletteToolInvocation,
} from '@/contexts/CommandPaletteContext'
import {ToolInvocation} from '@/components/command-palette/ToolInvocation'
import {ConfirmationCard} from '@/components/command-palette/ConfirmationCard'
import {cn} from '@/lib/utils'

interface AiChatViewProps {
  messages: AiPaletteMessage[]
  toolInvocations: AiPaletteToolInvocation[]
  pendingConfirmation: AiPalettePendingConfirmation | null
  loading: boolean
  confirming: boolean
  onApprove: () => void
  onDeny: () => void
}

export function AiChatView({
  messages,
  toolInvocations,
  pendingConfirmation,
  loading,
  confirming,
  onApprove,
  onDeny,
}: AiChatViewProps) {
  const endRef = useRef<HTMLDivElement>(null)

  useEffect(() => {
    endRef.current?.scrollIntoView({behavior: 'smooth'})
  }, [messages, toolInvocations, pendingConfirmation, loading])

  const lastUserIdx = messages.findLastIndex((m: AiPaletteMessage) => m.role === 'user')
  const toolInsertIdx = lastUserIdx >= 0 ? lastUserIdx + 1 : messages.length
  const hasActiveTools = toolInvocations.some((t) => t.status === 'invoking')

  return (
    <div className="space-y-3 px-3 py-3">
      {messages.slice(0, toolInsertIdx).map((message) => (
        <MessageBubble key={message.id} message={message} />
      ))}

      {toolInvocations.length > 0 && (
        <div className="rounded-lg border border-border/50 bg-muted/20 py-0.5">
          {toolInvocations.map((invocation) => (
            <ToolInvocation key={invocation.id} invocation={invocation} />
          ))}
        </div>
      )}

      {messages.slice(toolInsertIdx).map((message) => (
        <MessageBubble key={message.id} message={message} />
      ))}

      {pendingConfirmation && (
        <ConfirmationCard
          confirmation={pendingConfirmation}
          loading={confirming}
          onApprove={onApprove}
          onDeny={onDeny}
        />
      )}

      {loading && !hasActiveTools && (
        <p className="text-xs text-muted-foreground animate-pulse">Thinking…</p>
      )}

      <div ref={endRef} />
    </div>
  )
}

function MessageBubble({message}: {message: AiPaletteMessage}) {
  return (
    <div className={cn('text-sm', message.role === 'user' && 'text-right')}>
      {message.role === 'user' ? (
        <div className="inline-block max-w-[90%] rounded-lg bg-primary px-3 py-1.5 text-primary-foreground">
          {message.content}
        </div>
      ) : (
        <div className="prose prose-sm max-w-none dark:prose-invert">
          <ReactMarkdown>{message.content}</ReactMarkdown>
        </div>
      )}
    </div>
  )
}
