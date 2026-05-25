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

export interface SeriesDef {
  key: string
  name: string
}

export function valueKeySeries(valueKeys: string[]): SeriesDef[] {
  return valueKeys.map((key) => ({
    key,
    name: key.replace(/_/g, ' '),
  }))
}

function valueToLegendPart(value: unknown): string {
  return String(value ?? '').trim()
}

function getVariableLabelKeys(data: Record<string, unknown>[], labelKeys: string[]) {
  const variableKeys = labelKeys.filter((key) => {
    const values = new Set(data.map((row) => valueToLegendPart(row[key])).filter(Boolean))
    return values.size > 1
  })

  return variableKeys.length > 0 ? variableKeys : labelKeys
}

function getKeySubsets(keys: string[]): string[][] {
  const subsets: string[][] = []
  const count = 1 << keys.length
  for (let mask = 1; mask < count; mask += 1) {
    subsets.push(keys.filter((_, index) => (mask & (1 << index)) !== 0))
  }
  return subsets
}

function buildSeriesName(
  row: Record<string, unknown>,
  labelKeys: string[],
  valueKey: string,
  hasMultipleValues: boolean,
) {
  const labelParts = labelKeys.map((key) => valueToLegendPart(row[key])).filter(Boolean)
  const label = labelParts.join(', ') || 'value'
  return hasMultipleValues ? `${label} (${valueKey})` : label
}

function getDisplayLabelKeys(
  data: Record<string, unknown>[],
  labelKeys: string[],
  valueKeys: string[],
) {
  const candidates = getVariableLabelKeys(data, labelKeys)
  if (candidates.length <= 1 || candidates.length > 10) return candidates

  const hasMultipleValues = valueKeys.length > 1
  const sampleSeries = new Map<string, {row: Record<string, unknown>; valueKey: string}>()
  for (const row of data) {
    const rowKey = labelKeys.map((key) => valueToLegendPart(row[key])).join('\u0001')
    for (const valueKey of valueKeys) {
      sampleSeries.set(`${rowKey}\u0002${valueKey}`, {row, valueKey})
    }
  }

  const series = Array.from(sampleSeries.values())
  let best: {keys: string[]; score: number} | null = null

  for (const keys of getKeySubsets(candidates)) {
    const names = series.map(({row, valueKey}) => buildSeriesName(row, keys, valueKey, hasMultipleValues))
    if (new Set(names).size !== names.length) continue

    const score = names.reduce((sum, name) => sum + name.length, 0)
    if (
      best == null ||
      score < best.score ||
      (score === best.score && keys.length < best.keys.length)
    ) {
      best = {keys, score}
    }
  }

  return best?.keys ?? candidates
}

/**
 * Pivots flat multi-series data into one-row-per-timestamp format for Recharts.
 * Input:  [{time_bucket: 100, platform: "android", value: 5}, {time_bucket: 100, platform: "ios", value: 3}]
 * Output: [{time_bucket: 100, "android": 5, "ios": 3}]
 */
export function pivotData(
  data: Record<string, unknown>[],
  timeKey: string,
  labelKeys: string[],
  valueKeys: string[],
) {
  if (labelKeys.length === 0 || valueKeys.length === 0) {
    return {pivoted: data, series: valueKeySeries(valueKeys)}
  }

  const grouped = new Map<string | number, Record<string, unknown>>()
  const series = new Map<string, SeriesDef>()
  const displayLabelKeys = getDisplayLabelKeys(data, labelKeys, valueKeys)
  const hasMultipleValues = valueKeys.length > 1

  for (const row of data) {
    const t = row[timeKey] as string | number
    if (!grouped.has(t)) {
      grouped.set(t, {[timeKey]: t})
    }
    const entry = grouped.get(t)!

    const keyBase = labelKeys.map((key) => valueToLegendPart(row[key])).filter(Boolean).join(', ') || 'value'

    for (const valueKey of valueKeys) {
      const key = hasMultipleValues ? `${keyBase} (${valueKey})` : keyBase
      entry[key] = row[valueKey]
      if (!series.has(key)) {
        series.set(key, {
          key,
          name: buildSeriesName(row, displayLabelKeys, valueKey, hasMultipleValues),
        })
      }
    }
  }

  const pivoted = Array.from(grouped.values()).sort((a, b) => {
    const ta = a[timeKey] as number
    const tb = b[timeKey] as number
    return ta - tb
  })

  return {pivoted, series: Array.from(series.values())}
}
