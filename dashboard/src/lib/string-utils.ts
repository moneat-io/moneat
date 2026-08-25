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

export function trimTrailingCharacter(value: string, character: string): string {
  let end = value.length
  while (end > 0 && value[end - 1] === character) {
    end -= 1
  }
  return value.slice(0, end)
}

export function trimLeadingCharacter(value: string, character: string): string {
  let start = 0
  while (start < value.length && value[start] === character) {
    start += 1
  }
  return value.slice(start)
}
