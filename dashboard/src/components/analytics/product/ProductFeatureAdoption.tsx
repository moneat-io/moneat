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

import {Bookmark, Check, Layers, Plug, Search, Share2, Smartphone} from 'lucide-react'
import type {LucideIcon} from 'lucide-react'

import {SectionCard} from '@/components/ui/section-card'
import type {ProductFeatureAdoptionItem} from '@/lib/api'

const ICON_RULES: ReadonlyArray<{match: RegExp; icon: LucideIcon}> = [
  {match: /onboard|checklist|setup/i, icon: Check},
  {match: /search/i, icon: Search},
  {match: /save|bookmark|view/i, icon: Bookmark},
  {match: /shar|invite|collab/i, icon: Share2},
  {match: /integrat|connect|api|webhook/i, icon: Plug},
  {match: /mobile|app|ios|android/i, icon: Smartphone},
]

const FEATURE_ADOPTION_SKELETON_KEYS = ['feature-1', 'feature-2', 'feature-3', 'feature-4', 'feature-5'] as const

function iconFor(name: string): LucideIcon {
  return ICON_RULES.find((rule) => rule.match.test(name))?.icon ?? Layers
}

function ProductFeatureAdoptionBody({
  data,
  isLoading,
}: Readonly<{data?: ProductFeatureAdoptionItem[]; isLoading?: boolean}>) {
  if (isLoading) {
    return (
      <div className="space-y-3">
        {FEATURE_ADOPTION_SKELETON_KEYS.map((key) => (
          <div key={key} className="h-6 w-full animate-pulse rounded bg-muted" />
        ))}
      </div>
    )
  }
  if (data == null || data.length === 0) {
    return <p className="py-8 text-center text-xs text-muted-foreground">No feature usage data</p>
  }
  return (
    <div className="flex flex-col">
      {data.map((feature) => {
        const Icon = iconFor(feature.name)
        const adoptionRate = Math.min(100, Math.max(0, feature.adoptionRate))
        return (
          <div
            key={feature.name}
            className="grid grid-cols-[minmax(0,1fr)_56px] items-center gap-2.5 border-t py-2 first:border-t-0"
          >
            <div>
              <div className="flex items-center gap-2 text-sm">
                <Icon className="h-3.5 w-3.5 text-muted-foreground" />
                {feature.name}
              </div>
              <div className="mt-1.5 h-1.5 overflow-hidden rounded-full bg-muted">
                <div
                  className="h-full rounded-full bg-primary"
                  style={{width: `${adoptionRate}%`}}
                />
              </div>
            </div>
            <div className="text-right font-mono text-xs font-semibold tabular-nums">
              {adoptionRate.toFixed(0)}%
            </div>
          </div>
        )
      })}
    </div>
  )
}

export function ProductFeatureAdoption({
  data,
  isLoading,
}: Readonly<{data?: ProductFeatureAdoptionItem[]; isLoading?: boolean}>) {
  return (
    <SectionCard
      title="Feature adoption"
      icon={Layers}
      actions={<span className="text-xs font-normal text-muted-foreground">of active users</span>}
    >
      <ProductFeatureAdoptionBody data={data} isLoading={isLoading} />
    </SectionCard>
  )
}
