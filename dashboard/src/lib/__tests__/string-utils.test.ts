import {describe, expect, it} from 'vitest'
import {trimLeadingCharacter, trimTrailingCharacter} from '../string-utils'

describe('string character trimming', () => {
  it('trims repeated leading characters', () => {
    expect(trimLeadingCharacter('///api', '/')).toBe('api')
    expect(trimLeadingCharacter('api', '/')).toBe('api')
  })

  it('trims repeated trailing characters', () => {
    expect(trimTrailingCharacter('api///', '/')).toBe('api')
    expect(trimTrailingCharacter('api', '/')).toBe('api')
  })

  it('returns an empty value when all characters match', () => {
    expect(trimLeadingCharacter('---', '-')).toBe('')
    expect(trimTrailingCharacter('---', '-')).toBe('')
  })
})
