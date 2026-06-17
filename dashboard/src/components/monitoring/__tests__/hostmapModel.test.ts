// Moneat - observability platform
// Copyright (C) 2026 Moneat
//
// This program is free software: you can redistribute it and/or modify
// it under the terms of the GNU Affero General Public License as published by
// the Free Software Foundation, either version 3 of the License, or
// (at your option) any later version.

import {describe, expect, it} from 'vitest'
import {toHostmapView, sizeTierFor} from '../hostmapModel'
import type {InfrastructureMapNode, InfrastructureMapResult, InfrastructureMapTone} from '../infrastructureMapModel'

function node(
  id: string,
  sizePercent: number,
  fillTone: InfrastructureMapTone = 'success',
): InfrastructureMapNode {
  return {
    id,
    label: id,
    subtitle: '',
    statusLabel: 'up',
    fillTone,
    metricLabel: '',
    sizeLabel: '',
    sizePercent,
    details: [],
    tags: {},
  }
}

const result: InfrastructureMapResult = {
  groups: [
    {
      key: 'us-east-1a',
      label: 'us-east-1a',
      healthyCount: 1,
      nodes: [node('h1', 40), node('h2', 90, 'danger')],
    },
  ],
  summary: {totalResources: 2, visibleResources: 2, healthyResources: 1, groupCount: 1},
}

describe('sizeTierFor', () => {
  it('buckets sizePercent into s/m/l/xl', () => {
    expect(sizeTierFor(36)).toBe('s')
    expect(sizeTierFor(55)).toBe('m')
    expect(sizeTierFor(75)).toBe('l')
    expect(sizeTierFor(92)).toBe('xl')
  })

  it('uses inclusive lower bounds at tier edges', () => {
    expect(sizeTierFor(48)).toBe('m')
    expect(sizeTierFor(68)).toBe('l')
    expect(sizeTierFor(84)).toBe('xl')
  })
})

describe('toHostmapView', () => {
  it('maps groups to tiles preserving tone, tier, and order', () => {
    const view = toHostmapView(result)
    expect(view.groups).toHaveLength(1)
    expect(view.groups[0].label).toBe('us-east-1a')
    expect(view.groups[0].count).toBe(2)
    expect(view.groups[0].tiles.map((tile) => tile.id)).toEqual(['h1', 'h2'])
    expect(view.groups[0].tiles[1].tone).toBe('danger')
    expect(view.groups[0].tiles[1].tier).toBe('xl')
    expect(view.groups[0].tiles[0].tier).toBe('s')
  })

  it('keeps the node reference on each tile for the detail dock', () => {
    const view = toHostmapView(result)
    expect(view.groups[0].tiles[0].node.id).toBe('h1')
  })

  it('carries the summary through unchanged', () => {
    expect(toHostmapView(result).summary).toEqual(result.summary)
  })
})
