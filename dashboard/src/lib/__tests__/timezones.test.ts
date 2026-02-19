import { describe, it, expect } from 'vitest'
import { TIMEZONES } from '@/lib/timezones'

describe('timezones', () => {
  it('exports a non-empty array', () => {
    expect(Array.isArray(TIMEZONES)).toBe(true)
    expect(TIMEZONES.length).toBeGreaterThan(0)
  })

  it('includes UTC', () => {
    const utc = TIMEZONES.find(tz => tz.value === 'UTC')
    expect(utc).toBeDefined()
    expect(utc!.label).toContain('UTC')
  })

  it('includes major US timezones', () => {
    const values = TIMEZONES.map(tz => tz.value)
    expect(values).toContain('America/New_York')
    expect(values).toContain('America/Chicago')
    expect(values).toContain('America/Denver')
    expect(values).toContain('America/Los_Angeles')
  })

  it('includes major European timezones', () => {
    const values = TIMEZONES.map(tz => tz.value)
    expect(values).toContain('Europe/London')
    expect(values).toContain('Europe/Berlin')
    expect(values).toContain('Europe/Paris')
  })

  it('includes major Asian timezones', () => {
    const values = TIMEZONES.map(tz => tz.value)
    expect(values).toContain('Asia/Tokyo')
    expect(values).toContain('Asia/Shanghai')
    expect(values).toContain('Asia/Kolkata')
  })

  it('each entry has value and label', () => {
    for (const tz of TIMEZONES) {
      expect(tz.value).toBeTruthy()
      expect(tz.label).toBeTruthy()
    }
  })

  it('has no duplicate values', () => {
    const values = TIMEZONES.map(tz => tz.value)
    const unique = new Set(values)
    expect(unique.size).toBe(values.length)
  })

  it('values are valid IANA timezone identifiers', () => {
    for (const tz of TIMEZONES) {
      // All values should match IANA format: Region/City or UTC
      expect(tz.value).toMatch(/^(UTC|[A-Z][a-z]+\/[A-Za-z_]+)$/)
    }
  })
})
