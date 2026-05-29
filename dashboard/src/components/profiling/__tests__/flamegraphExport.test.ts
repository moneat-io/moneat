// Moneat - observability platform
// Copyright (C) 2026 Moneat
//
// This program is free software: you can redistribute it and/or modify
// it under the terms of the GNU Affero General Public License as published by
// the Free Software Foundation, either version 3 of the License, or
// (at your option) any later version.

import {describe, it, expect} from 'vitest'
import {framesToSvg, svgDimensions, type ExportFrame} from '../flamegraphExport'

const FRAMES: ExportFrame[] = [
  {name: 'com.app.A.run', depth: 0, x: 0, width: 100, color: 'hsl(142,55%,42%)'},
  {name: 'a<b>&c', depth: 1, x: 0, width: 50, color: '#ffffff'},
]

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
