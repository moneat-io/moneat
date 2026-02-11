import { describe, it, expect } from 'vitest'

describe('Sample Test', () => {
  it('should pass basic assertion', () => {
    expect(1 + 1).toBe(2)
  })

  it('should have MSW server available', async () => {
    // This test verifies that MSW is set up correctly
    // Actual API tests will be added in Phase 3
    expect(true).toBe(true)
  })
})
