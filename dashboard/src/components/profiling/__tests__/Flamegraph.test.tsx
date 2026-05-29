// Moneat - observability platform
// Copyright (C) 2026 Moneat
//
// This program is free software: you can redistribute it and/or modify
// it under the terms of the GNU Affero General Public License as published by
// the Free Software Foundation, either version 3 of the License, or
// (at your option) any later version.

import {describe, it, expect, beforeEach, afterEach, vi} from 'vitest'
import {render, screen, fireEvent, waitFor} from '@testing-library/react'
import {Flamegraph} from '../Flamegraph'
import type {FlameNode} from '../frameModel'

const FRAMES: FlameNode[] = [
  {
    name: 'com.moneat.svc.A.run',
    value: 100,
    children: [
      {name: 'java.lang.Thread.run', value: 60, children: []},
      {name: 'com.moneat.svc.B.work', value: 40, children: []},
    ],
  },
]

describe('Flamegraph', () => {
  beforeEach(() => {
    globalThis.localStorage.clear()
    if (!HTMLElement.prototype.scrollTo) {
      HTMLElement.prototype.scrollTo = vi.fn()
    }
  })

  afterEach(() => {
    vi.restoreAllMocks()
    vi.unstubAllGlobals()
  })

  it('renders the toolbar, a frame label and the legend', () => {
    render(<Flamegraph frames={FRAMES} language="jvm" service="svc-a" />)
    expect(screen.getByPlaceholderText(/Search functions/)).toBeInTheDocument()
    expect(screen.getByText('Hide')).toBeInTheDocument()
    expect(screen.getAllByText('A.run').length).toBeGreaterThan(0)
    // "Your code" multi-select shows the auto-detected namespace.
    expect(screen.getByText(/Auto/)).toBeInTheDocument()
  })

  it('scopes Top Functions between all and app', () => {
    render(<Flamegraph frames={FRAMES} language="jvm" service="svc-b" />)
    // Panel is open by default and scoped to "My code" — runtime frames hidden.
    expect(screen.queryByText('java.lang.Thread.run')).not.toBeInTheDocument()
    expect(screen.getByText('com.moneat.svc.A.run')).toBeInTheDocument()

    // Switching to "All" reveals runtime frames.
    fireEvent.click(screen.getByText('All'))
    expect(screen.getByText('java.lang.Thread.run')).toBeInTheDocument()
  })

  it('toggles bottom-up, hide-system and kind colouring without crashing', () => {
    render(<Flamegraph frames={FRAMES} language="jvm" service="svc-c" />)
    fireEvent.click(screen.getByText('Bottom-up'))
    fireEvent.click(screen.getByText('Dim'))
    fireEvent.click(screen.getByText('Hide'))
    fireEvent.click(screen.getByText('Kind'))
    fireEvent.click(screen.getByLabelText('Fold recursion'))
    fireEvent.change(screen.getByPlaceholderText('custom prefix…'), {
      target: {value: 'com.moneat'},
    })
    fireEvent.click(screen.getByTitle('Reset to auto-detect'))
    expect(screen.getByText('your code')).toBeInTheDocument()
  })

  it('renders sample-type and thread selectors and fires changes', () => {
    const onSampleTypeChange = vi.fn()
    const onThreadChange = vi.fn()
    render(
      <Flamegraph
        frames={FRAMES}
        language="jvm"
        service="svc-d"
        sampleTypes={[
          {key: 'cpu', label: 'CPU', unit: 'samples'},
          {key: 'alloc', label: 'Allocations', unit: 'bytes'},
        ]}
        threads={[{id: 'main', label: 'main', samples: 100}]}
        selectedSampleType="cpu"
        selectedThread={null}
        unit="samples"
        onSampleTypeChange={onSampleTypeChange}
        onThreadChange={onThreadChange}
      />,
    )
    fireEvent.change(screen.getByDisplayValue('CPU'), {target: {value: 'alloc'}})
    expect(onSampleTypeChange).toHaveBeenCalledWith('alloc')

    fireEvent.change(screen.getByDisplayValue('All threads'), {target: {value: 'main'}})
    expect(onThreadChange).toHaveBeenCalledWith('main')
  })

  it('renders an empty state when there are no frames', () => {
    render(<Flamegraph frames={[]} emptyMessage="Nothing here" />)
    expect(screen.getByText('Nothing here')).toBeInTheDocument()
  })

  it('searches, cycles matches, zooms and copies the hovered stack', () => {
    const writeText = vi.fn()
    Object.defineProperty(globalThis.navigator, 'clipboard', {
      configurable: true,
      value: {writeText},
    })
    render(<Flamegraph frames={FRAMES} language="jvm" service="svc-search" />)

    const search = screen.getByPlaceholderText(/Search functions/)
    fireEvent.keyDown(globalThis.window, {key: '/'})
    expect(search).toHaveFocus()

    fireEvent.change(search, {target: {value: 'co'}})
    expect(screen.getByText('3+ chars')).toBeInTheDocument()

    fireEvent.change(search, {target: {value: 'B\\.work'}})
    fireEvent.click(screen.getByLabelText('Regex'))
    expect(screen.getByText('1/1')).toBeInTheDocument()
    fireEvent.keyDown(globalThis.window, {key: 'n'})
    fireEvent.keyDown(globalThis.window, {key: 'N'})

    fireEvent.change(search, {target: {value: 'foo('}})
    expect(search).toHaveClass('border-destructive')

    const frameLabel = screen.getByText('A.run')
    const frame = frameLabel.closest('.group') as HTMLElement
    fireEvent.mouseEnter(frame)
    fireEvent.keyDown(globalThis.window, {key: 'c'})
    expect(writeText).toHaveBeenCalledWith('com.moneat.svc.A.run')

    fireEvent.click(frame)
    expect(screen.getByTitle('Reset zoom')).toBeInTheDocument()
    fireEvent.click(screen.getByText('root'))
    expect(screen.queryByTitle('Reset zoom')).not.toBeInTheDocument()

    fireEvent.keyDown(globalThis.window, {key: 'Escape'})
    expect(search).toHaveValue('')
  })

  it('opens comparison details and applies selected baselines', () => {
    const onCompareChange = vi.fn()
    render(
      <Flamegraph
        frames={FRAMES}
        baselineFrames={[{name: 'com.moneat.svc.A.run', value: 100, children: []}]}
        language="jvm"
        service="svc-compare"
        compareProfiles={[{profileId: 'base-1', label: 'Earlier profile'}]}
        compareId="base-1"
        baselineLoading
        onCompareChange={onCompareChange}
      />,
    )

    fireEvent.click(screen.getByLabelText('Compare profiles'))
    expect(screen.getByText('Compare to baseline:')).toBeInTheDocument()
    fireEvent.change(screen.getByRole('combobox'), {target: {value: ''}})
    expect(onCompareChange).toHaveBeenCalledWith(null)
    fireEvent.click(screen.getByRole('button', {name: /com\.moneat\.svc\.B\.work/}))
    expect(screen.getByPlaceholderText(/Search functions/)).toHaveValue(
      'com.moneat.svc.B.work',
    )
  })

  it('exports visible frames as SVG and PNG', async () => {
    const context = {
      scale: vi.fn(),
      drawImage: vi.fn(),
    }
    vi.spyOn(HTMLCanvasElement.prototype, 'getContext').mockReturnValue(
      context as unknown as CanvasRenderingContext2D,
    )
    vi.spyOn(HTMLCanvasElement.prototype, 'toDataURL')
      .mockReturnValue('data:image/png;base64,abc')
    const click = vi.spyOn(HTMLAnchorElement.prototype, 'click').mockImplementation(() => {})
    vi.stubGlobal('URL', {
      ...globalThis.URL,
      createObjectURL: vi.fn(() => 'blob:flamegraph'),
      revokeObjectURL: vi.fn(),
    })
    vi.stubGlobal('Image', class {
      onload: (() => void) | null = null
      onerror: (() => void) | null = null

      set src(_value: string) {
        this.onload?.()
      }
    })

    render(<Flamegraph frames={FRAMES} language="jvm" service="svc export" />)

    fireEvent.click(screen.getByRole('button', {name: 'SVG'}))
    fireEvent.click(screen.getByRole('button', {name: 'PNG'}))

    await waitFor(() => {
      expect(click).toHaveBeenCalledTimes(2)
    })
    expect(context.drawImage).toHaveBeenCalled()
  })
})
