import { describe, it, expect } from 'vitest'
import { LEGAL_TERMS_VERSION, LEGAL_PRIVACY_VERSION } from '@/lib/legal'

describe('legal', () => {
  it('exports terms version as string', () => {
    expect(typeof LEGAL_TERMS_VERSION).toBe('string')
    expect(LEGAL_TERMS_VERSION.length).toBeGreaterThan(0)
  })

  it('exports privacy version as string', () => {
    expect(typeof LEGAL_PRIVACY_VERSION).toBe('string')
    expect(LEGAL_PRIVACY_VERSION.length).toBeGreaterThan(0)
  })

  it('has default version matching expected format', () => {
    // Default is '2026-02-08' (date format)
    expect(LEGAL_TERMS_VERSION).toMatch(/^\d{4}-\d{2}-\d{2}$/)
    expect(LEGAL_PRIVACY_VERSION).toMatch(/^\d{4}-\d{2}-\d{2}$/)
  })
})
