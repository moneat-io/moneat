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

import {render, screen, fireEvent} from '@testing-library/react'
import {describe, it, expect, vi} from 'vitest'

import {TimeRangePicker} from '@/components/filters/TimeRangePicker'
import type {TimeRangePreset} from '@/lib/filters/time'

vi.mock('@/components/ui/datetime-picker', () => ({
  DateTimePicker: ({
    value,
    onChange,
    placeholder,
  }: {
    value: string
    onChange: (value: string) => void
    placeholder: string
  }) => (
    <input
      aria-label={placeholder}
      value={value}
      onChange={(event) => onChange(event.target.value)}
    />
  ),
}))

const presets: TimeRangePreset[] = [
  {value: '1h', label: 'Last hour', minutes: 60},
  {value: '24h', label: 'Last 24 hours', minutes: 1440},
]

function setup(props: Partial<React.ComponentProps<typeof TimeRangePicker>> = {}) {
  const onTimePresetChange = vi.fn()
  const onCustomFromChange = vi.fn()
  const onCustomToChange = vi.fn()
  render(
    <TimeRangePicker
      timePreset="1h"
      onTimePresetChange={onTimePresetChange}
      customFrom=""
      customTo=""
      onCustomFromChange={onCustomFromChange}
      onCustomToChange={onCustomToChange}
      presets={presets}
      {...props}
    />
  )
  return {onTimePresetChange, onCustomFromChange, onCustomToChange}
}

describe('TimeRangePicker', () => {
  it('shows the active preset and applies another preset', () => {
    const {onTimePresetChange} = setup()

    fireEvent.click(screen.getByRole('button', {name: /Last hour/}))
    fireEvent.click(screen.getByRole('button', {name: 'Last 24 hours'}))

    expect(onTimePresetChange).toHaveBeenCalledWith('24h')
    expect(screen.queryByRole('button', {name: 'Last 24 hours'})).toBeNull()
  })

  it('uses the default preset list when no preset list is provided', () => {
    render(
      <TimeRangePicker
        timePreset="15m"
        onTimePresetChange={() => {}}
        customFrom=""
        customTo=""
        onCustomFromChange={() => {}}
        onCustomToChange={() => {}}
      />
    )

    fireEvent.click(screen.getByRole('button', {name: /Last 15 minutes/}))
    expect(screen.getByRole('button', {name: 'Last 5 minutes'})).toBeTruthy()
  })

  it('falls back when a preset is unknown or unavailable', () => {
    const {rerender} = render(
      <TimeRangePicker
        timePreset="missing"
        onTimePresetChange={() => {}}
        customFrom=""
        customTo=""
        onCustomFromChange={() => {}}
        onCustomToChange={() => {}}
        presets={presets}
      />
    )
    expect(screen.getByRole('button', {name: /Last hour/})).toBeTruthy()

    rerender(
      <TimeRangePicker
        timePreset="missing"
        onTimePresetChange={() => {}}
        customFrom=""
        customTo=""
        onCustomFromChange={() => {}}
        onCustomToChange={() => {}}
        presets={[]}
      />
    )
    expect(screen.getByRole('button', {name: /Custom range/})).toBeTruthy()
  })

  it('formats custom range labels', () => {
    const {rerender} = render(
      <TimeRangePicker
        timePreset="custom"
        onTimePresetChange={() => {}}
        customFrom=""
        customTo=""
        onCustomFromChange={() => {}}
        onCustomToChange={() => {}}
        presets={presets}
      />
    )
    expect(screen.getByRole('button', {name: /Custom range/})).toBeTruthy()

    rerender(
      <TimeRangePicker
        timePreset="custom"
        onTimePresetChange={() => {}}
        customFrom="2026-03-14T10:00"
        customTo="2026-03-14T11:30"
        onCustomFromChange={() => {}}
        onCustomToChange={() => {}}
        presets={presets}
      />
    )
    expect(screen.getByRole('button', {name: /Mar 14, 10:00 - 11:30/})).toBeTruthy()

    rerender(
      <TimeRangePicker
        timePreset="custom"
        onTimePresetChange={() => {}}
        customFrom="2026-03-14T10:00"
        customTo="2026-03-15T11:30"
        onCustomFromChange={() => {}}
        onCustomToChange={() => {}}
        presets={presets}
      />
    )
    expect(screen.getByRole('button', {name: /Mar 14, 10:00 - Mar 15, 11:30/})).toBeTruthy()
  })

  it('formats partial and invalid custom ranges', () => {
    const {rerender} = render(
      <TimeRangePicker
        timePreset="custom"
        onTimePresetChange={() => {}}
        customFrom="2026-03-14T10:00"
        customTo=""
        onCustomFromChange={() => {}}
        onCustomToChange={() => {}}
        presets={presets}
      />
    )
    expect(screen.getByRole('button', {name: /From Mar 14, 10:00/})).toBeTruthy()

    rerender(
      <TimeRangePicker
        timePreset="custom"
        onTimePresetChange={() => {}}
        customFrom=""
        customTo="2026-03-15T11:30"
        onCustomFromChange={() => {}}
        onCustomToChange={() => {}}
        presets={presets}
      />
    )
    expect(screen.getByRole('button', {name: /Until Mar 15, 11:30/})).toBeTruthy()

    rerender(
      <TimeRangePicker
        timePreset="custom"
        onTimePresetChange={() => {}}
        customFrom="bad-date"
        customTo=""
        onCustomFromChange={() => {}}
        onCustomToChange={() => {}}
        presets={presets}
      />
    )
    expect(screen.getByRole('button', {name: /Custom range/})).toBeTruthy()
  })

  it('forwards the custom preset selection', () => {
    const {onTimePresetChange} = setup()

    fireEvent.click(screen.getByRole('button', {name: /Last hour/}))
    fireEvent.click(screen.getByRole('button', {name: 'Custom range...'}))

    expect(onTimePresetChange).toHaveBeenCalledWith('custom')
  })

  it('opens custom inputs and forwards custom changes', () => {
    const {onCustomFromChange, onCustomToChange} = setup({timePreset: 'custom'})

    fireEvent.click(screen.getByRole('button', {name: /Custom range/}))
    fireEvent.change(screen.getByLabelText('Select start time'), {
      target: {value: '2026-03-14T10:00'},
    })
    fireEvent.change(screen.getByLabelText('Select end time'), {
      target: {value: '2026-03-14T11:00'},
    })

    expect(onCustomFromChange).toHaveBeenCalledWith('2026-03-14T10:00')
    expect(onCustomToChange).toHaveBeenCalledWith('2026-03-14T11:00')
  })

  it('hides custom controls when disabled and closes on outside click', () => {
    setup({allowCustom: false})

    fireEvent.click(screen.getByRole('button', {name: /Last hour/}))
    expect(screen.queryByRole('button', {name: 'Custom range...'})).toBeNull()
    expect(screen.getByRole('button', {name: 'Last 24 hours'})).toBeTruthy()

    fireEvent.mouseDown(screen.getByRole('button', {name: 'Last 24 hours'}))
    expect(screen.getByRole('button', {name: 'Last 24 hours'})).toBeTruthy()

    fireEvent.mouseDown(document.body)
    expect(screen.queryByRole('button', {name: 'Last 24 hours'})).toBeNull()
  })
})
