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

import {describe, expect, it, vi} from 'vitest'
import {fireEvent, render, screen} from '@testing-library/react'
import {ReplayTimelineScrubber} from '@/components/ReplayTimelineScrubber'
import type {ReplayTimelineItem} from '@/lib/api'

const items: ReplayTimelineItem[] = [
  {id: 's1', type: 'span', timestamp: '2026-06-01T00:00:05.000Z', offsetMs: 5_000, title: 'ui.click'},
  {
    id: 'e1',
    type: 'error',
    timestamp: '2026-06-01T00:00:24.000Z',
    offsetMs: 24_000,
    durationMs: 275,
    title: 'POST /checkout 500',
    description: 'Request failed',
  },
  {id: 'e2', type: 'error', timestamp: '2026-06-01T00:00:46.000Z', offsetMs: 46_000, title: 'POST /checkout 500'},
]

function setup({
  currentOffsetMs = 0,
  isPlaying = false,
  isFullscreen = false,
  onFullscreenToggle,
}: {
  readonly currentOffsetMs?: number
  readonly isPlaying?: boolean
  readonly isFullscreen?: boolean
  readonly onFullscreenToggle?: () => void
} = {}) {
  const onSeek = vi.fn()
  const onPlayPause = vi.fn()
  const onSpeedChange = vi.fn()
  render(
    <ReplayTimelineScrubber
      currentOffsetMs={currentOffsetMs}
      durationMs={84_000}
      isPlaying={isPlaying}
      items={items}
      onSeek={onSeek}
      onPlayPause={onPlayPause}
      onSpeedChange={onSpeedChange}
      speed={1}
      onFullscreenToggle={onFullscreenToggle}
      isFullscreen={isFullscreen}
    />
  )
  return {onSeek, onPlayPause, onSpeedChange}
}

describe('ReplayTimelineScrubber', () => {
  it('renders a marker per timeline item with an accessible clock', () => {
    setup()
    expect(screen.getByRole('slider')).toHaveAttribute('aria-valuetext', '0:00 / 1:24')
    // one seek button per marker
    expect(screen.getAllByRole('button', {name: /at 0:/})).toHaveLength(3)
  })

  it('jumps to the next error after the current time', () => {
    const {onSeek} = setup({currentOffsetMs: 10_000})
    fireEvent.click(screen.getByRole('button', {name: /Next error/}))
    expect(onSeek).toHaveBeenCalledWith(24_000)
  })

  it('wraps to the first error when none remain ahead', () => {
    const {onSeek} = setup({currentOffsetMs: 60_000})
    fireEvent.click(screen.getByRole('button', {name: /Next error/}))
    expect(onSeek).toHaveBeenCalledWith(24_000)
  })

  it('toggles play and cycles speed', () => {
    const {onPlayPause, onSpeedChange} = setup()
    fireEvent.click(screen.getByRole('button', {name: 'Play'}))
    expect(onPlayPause).toHaveBeenCalled()
    fireEvent.click(screen.getByRole('button', {name: '1x'}))
    expect(onSpeedChange).toHaveBeenCalledWith(1.5)
  })

  it('supports keyboard seeking from the slider', () => {
    const {onSeek} = setup({currentOffsetMs: 42_000})
    const slider = screen.getByRole('slider')

    fireEvent.keyDown(slider, {key: 'ArrowRight'})
    fireEvent.keyDown(slider, {key: 'ArrowLeft'})
    fireEvent.keyDown(slider, {key: 'Home'})
    fireEvent.keyDown(slider, {key: 'End'})

    expect(onSeek).toHaveBeenNthCalledWith(1, 46_200)
    expect(onSeek).toHaveBeenNthCalledWith(2, 37_800)
    expect(onSeek).toHaveBeenNthCalledWith(3, 0)
    expect(onSeek).toHaveBeenNthCalledWith(4, 84_000)
  })

  it('seeks from the track, marker, skip, and fullscreen controls', () => {
    const onFullscreenToggle = vi.fn()
    const {onSeek} = setup({currentOffsetMs: 40_000, isPlaying: true, onFullscreenToggle})
    const slider = screen.getByRole('slider')
    vi.spyOn(slider, 'getBoundingClientRect').mockReturnValue({
      x: 0,
      y: 0,
      left: 10,
      top: 0,
      right: 110,
      bottom: 6,
      width: 100,
      height: 6,
      toJSON: () => ({}),
    })

    fireEvent.mouseDown(slider, {clientX: 50})
    globalThis.dispatchEvent(new MouseEvent('mousemove', {clientX: 110}))
    globalThis.dispatchEvent(new MouseEvent('mouseup'))
    fireEvent.click(screen.getByRole('button', {name: /ui\.click at 0:05/}))
    fireEvent.click(screen.getByRole('button', {name: 'Skip to next event'}))
    fireEvent.click(screen.getByRole('button', {name: 'Fullscreen'}))

    expect(onSeek).toHaveBeenNthCalledWith(1, 33_600)
    expect(onSeek).toHaveBeenNthCalledWith(2, 84_000)
    expect(onSeek).toHaveBeenNthCalledWith(3, 5_000)
    expect(onSeek).toHaveBeenNthCalledWith(4, 46_000)
    expect(onFullscreenToggle).toHaveBeenCalled()
  })

  it('renders marker hover details', () => {
    setup()
    fireEvent.mouseEnter(screen.getByRole('button', {name: /POST \/checkout 500 at 0:24/}))

    expect(screen.getByText('Request failed')).toBeInTheDocument()
    expect(screen.getByText('(275ms)')).toBeInTheDocument()
  })

  it('clamps navigation without markers or optional controls', () => {
    const onSeek = vi.fn()
    render(
      <ReplayTimelineScrubber
        currentOffsetMs={4_000}
        durationMs={84_000}
        isPlaying={false}
        items={[]}
        onSeek={onSeek}
        onPlayPause={vi.fn()}
      />
    )

    fireEvent.click(screen.getByRole('button', {name: 'Rewind 10 seconds'}))
    fireEvent.click(screen.getByRole('button', {name: 'Skip to next event'}))

    expect(onSeek).toHaveBeenNthCalledWith(1, 0)
    expect(onSeek).toHaveBeenNthCalledWith(2, 14_000)
    expect(screen.queryByRole('button', {name: /Next error/})).not.toBeInTheDocument()
    expect(screen.queryByRole('button', {name: '1x'})).not.toBeInTheDocument()
  })
})
