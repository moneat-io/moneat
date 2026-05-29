// Moneat - observability platform
// Copyright (C) 2026 Moneat
//
// This program is free software: you can redistribute it and/or modify
// it under the terms of the GNU Affero General Public License as published by
// the Free Software Foundation, either version 3 of the License, or
// (at your option) any later version.

import {describe, it, expect} from 'vitest'
import {
  formatBytes,
  formatCompact,
  formatDuration,
  parseUtcDate,
  profileTypeBadgeClass,
} from '../profileFormat'

describe('profileFormat', () => {
  it('formats byte sizes across units', () => {
    expect(formatBytes(512)).toBe('512 B')
    expect(formatBytes(2048)).toBe('2.0 KB')
    expect(formatBytes(2 * 1024 * 1024)).toBe('2.0 MB')
    expect(formatBytes(3 * 1024 * 1024 * 1024)).toBe('3.00 GB')
  })

  it('formats durations from nanoseconds', () => {
    expect(formatDuration(0)).toBe('—')
    expect(formatDuration(500_000_000)).toBe('500ms')
    expect(formatDuration(60_000_000_000)).toBe('60.0s')
  })

  it('parses ClickHouse UTC timestamps and ISO strings', () => {
    expect(parseUtcDate('2026-05-28 12:00:00.000').getTime()).toBe(
      Date.UTC(2026, 4, 28, 12, 0, 0, 0),
    )
    expect(parseUtcDate('2026-05-28T12:00:00.000Z').getTime()).toBe(
      Date.UTC(2026, 4, 28, 12, 0, 0, 0),
    )
    expect(Number.isNaN(parseUtcDate('').getTime())).toBe(true)
  })

  it('formats compact counts', () => {
    expect(formatCompact(950)).toBe('950')
    expect(formatCompact(41_000)).toBe('41K')
  })

  it('maps known profile types to themed badge classes', () => {
    expect(profileTypeBadgeClass('cpu')).toContain('orange')
    expect(profileTypeBadgeClass('heap')).toContain('green')
    expect(profileTypeBadgeClass('jfr')).toBe('bg-secondary text-secondary-foreground')
  })
})
