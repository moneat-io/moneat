// Moneat - observability platform
// Copyright (C) 2026 Moneat
//
// This program is free software: you can redistribute it and/or modify
// it under the terms of the GNU Affero General Public License as
// published by the Free Software Foundation, either version 3 of the
// License, or (at your option) any later version.
//
// This program is distributed in the hope that it will be useful,
// but WITHOUT ANY WARRANTY; without even the implied warranty of
// MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the
// GNU Affero General Public License for more details.
//
// You should have received a copy of the GNU Affero General Public License
// along with this program. If not, see <https://www.gnu.org/licenses/>.

function isDigits(value: string): boolean {
  if (!value) return false
  for (const character of value) {
    if (character < '0' || character > '9') return false
  }
  return true
}

function isJavaStacktrace(stacktrace: string): boolean {
  const firstLine = stacktrace.split('\n', 1)[0]?.trimStart() ?? ''
  if (!firstLine.startsWith('at ')) return false

  const openParen = firstLine.indexOf('(')
  const closeParen = firstLine.lastIndexOf(')')
  if (openParen <= 3 || closeParen <= openParen + 1) return false

  const location = firstLine.slice(openParen + 1, closeParen)
  const lineSeparator = location.lastIndexOf(':')
  return lineSeparator > 0 && isDigits(location.slice(lineSeparator + 1))
}

export function detectStacktraceLanguage(stacktrace: string): string {
  if (isJavaStacktrace(stacktrace) || stacktrace.includes('\tat ')) return 'javastacktrace'
  if (/File ".+", line \d+/.test(stacktrace)) return 'python'
  return 'log'
}
