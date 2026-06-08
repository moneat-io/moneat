// Moneat - observability platform
// Copyright (C) 2026 Moneat
//
// This program is free software: you can redistribute it and/or modify
// it under the terms of the GNU Affero General Public License as published by
// the Free Software Foundation, either version 3 of the License, or
// (at your option) any later version.

import type {
  InfrastructureMapNode,
  InfrastructureMapResult,
  InfrastructureMapSummary,
  InfrastructureMapTone,
} from './infrastructureMapModel'

/**
 * Render model for the packed hostmap. A thin, pure transform over
 * {@link InfrastructureMapResult} (which already derives tone, size, and
 * grouping): it only buckets each node's continuous `sizePercent` into a
 * discrete tile tier and carries the node through for the detail dock. No
 * metric recomputation lives here — capacity/utilization stay owned by
 * `infrastructureMapModel`.
 */

export type HostmapSizeTier = 's' | 'm' | 'l' | 'xl'

export interface HostmapTile {
  id: string
  label: string
  tone: InfrastructureMapTone
  tier: HostmapSizeTier
  node: InfrastructureMapNode
}

export interface HostmapGroup {
  key: string
  label: string
  healthyCount: number
  count: number
  tiles: HostmapTile[]
}

export interface HostmapView {
  groups: HostmapGroup[]
  summary: InfrastructureMapSummary
}

// Tier thresholds line up with infrastructureMapModel's MIN/MAX tile percent
// band (36–92): bigger capacity -> bigger tile, in four legible steps.
export function sizeTierFor(sizePercent: number): HostmapSizeTier {
  if (sizePercent >= 84) return 'xl'
  if (sizePercent >= 68) return 'l'
  if (sizePercent >= 48) return 'm'
  return 's'
}

export function toHostmapView(result: InfrastructureMapResult): HostmapView {
  return {
    summary: result.summary,
    groups: result.groups.map((group) => ({
      key: group.key,
      label: group.label,
      healthyCount: group.healthyCount,
      count: group.nodes.length,
      tiles: group.nodes.map((node) => ({
        id: node.id,
        label: node.label,
        tone: node.fillTone,
        tier: sizeTierFor(node.sizePercent),
        node,
      })),
    })),
  }
}
