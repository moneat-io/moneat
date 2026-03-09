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

export function formatErrorForLogging(error: unknown): string {
  if (error instanceof Error) {
    if (error.message === 'NETWORK_ERROR') {
      return 'Network error: Unable to connect to server'
    }
    return error.message
  }
  return String(error)
}

export function filenameFromContentDisposition(
  value: string | null
): string | null {
  if (!value) return null
  const utf8Match = value.match(/filename\*=UTF-8''([^;]+)/i)
  if (utf8Match?.[1]) {
    try {
      return decodeURIComponent(utf8Match[1].trim())
    } catch {
      return utf8Match[1].trim()
    }
  }
  const filenameMatch = value.match(/filename="?([^";]+)"?/i)
  return filenameMatch?.[1]?.trim() ?? null
}
