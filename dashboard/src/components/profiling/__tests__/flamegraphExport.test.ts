// Moneat - observability platform
// Copyright (C) 2026 Moneat
//
// This program is free software: you can redistribute it and/or modify
// it under the terms of the GNU Affero General Public License as published by
// the Free Software Foundation, either version 3 of the License, or
// (at your option) any later version.

import {describe, it, expect, vi, afterEach} from 'vitest'
import {downloadPng, framesToSvg, svgDimensions, type ExportFrame} from '../flamegraphExport'

const FRAMES: ExportFrame[] = [
  {name: 'com.app.A.run', depth: 0, x: 0, width: 100, color: 'hsl(142,55%,42%)'},
  {name: 'a<b>&c', depth: 1, x: 0, width: 50, color: '#ffffff'},
]

afterEach(() => {
  vi.restoreAllMocks()
  vi.unstubAllGlobals()
})

describe('framesToSvg', () => {
  it('emits a rect per frame and escapes labels/title', () => {
    const svg = framesToSvg(FRAMES, {width: 800, rowHeight: 20, title: 't & <x>'})
    expect(svg.startsWith('<svg')).toBe(true)
    expect((svg.match(/<rect/g) || []).length).toBeGreaterThanOrEqual(3)
    expect(svg).toContain('&amp;')
    expect(svg).not.toContain('a<b>')
  })

  it('derives height from the deepest frame', () => {
    const {width, height} = svgDimensions(FRAMES, {width: 800, rowHeight: 20})
    expect(width).toBe(800)
    expect(height).toBeGreaterThan(40)
  })
})

describe('downloadPng', () => {
  it('rasterizes the SVG and revokes the object URL', async () => {
    const context = {
      scale: vi.fn(),
      drawImage: vi.fn(),
    }
    vi.spyOn(HTMLCanvasElement.prototype, 'getContext').mockReturnValue(
      context as unknown as CanvasRenderingContext2D,
    )
    vi.spyOn(HTMLCanvasElement.prototype, 'toDataURL').mockReturnValue('data:image/png;base64,abc')
    const click = vi.spyOn(HTMLAnchorElement.prototype, 'click').mockImplementation(() => {})
    const createObjectURL = vi.fn(() => 'blob:flamegraph')
    const revokeObjectURL = vi.fn()
    vi.stubGlobal('URL', {
      ...globalThis.URL,
      createObjectURL,
      revokeObjectURL,
    })
    vi.stubGlobal('Image', class {
      onload: (() => void) | null = null
      onerror: (() => void) | null = null

      set src(_value: string) {
        this.onload?.()
      }
    })
    Object.defineProperty(globalThis.window, 'devicePixelRatio', {
      configurable: true,
      value: 2,
    })

    await downloadPng('<svg />', 100, 50, 'flamegraph.png')

    expect(createObjectURL).toHaveBeenCalled()
    expect(context.scale).toHaveBeenCalledWith(2, 2)
    expect(context.drawImage).toHaveBeenCalledWith(expect.any(Object), 0, 0, 100, 50)
    expect(click).toHaveBeenCalled()
    expect(revokeObjectURL).toHaveBeenCalledWith('blob:flamegraph')
  })
})
