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
  {id: 'e1', type: 'error', timestamp: '2026-06-01T00:00:24.000Z', offsetMs: 24_000, title: 'POST /checkout 500'},
  {id: 'e2', type: 'error', timestamp: '2026-06-01T00:00:46.000Z', offsetMs: 46_000, title: 'POST /checkout 500'},
]

function setup(currentOffsetMs = 0) {
  const onSeek = vi.fn()
  const onPlayPause = vi.fn()
  const onSpeedChange = vi.fn()
  render(
    <ReplayTimelineScrubber
      currentOffsetMs={currentOffsetMs}
      durationMs={84_000}
      isPlaying={false}
      items={items}
      onSeek={onSeek}
      onPlayPause={onPlayPause}
      onSpeedChange={onSpeedChange}
      speed={1}
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
    const {onSeek} = setup(10_000)
    fireEvent.click(screen.getByRole('button', {name: /Next error/}))
    expect(onSeek).toHaveBeenCalledWith(24_000)
  })

  it('wraps to the first error when none remain ahead', () => {
    const {onSeek} = setup(60_000)
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
})
