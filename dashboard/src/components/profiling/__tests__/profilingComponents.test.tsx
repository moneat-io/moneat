// Moneat - observability platform
// Copyright (C) 2026 Moneat
//
// This program is free software: you can redistribute it and/or modify
// it under the terms of the GNU Affero General Public License as published by
// the Free Software Foundation, either version 3 of the License, or
// (at your option) any later version.

import {describe, it, expect, vi, afterEach} from 'vitest'
import {render, screen, fireEvent} from '@testing-library/react'
import {AppPackageSelect} from '../AppPackageSelect'
import {CompareBar, type CompareProfile} from '../CompareBar'
import {FlamegraphLegend} from '../FlamegraphLegend'
import {FlamegraphMinimap, type MiniRect} from '../FlamegraphMinimap'
import {ThreadSampleTypeSelectors} from '../ThreadSampleTypeSelectors'
import {TopFunctionsPanel} from '../TopFunctionsPanel'
import type {DiffResult, TopFunction} from '../frameModel'

afterEach(() => {
  vi.restoreAllMocks()
  vi.unstubAllGlobals()
})

describe('AppPackageSelect', () => {
  it('summarizes auto-detected and manual namespace selections', () => {
    const onChange = vi.fn()
    const namespaces = [
      {namespace: 'com.moneat', self: 70, total: 100},
      {namespace: 'com.worker', self: 20, total: 30},
    ]

    const {rerender} = render(
      <AppPackageSelect
        namespaces={namespaces}
        value=""
        effective={['com.moneat']}
        onChange={onChange}
      />,
    )
    expect(screen.getByText('Auto · com.moneat')).toBeInTheDocument()

    rerender(
      <AppPackageSelect
        namespaces={namespaces}
        value="com.moneat,com.worker"
        effective={['com.moneat']}
        onChange={onChange}
      />,
    )
    expect(screen.getByText('2 selected')).toBeInTheDocument()
  })
})

describe('CompareBar', () => {
  const profiles: CompareProfile[] = [
    {profileId: 'base-1', label: 'Baseline one'},
    {profileId: 'base-2', label: 'Baseline two'},
  ]

  const diff: DiffResult = {
    deltaByName: new Map([['com.app.Hot.run', 4.5]]),
    topRegressions: [
      {
        name: 'com.app.Hot.run',
        currentPercent: 6,
        basePercent: 1.5,
        deltaPercent: 4.5,
      },
    ],
    topImprovements: [],
  }

  it('changes, clears and selects comparison deltas', () => {
    const onChange = vi.fn()
    const onSelect = vi.fn()
    render(
      <CompareBar
        profiles={profiles}
        compareId="base-1"
        loading
        diff={diff}
        onChange={onChange}
        onSelect={onSelect}
      />,
    )

    fireEvent.change(screen.getByRole('combobox'), {target: {value: 'base-2'}})
    expect(onChange).toHaveBeenCalledWith('base-2')

    fireEvent.change(screen.getByRole('combobox'), {target: {value: ''}})
    expect(onChange).toHaveBeenCalledWith(null)

    fireEvent.click(screen.getByRole('button', {name: /Clear/}))
    expect(onChange).toHaveBeenLastCalledWith(null)

    fireEvent.click(screen.getByRole('button', {name: /com\.app\.Hot\.run/}))
    expect(onSelect).toHaveBeenCalledWith('com.app.Hot.run')
    expect(screen.getByText('No significant change.')).toBeInTheDocument()
  })
})

describe('ThreadSampleTypeSelectors', () => {
  it('renders nothing without selectable dimensions', () => {
    const {container} = render(
      <ThreadSampleTypeSelectors
        sampleTypes={[{key: 'cpu', label: 'CPU', unit: 'samples'}]}
        threads={[]}
        onSampleTypeChange={vi.fn()}
        onThreadChange={vi.fn()}
      />,
    )
    expect(container).toBeEmptyDOMElement()
  })

  it('renders thread-only selectors and clears thread selection', () => {
    const onThreadChange = vi.fn()
    render(
      <ThreadSampleTypeSelectors
        sampleTypes={[{key: 'cpu', label: 'CPU', unit: 'samples'}]}
        threads={[{id: 'main', label: 'main', samples: 1234}]}
        selectedThread="main"
        onSampleTypeChange={vi.fn()}
        onThreadChange={onThreadChange}
      />,
    )

    expect(screen.queryByText(/Type/)).not.toBeInTheDocument()
    expect(screen.getByDisplayValue('main (1,234)')).toBeInTheDocument()
    fireEvent.change(screen.getByRole('combobox'), {target: {value: ''}})
    expect(onThreadChange).toHaveBeenCalledWith(null)
  })
})

describe('FlamegraphLegend', () => {
  it('marks selected app namespaces and groups overflow packages', () => {
    render(
      <FlamegraphLegend
        mode="package"
        appPrefixes={['com.moneat']}
        namespaces={[
          {namespace: 'com.moneat', self: 6, total: 10},
          {namespace: 'com.alpha', self: 5, total: 8},
          {namespace: 'com.beta', self: 4, total: 7},
          {namespace: 'com.gamma', self: 3, total: 6},
          {namespace: 'com.delta', self: 2, total: 5},
          {namespace: 'com.epsilon', self: 1, total: 4},
          {namespace: 'com.zeta', self: 1, total: 3},
        ]}
      />,
    )

    expect(screen.getByText('com.moneat (you)')).toBeInTheDocument()
    expect(screen.getByText('other')).toBeInTheDocument()
  })

  it('renders the kind legend', () => {
    render(<FlamegraphLegend mode="kind" namespaces={[]} appPrefixes={[]} />)
    expect(screen.getByText('runtime / system')).toBeInTheDocument()
  })
})

describe('TopFunctionsPanel', () => {
  const functions: TopFunction[] = [
    {
      name: 'com.app.Hot.run',
      kind: 'app',
      selfValue: 25,
      totalValue: 80,
      selfPercent: 25,
      totalPercent: 80,
    },
  ]

  it('fires scope, sort, select and copy actions', () => {
    const onScopeChange = vi.fn()
    const onSortChange = vi.fn()
    const onSelect = vi.fn()
    const onCopy = vi.fn()
    render(
      <TopFunctionsPanel
        functions={functions}
        scope="app"
        sortBy="self"
        hasAppPrefixes
        onScopeChange={onScopeChange}
        onSortChange={onSortChange}
        onSelect={onSelect}
        onCopy={onCopy}
        colorOf={() => 'hsl(142, 60%, 45%)'}
      />,
    )

    expect(screen.getByText('1 function')).toBeInTheDocument()
    fireEvent.click(screen.getByRole('button', {name: 'All'}))
    expect(onScopeChange).toHaveBeenCalledWith('all')

    fireEvent.click(screen.getByRole('button', {name: /Total/}))
    expect(onSortChange).toHaveBeenCalledWith('total')

    fireEvent.click(screen.getByText('com.app.Hot.run'))
    expect(onSelect).toHaveBeenCalledWith('com.app.Hot.run')

    fireEvent.click(screen.getByLabelText('Copy function name'))
    expect(onCopy).toHaveBeenCalledWith('com.app.Hot.run')
  })

  it('shows the empty app-scope state and disables app scope without prefixes', () => {
    render(
      <TopFunctionsPanel
        functions={[]}
        scope="all"
        sortBy="total"
        hasAppPrefixes={false}
        onScopeChange={vi.fn()}
        onSortChange={vi.fn()}
        onSelect={vi.fn()}
        onCopy={vi.fn()}
        colorOf={() => 'hsl(142, 60%, 45%)'}
      />,
    )

    expect(screen.getByText('0 functions')).toBeInTheDocument()
    expect(screen.getByRole('button', {name: 'My code'})).toBeDisabled()
    expect(screen.getByText('No functions match this scope.')).toBeInTheDocument()
  })
})

describe('FlamegraphMinimap', () => {
  const rects: MiniRect[] = [
    {depth: 0, x: 0, width: 100, color: 'hsl(142, 60%, 45%)'},
    {depth: 1, x: 25, width: 50, color: 'hsl(205, 32%, 46%)'},
  ]

  it('draws frames and keeps pointer scrubbing captured', () => {
    const context = {
      setTransform: vi.fn(),
      clearRect: vi.fn(),
      fillRect: vi.fn(),
      strokeRect: vi.fn(),
      fillStyle: '',
      strokeStyle: '',
      lineWidth: 0,
    }
    vi.spyOn(HTMLCanvasElement.prototype, 'getContext').mockReturnValue(
      context as unknown as CanvasRenderingContext2D,
    )
    HTMLCanvasElement.prototype.setPointerCapture = vi.fn()
    HTMLCanvasElement.prototype.releasePointerCapture = vi.fn()
    vi.stubGlobal('ResizeObserver', class {
      constructor(private readonly callback: ResizeObserverCallback) {}

      observe(target: Element) {
        this.callback([
          {
            target,
            contentRect: {width: 360},
          } as ResizeObserverEntry,
        ], this as unknown as ResizeObserver)
      }

      disconnect() {}
      unobserve() {}
    })

    const onScrollToFraction = vi.fn()
    render(
      <FlamegraphMinimap
        rects={rects}
        rows={2}
        chartHeight={400}
        scrollTop={40}
        viewportHeight={100}
        onScrollToFraction={onScrollToFraction}
      />,
    )

    const canvas = document.querySelector('canvas') as HTMLCanvasElement
    Object.defineProperty(canvas, 'getBoundingClientRect', {
      configurable: true,
      value: () => ({
        top: 0,
        left: 0,
        width: 600,
        height: 56,
        right: 600,
        bottom: 56,
        x: 0,
        y: 0,
        toJSON: () => ({}),
      }),
    })

    expect(context.fillRect).toHaveBeenCalled()
    fireEvent.pointerDown(canvas, {clientY: 14, pointerId: 1})
    fireEvent.pointerMove(canvas, {clientY: 70, pointerId: 1})
    fireEvent.pointerUp(canvas, {clientY: 70, pointerId: 1})

    expect(canvas.setPointerCapture).toHaveBeenCalledWith(1)
    expect(canvas.releasePointerCapture).toHaveBeenCalledWith(1)
    expect(onScrollToFraction).toHaveBeenNthCalledWith(1, 0.25)
    expect(onScrollToFraction).toHaveBeenLastCalledWith(1)
  })
})
