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

import {useState, useRef, useEffect} from 'react'
import {useLocation} from '@tanstack/react-router'
import {api} from '@/lib/api'
import type {AiChatResponse, AiChatResponseData, AiConversationSummary, AiSseEvent} from '@/lib/api'
import {Logo} from '@/components/logo'
import {ChatMessage} from './ChatMessage'
import {ActionCard} from './ActionCard'
import {ClarificationCard} from './ClarificationCard'
import {DataQueryResult} from './DataQueryResult'
import {ContextAggregationProgress} from './ContextAggregationProgress'
import {ChatInput} from './ChatInput'
import {cn} from '@/lib/utils'
import {MessageSquare, Minus, X, Trash2, Plus, ChevronLeft} from 'lucide-react'

interface SourceStatus {
  source: string
  status: 'pending' | 'in_progress' | 'done' | 'error'
  count?: number
}

interface Message {
  id: string
  role: 'user' | 'assistant'
  content: string
  response?: AiChatResponseData
  timestamp: Date
  // SSE aggregation state
  searchSources?: SourceStatus[]
  totalTokens?: number
}

export function ChatPanel({onClose, onMinimize}: {onClose: () => void; onMinimize: () => void}) {
  const location = useLocation()
  const [messages, setMessages] = useState<Message[]>([
    {
      id: 'welcome',
      role: 'assistant',
      content: 'Hey! I\'m Moneat AI. I can help you investigate issues, analyze logs, query traces, and more. What would you like to do?',
      timestamp: new Date(),
    },
  ])
  const [conversationId, setConversationId] = useState<number | null>(null)
  const [isLoading, setIsLoading] = useState(false)
  const [showConversations, setShowConversations] = useState(false)
  const [conversations, setConversations] = useState<AiConversationSummary[]>([])
  const [conversationsLoading, setConversationsLoading] = useState(false)
  const [useEnterpriseSse, setUseEnterpriseSse] = useState(true)
  const messagesEndRef = useRef<HTMLDivElement>(null)

  useEffect(() => {
    messagesEndRef.current?.scrollIntoView({behavior: 'smooth'})
  }, [messages])

  const handleSend = async (text: string) => {
    const userMessage: Message = {
      id: `user-${Date.now()}`,
      role: 'user',
      content: text,
      timestamp: new Date(),
    }
    setMessages(prev => [...prev, userMessage])
    setIsLoading(true)

    if (useEnterpriseSse) {
      await handleSseSend(text)
    } else {
      await handleLegacySend(text)
    }
  }

  const handleSseSend = async (text: string) => {
    const progressId = `progress-${Date.now()}`
    const sources: SourceStatus[] = []

    // Add a progress message
    setMessages(prev => [...prev, {
      id: progressId,
      role: 'assistant',
      content: '',
      searchSources: sources,
      timestamp: new Date(),
    }])

    try {
      await api.streamAiSearch(
        {
          conversationId,
          message: text,
          currentPage: location.pathname,
        },
        (event: AiSseEvent) => {
          if (event.phase === 'searching') {
            setMessages(prev => prev.map(m => {
              if (m.id !== progressId) return m
              const updatedSources = [...(m.searchSources ?? [])]
              const idx = updatedSources.findIndex(s => s.source === event.source)
              const entry: SourceStatus = {source: event.source, status: event.status as SourceStatus['status'], count: event.count}
              if (idx >= 0) updatedSources[idx] = entry
              else updatedSources.push(entry)
              return {...m, searchSources: updatedSources}
            }))
          } else if (event.phase === 'context_ready') {
            setMessages(prev => prev.map(m =>
              m.id === progressId
                ? {...m, totalTokens: event.totalTokens}
                : m
            ))
          } else if (event.phase === 'response' && event.done) {
            setMessages(prev => prev.map(m =>
              m.id === progressId
                ? {
                    ...m,
                    searchSources: undefined,
                    content: event.content,
                  }
                : m
            ))
          } else if (event.phase === 'error') {
            setMessages(prev => prev.map(m =>
              m.id === progressId
                ? {...m, content: event.error, searchSources: undefined}
                : m
            ))
          }
        },
      )
    } catch (err) {
      const errMsg = err instanceof Error ? err.message : 'Search failed'
      // If enterprise SSE is not available, fall back to legacy
      if (errMsg.includes('404') || errMsg.includes('405')) {
        setUseEnterpriseSse(false)
        setMessages(prev => prev.filter(m => m.id !== progressId))
        await handleLegacySend(text)
        return
      }
      setMessages(prev => prev.map(m =>
        m.id === progressId ? {...m, content: errMsg, searchSources: undefined} : m
      ))
    } finally {
      setIsLoading(false)
    }
  }

  const handleLegacySend = async (text: string) => {
    try {
      const result: AiChatResponse = await api.sendChatMessage(
        conversationId,
        text,
        location.pathname
      )
      setConversationId(result.conversationId)

      const aiMessage: Message = {
        id: `ai-${Date.now()}`,
        role: 'assistant',
        content: result.response.message,
        response: result.response,
        timestamp: new Date(),
      }
      setMessages(prev => [...prev, aiMessage])
    } catch (err) {
      const errorMessage = err instanceof Error ? err.message : 'Sorry, I encountered an error. Please try again.'
      setMessages(prev => [
        ...prev,
        {
          id: `error-${Date.now()}`,
          role: 'assistant',
          content: errorMessage,
          timestamp: new Date(),
        },
      ])
    } finally {
      setIsLoading(false)
    }
  }

  const handleActionConfirm = async (actionId: string) => {
    if (!conversationId) return
    try {
      const result = await api.executeAiAction(conversationId, actionId)
      setMessages(prev => [
        ...prev,
        {
          id: `action-${Date.now()}`,
          role: 'assistant',
          content: result.message,
          timestamp: new Date(),
        },
      ])
    } catch {
      setMessages(prev => [
        ...prev,
        {
          id: `action-error-${Date.now()}`,
          role: 'assistant',
          content: 'Failed to execute the action. Please try again.',
          timestamp: new Date(),
        },
      ])
    }
  }

  const handleClarificationSelect = (field: string, value: string) => {
    handleSend(`${field}: ${value}`)
  }

  const loadConversations = async () => {
    setConversationsLoading(true)
    try {
      const convos = await api.getAiConversations()
      setConversations(convos)
    } catch {
      // Silently fail
    } finally {
      setConversationsLoading(false)
    }
  }

  const loadConversation = async (id: number) => {
    try {
      const detail = await api.getAiConversation(id)
      setConversationId(id)
      setMessages(
        detail.messages.map(m => ({
          id: `msg-${m.id}`,
          role: m.role as 'user' | 'assistant',
          content: m.content,
          response: m.role === 'assistant' ? tryParseResponse(m.content) : undefined,
          timestamp: new Date(m.createdAt),
        }))
      )
      setShowConversations(false)
    } catch {
      // Silently fail
    }
  }

  const deleteConversation = async (id: number) => {
    try {
      await api.deleteAiConversation(id)
      setConversations(prev => prev.filter(c => c.id !== id))
      if (conversationId === id) {
        startNewConversation()
      }
    } catch {
      // Silently fail
    }
  }

  const startNewConversation = () => {
    setConversationId(null)
    setMessages([
      {
        id: 'welcome',
        role: 'assistant',
        content: 'Hey! I\'m Moneat AI. I can help you investigate issues, analyze logs, query traces, and more. What would you like to do?',
        timestamp: new Date(),
      },
    ])
    setShowConversations(false)
  }

  const toggleConversations = () => {
    if (!showConversations) {
      loadConversations()
    }
    setShowConversations(!showConversations)
  }

  return (
    <div className="fixed bottom-20 right-4 z-50 flex flex-col w-[400px] h-[520px] bg-background border border-border rounded-xl shadow-2xl overflow-hidden">
      {/* Header */}
      <div className="flex items-center justify-between px-4 py-3 border-b border-border bg-card">
        <div className="flex items-center gap-2">
          {showConversations && (
            <button onClick={() => setShowConversations(false)} className="p-1 hover:bg-muted rounded">
              <ChevronLeft className="h-4 w-4" />
            </button>
          )}
          <Logo markOnly className="h-5 w-5" />
          <span className="font-semibold text-sm">Moneat AI</span>
        </div>
        <div className="flex items-center gap-1">
          <button
            onClick={startNewConversation}
            className="p-1.5 hover:bg-muted rounded-md transition-colors"
            title="New conversation"
          >
            <Plus className="h-3.5 w-3.5" />
          </button>
          <button
            onClick={toggleConversations}
            className={cn("p-1.5 hover:bg-muted rounded-md transition-colors", showConversations && "bg-muted")}
            title="Conversations"
          >
            <MessageSquare className="h-3.5 w-3.5" />
          </button>
          <button onClick={onMinimize} className="p-1.5 hover:bg-muted rounded-md transition-colors" title="Minimize">
            <Minus className="h-3.5 w-3.5" />
          </button>
          <button onClick={onClose} className="p-1.5 hover:bg-muted rounded-md transition-colors" title="Close">
            <X className="h-3.5 w-3.5" />
          </button>
        </div>
      </div>

      {showConversations ? (
        /* Conversation list */
        <div className="flex-1 overflow-y-auto p-3 space-y-1">
          {conversationsLoading ? (
            <div className="text-sm text-muted-foreground text-center py-8">Loading...</div>
          ) : conversations.length === 0 ? (
            <div className="text-sm text-muted-foreground text-center py-8">No conversations yet</div>
          ) : (
            conversations.map(c => (
              <div
                key={c.id}
                className={cn(
                  "flex items-center justify-between px-3 py-2 rounded-lg hover:bg-muted cursor-pointer group",
                  conversationId === c.id && "bg-muted"
                )}
                onClick={() => loadConversation(c.id)}
              >
                <div className="flex-1 min-w-0">
                  <p className="text-sm truncate">{c.title || 'Untitled'}</p>
                  <p className="text-xs text-muted-foreground">
                    {new Date(c.updatedAt).toLocaleDateString()}
                  </p>
                </div>
                <button
                  onClick={e => {
                    e.stopPropagation()
                    deleteConversation(c.id)
                  }}
                  className="p-1 opacity-0 group-hover:opacity-100 hover:bg-destructive/10 rounded transition-opacity"
                >
                  <Trash2 className="h-3.5 w-3.5 text-destructive" />
                </button>
              </div>
            ))
          )}
        </div>
      ) : (
        <>
          {/* Messages */}
          <div className="flex-1 overflow-y-auto p-3 space-y-3">
            {messages.map(msg => (
              <div key={msg.id}>
                {/* SSE aggregation progress */}
                {msg.searchSources && msg.searchSources.length > 0 && (
                  <ContextAggregationProgress
                    sources={msg.searchSources}
                    totalTokens={msg.totalTokens}
                  />
                )}
                {/* Regular message content */}
                {msg.content && (
                  <ChatMessage
                    role={msg.role}
                    content={msg.content}
                  />
                )}
                {/* Legacy response components */}
                {msg.response?.actions?.map(action => (
                  <ActionCard
                    key={action.id}
                    action={action}
                    onConfirm={() => handleActionConfirm(action.id)}
                  />
                ))}
                {msg.response?.clarifications?.map(c => (
                  <ClarificationCard
                    key={c.id}
                    clarification={c}
                    onSelect={value => handleClarificationSelect(c.field, value)}
                  />
                ))}
                {msg.response?.data_queries?.map(q => (
                  <DataQueryResult key={q.id} query={q} />
                ))}
                {msg.response?.links && msg.response.links.length > 0 && (
                  <div className="ml-9 mt-1 flex flex-wrap gap-1">
                    {msg.response?.links?.map((link, i) => (
                      <a
                        key={i}
                        href={link.url}
                        className="text-xs text-primary hover:underline bg-primary/5 px-2 py-0.5 rounded"
                      >
                        {link.label} →
                      </a>
                    ))}
                  </div>
                )}
              </div>
            ))}
            {isLoading && (
              <div className="flex items-start gap-2">
                <div className="flex-shrink-0 w-7 h-7 rounded-full bg-primary/10 flex items-center justify-center">
                  <Logo markOnly className="h-4 w-4" />
                </div>
                <div className="bg-muted rounded-lg px-3 py-2 text-sm">
                  <div className="flex gap-1">
                    <span className="w-1.5 h-1.5 bg-muted-foreground/50 rounded-full animate-bounce" style={{animationDelay: '0ms'}} />
                    <span className="w-1.5 h-1.5 bg-muted-foreground/50 rounded-full animate-bounce" style={{animationDelay: '150ms'}} />
                    <span className="w-1.5 h-1.5 bg-muted-foreground/50 rounded-full animate-bounce" style={{animationDelay: '300ms'}} />
                  </div>
                </div>
              </div>
            )}
            <div ref={messagesEndRef} />
          </div>

          {/* Input */}
          <ChatInput onSend={handleSend} disabled={isLoading} />
        </>
      )}
    </div>
  )
}

function tryParseResponse(content: string): AiChatResponseData | undefined {
  try {
    return JSON.parse(content)
  } catch {
    return undefined
  }
}
