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

import { describe, it, expect } from 'vitest'
import {
  urlWithQuery,
  formatErrorForLogging,
  filenameFromContentDisposition,
  isUuidResourceId,
} from '../utils'

// ──── urlWithQuery ────

describe('urlWithQuery', () => {
  it('appends query string when non-empty', () => {
    expect(urlWithQuery('/api/test', 'foo=bar')).toBe('/api/test?foo=bar')
  })

  it('returns path alone when query string is empty', () => {
    expect(urlWithQuery('/api/test', '')).toBe('/api/test')
  })
})

// ──── isUuidResourceId ────

describe('isUuidResourceId', () => {
  it('accepts UUID-shaped resource IDs', () => {
    expect(isUuidResourceId('123e4567-e89b-12d3-a456-426614174000')).toBe(true)
  })

  it('rejects numeric and blank resource IDs', () => {
    expect(isUuidResourceId('101')).toBe(false)
    expect(isUuidResourceId('')).toBe(false)
  })
})

// ──── formatErrorForLogging ────

describe('formatErrorForLogging', () => {
  it('returns network error message for NETWORK_ERROR', () => {
    const error = new Error('NETWORK_ERROR')
    expect(formatErrorForLogging(error)).toBe(
      'Network error: Unable to connect to server',
    )
  })

  it('returns error.message for a regular Error', () => {
    const error = new Error('Something went wrong')
    expect(formatErrorForLogging(error)).toBe('Something went wrong')
  })

  it('returns the string itself for a string input', () => {
    expect(formatErrorForLogging('raw error text')).toBe('raw error text')
  })

  it('returns stringified number for a number input', () => {
    expect(formatErrorForLogging(42)).toBe('42')
  })
})

// ──── filenameFromContentDisposition ────

describe('filenameFromContentDisposition', () => {
  it('returns null for null input', () => {
    expect(filenameFromContentDisposition(null)).toBeNull()
  })

  it('returns null for an empty string', () => {
    expect(filenameFromContentDisposition('')).toBeNull()
  })

  it('parses UTF-8 encoded filename', () => {
    const header = "attachment; filename*=UTF-8''my%20file.pdf"
    expect(filenameFromContentDisposition(header)).toBe('my file.pdf')
  })

  it('returns raw value when UTF-8 decode fails', () => {
    const header = "attachment; filename*=UTF-8''%E0%A4%A"
    const result = filenameFromContentDisposition(header)
    expect(result).toBe('%E0%A4%A')
  })

  it('parses quoted filename', () => {
    const header = 'attachment; filename="report.csv"'
    expect(filenameFromContentDisposition(header)).toBe('report.csv')
  })

  it('parses unquoted filename', () => {
    const header = 'attachment; filename=report.csv'
    expect(filenameFromContentDisposition(header)).toBe('report.csv')
  })

  it('returns null when no filename is present', () => {
    expect(filenameFromContentDisposition('attachment')).toBeNull()
  })
})
