// Moneat - observability platform
// Copyright (C) 2026 Moneat
//
// This program is free software: you can redistribute it and/or modify
// it under the terms of the GNU Affero General Public License as published by
// the Free Software Foundation, either version 3 of the License, or
// (at your option) any later version.

import {describe, it, expect, beforeEach, vi} from 'vitest'
import {render, screen, fireEvent} from '@testing-library/react'
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
    localStorage.clear()
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
    fireEvent.click(screen.getByText('Hide'))
    fireEvent.click(screen.getByText('Kind'))
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
})
