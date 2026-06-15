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

export type DateFormatPref = 'medium' | 'iso' | 'dmy'

export function asDateFormat(value: unknown): DateFormatPref | undefined {
  return value === 'medium' || value === 'iso' || value === 'dmy' ? value : undefined
}

export function sortedSidebarKeys(items: readonly string[]): string[] {
  return [...items].sort((a, b) => a.localeCompare(b))
}

export function hasSidebarChanges(hiddenItems: readonly string[], savedHidden: readonly string[]): boolean {
  return JSON.stringify(sortedSidebarKeys(hiddenItems)) !== JSON.stringify(sortedSidebarKeys(savedHidden))
}
