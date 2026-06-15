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

import {useCallback, useRef, type CSSProperties, type ReactNode} from 'react'
import {AiChatContent} from '@/components/command-palette/AiChatContent'
import {useCommandPalette} from '@/hooks/useCommandPalette'

const MIN_PANEL_PCT = 15
const MAX_PANEL_PCT = 70
const noopSetPanelSize = () => undefined

interface AiSplitPanelProps {
  children: ReactNode
  style?: CSSProperties
  className?: string
}

export function AiSplitPanel({children, style, className}: AiSplitPanelProps) {
  const palette = useCommandPalette()
  const containerRef = useRef<HTMLDivElement>(null)
  const isResizing = useRef(false)

  const orientation = palette?.aiPanelOrientation ?? 'vertical'
  const panelSize = palette?.aiPanelSize ?? 30
  const setPanelSize = palette?.setAiPanelSize ?? noopSetPanelSize

  const isVertical = orientation === 'vertical'

  const startResize = useCallback(
    (e: React.MouseEvent) => {
      e.preventDefault()
      isResizing.current = true

      const onMove = (ev: MouseEvent) => {
        if (!containerRef.current) return
        const rect = containerRef.current.getBoundingClientRect()
        let pct: number
        if (isVertical) {
          const totalWidth = rect.width
          const fromRight = rect.right - ev.clientX
          pct = (fromRight / totalWidth) * 100
        } else {
          const totalHeight = rect.height
          const fromBottom = rect.bottom - ev.clientY
          pct = (fromBottom / totalHeight) * 100
        }
        setPanelSize(Math.min(MAX_PANEL_PCT, Math.max(MIN_PANEL_PCT, pct)))
      }

      const onUp = () => {
        isResizing.current = false
        window.removeEventListener('mousemove', onMove)
        window.removeEventListener('mouseup', onUp)
      }

      window.addEventListener('mousemove', onMove)
      window.addEventListener('mouseup', onUp)
    },
    [isVertical, setPanelSize],
  )

  return (
    <div
      ref={containerRef}
      style={{
        ...style,
        display: 'flex',
        flexDirection: isVertical ? 'row' : 'column',
        overflow: 'hidden',
      }}
      className={className}
    >
      {/* Main content */}
      <div style={{flex: 1, minWidth: 0, minHeight: 0, overflow: 'auto'}}>
        {children}
      </div>

      {/* Resize divider */}
      <div
        onMouseDown={startResize}
        style={{
          flexShrink: 0,
          cursor: isVertical ? 'col-resize' : 'row-resize',
          ...(isVertical ? {width: 4} : {height: 4}),
        }}
        className="bg-border hover:bg-primary/30 transition-colors"
      />

      {/* AI panel */}
      <div
        style={{
          flexShrink: 0,
          ...(isVertical
            ? {width: `${panelSize}%`, minWidth: 260}
            : {height: `${panelSize}%`, minHeight: 220}),
          display: 'flex',
          flexDirection: 'column',
          overflow: 'hidden',
          borderLeft: isVertical ? '1px solid hsl(var(--border))' : undefined,
          borderTop: !isVertical ? '1px solid hsl(var(--border))' : undefined,
        }}
      >
        <AiChatContent variant="panel" />
      </div>
    </div>
  )
}
