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

// Categorical series palette: the ten theme-tuned chart tokens (adjacent-hue
// separated and legible on the dark viz canvas). Defined via CSS variables so
// series colors track the active theme.
export const CHART_TOKENS = [
  'hsl(var(--chart-1))',
  'hsl(var(--chart-2))',
  'hsl(var(--chart-3))',
  'hsl(var(--chart-4))',
  'hsl(var(--chart-5))',
  'hsl(var(--chart-6))',
  'hsl(var(--chart-7))',
  'hsl(var(--chart-8))',
  'hsl(var(--chart-9))',
  'hsl(var(--chart-10))',
]

// Color for the series at a given index. The first ten come from the theme
// palette; beyond that we rotate hues by the golden angle so overflow series
// stay well separated and saturated enough to read on the dark canvas — rather
// than falling back to dull, washed-out defaults.
export function seriesColor(index: number): string {
  if (index < CHART_TOKENS.length) return CHART_TOKENS[index]
  const hue = Math.round((index * 137.508) % 360)
  return `hsl(${hue} 72% 60%)`
}
