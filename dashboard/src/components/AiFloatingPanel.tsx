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

import {useCallback, useEffect, useRef, useState} from 'react'
import {MessageSquare, X} from 'lucide-react'
import {AiChatContent} from '@/components/command-palette/AiChatContent'
import {useCommandPalette} from '@/hooks/useCommandPalette'

const DEFAULT_WIDTH = 420
const DEFAULT_HEIGHT = 560
const MIN_WIDTH = 300
const MIN_HEIGHT = 360
const RESIZE_HANDLE = 8

export function AiFloatingPanel() {
  const palette = useCommandPalette()
  const [pos, setPos] = useState({x: window.innerWidth - DEFAULT_WIDTH - 24, y: 80})
  const [size, setSize] = useState({w: DEFAULT_WIDTH, h: DEFAULT_HEIGHT})
  const dragOffset = useRef<{x: number; y: number} | null>(null)
  const resizeStart = useRef<{mx: number; my: number; w: number; h: number} | null>(null)
  const panelRef = useRef<HTMLDivElement>(null)

  // Keep panel within viewport on resize
  useEffect(() => {
    const onResize = () => {
      setPos((p) => ({
        x: Math.min(p.x, window.innerWidth - size.w - 8),
        y: Math.min(p.y, window.innerHeight - size.h - 8),
      }))
    }
    window.addEventListener('resize', onResize)
    return () => window.removeEventListener('resize', onResize)
  }, [size])

  const startDrag = useCallback((e: React.MouseEvent) => {
    e.preventDefault()
    dragOffset.current = {x: e.clientX - pos.x, y: e.clientY - pos.y}

    const onMove = (ev: MouseEvent) => {
      if (!dragOffset.current) return
      setPos({
        x: Math.max(0, Math.min(ev.clientX - dragOffset.current.x, window.innerWidth - size.w)),
        y: Math.max(0, Math.min(ev.clientY - dragOffset.current.y, window.innerHeight - size.h)),
      })
    }
    const onUp = () => {
      dragOffset.current = null
      window.removeEventListener('mousemove', onMove)
      window.removeEventListener('mouseup', onUp)
    }
    window.addEventListener('mousemove', onMove)
    window.addEventListener('mouseup', onUp)
  }, [pos, size])

  const startResize = useCallback((e: React.MouseEvent) => {
    e.preventDefault()
    resizeStart.current = {mx: e.clientX, my: e.clientY, w: size.w, h: size.h}

    const onMove = (ev: MouseEvent) => {
      if (!resizeStart.current) return
      const dx = ev.clientX - resizeStart.current.mx
      const dy = ev.clientY - resizeStart.current.my
      setSize({
        w: Math.max(MIN_WIDTH, resizeStart.current.w + dx),
        h: Math.max(MIN_HEIGHT, resizeStart.current.h + dy),
      })
    }
    const onUp = () => {
      resizeStart.current = null
      window.removeEventListener('mousemove', onMove)
      window.removeEventListener('mouseup', onUp)
    }
    window.addEventListener('mousemove', onMove)
    window.addEventListener('mouseup', onUp)
  }, [size])

  if (!palette || palette.aiPanelMode !== 'float') return null

  return (
    <div
      ref={panelRef}
      className="fixed z-50 flex flex-col rounded-xl border border-border bg-background shadow-2xl"
      style={{
        left: pos.x,
        top: pos.y,
        width: size.w,
        height: size.h,
        minWidth: MIN_WIDTH,
        minHeight: MIN_HEIGHT,
      }}
    >
      {/* Drag handle / header */}
      <div
        className="flex shrink-0 cursor-grab items-center gap-2 rounded-t-xl border-b bg-muted/30 px-3 py-2 active:cursor-grabbing select-none"
        onMouseDown={startDrag}
      >
        <MessageSquare className="h-3.5 w-3.5 text-muted-foreground" />
        <span className="flex-1 text-xs font-medium text-muted-foreground">Ask AI</span>
        <button
          type="button"
          title="Close panel"
          onMouseDown={(e) => e.stopPropagation()}
          onClick={() => palette.setAiPanelMode('dialog')}
          className="rounded p-0.5 text-muted-foreground hover:bg-muted hover:text-foreground transition-colors"
        >
          <X className="h-3.5 w-3.5" />
        </button>
      </div>

      {/* Chat content (fills remaining space) */}
      <div className="min-h-0 flex-1 overflow-hidden">
        {/* Pass variant=panel so AiChatContent fills height and hides the mode-switcher header (already in drag header) */}
        <AiChatContentNoHeader />
      </div>

      {/* Resize handle — bottom-right corner */}
      <div
        className="absolute bottom-0 right-0 cursor-se-resize rounded-br-xl"
        style={{width: RESIZE_HANDLE * 3, height: RESIZE_HANDLE * 3}}
        onMouseDown={startResize}
      >
        <svg
          className="absolute bottom-1.5 right-1.5 h-3 w-3 text-muted-foreground/40"
          viewBox="0 0 6 6"
          fill="currentColor"
        >
          <rect x="4" y="0" width="2" height="2" />
          <rect x="4" y="4" width="2" height="2" />
          <rect x="0" y="4" width="2" height="2" />
        </svg>
      </div>
    </div>
  )
}

/** AiChatContent without the mode-switcher header row — used inside the float panel
 *  which renders its own header/drag bar. */
function AiChatContentNoHeader() {
  // We render AiChatContent with variant='panel'; the header inside it is compact
  // and still has the mode-switcher so users can switch back.
  return <AiChatContent variant="panel" />
}
