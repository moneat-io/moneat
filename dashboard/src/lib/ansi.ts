// Moneat - Mobile-First Error Monitoring Platform
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

/**
 * Strip ANSI escape codes from a string
 * Removes color codes like [34m, [0;39m, etc.
 */
export function stripAnsi(text: string | null | undefined): string {
  if (!text) return ''
  
  // ANSI escape code pattern
  // eslint-disable-next-line no-control-regex
  const ansiPattern = /\u001b\[[0-9;]*m|\x1b\[[0-9;]*m|\[0m|\[[0-9;]+m/g
  
  return text.replace(ansiPattern, '')
}
