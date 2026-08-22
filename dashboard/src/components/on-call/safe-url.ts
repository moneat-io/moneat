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

// Boundary for user-controlled incident source URLs. Incident links come from
// operators and integrations, so before we ever store one or render it as a
// clickable anchor we parse it and require an http(s) scheme — rejecting
// javascript:, data:, mailto:, and other schemes that could execute or mislead.

/**
 * Parse and normalize a URL, returning the normalized href only when it is a
 * syntactically valid http(s) URL. Returns null for anything else (empty,
 * malformed, or a non-http(s) scheme). Use the return value both to validate on
 * input and to decide whether stored data is safe to render as a link.
 */
export function parseHttpUrl(raw: string | null | undefined): string | null {
  if (typeof raw !== 'string') return null
  const trimmed = raw.trim()
  if (!trimmed) return null
  let parsed: URL
  try {
    parsed = new URL(trimmed)
  } catch {
    return null
  }
  if (parsed.protocol !== 'http:' && parsed.protocol !== 'https:') return null
  return parsed.toString()
}

/** True when `raw` is a syntactically valid http(s) URL. */
export function isHttpUrl(raw: string | null | undefined): boolean {
  return parseHttpUrl(raw) !== null
}
