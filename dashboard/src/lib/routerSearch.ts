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

type SearchPrimitive = string | number | boolean

function isSearchPrimitive(value: unknown): value is SearchPrimitive {
  return typeof value === 'string' || typeof value === 'number' || typeof value === 'boolean'
}

function stringifySearchValue(value: unknown): string {
  if (typeof value === 'object' && value !== null) {
    return JSON.stringify(value)
  }

  if (typeof value === 'string') {
    try {
      JSON.parse(value)
      return JSON.stringify(value)
    } catch {
      return value
    }
  }

  return String(value)
}

export function stringifySearchWithRepeatedPrimitiveArrays(search: Record<string, unknown>): string {
  const params = new URLSearchParams()

  for (const [key, value] of Object.entries(search)) {
    if (value === undefined) continue

    if (Array.isArray(value) && value.every(isSearchPrimitive)) {
      for (const item of value) {
        params.append(key, stringifySearchValue(item))
      }
      continue
    }

    params.set(key, stringifySearchValue(value))
  }

  const searchString = params.toString()
  return searchString ? `?${searchString}` : ''
}
