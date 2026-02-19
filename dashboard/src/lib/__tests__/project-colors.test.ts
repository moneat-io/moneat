import { describe, it, expect } from 'vitest'
import { getProjectColor, getProjectInitial } from '../project-colors'

describe('getProjectColor', () => {
  it('returns correct color for letter A', () => {
    expect(getProjectColor('Alpha')).toBe('#ef4444')
  })

  it('returns correct color for letter Z', () => {
    expect(getProjectColor('Zulu')).toBe('#db2777')
  })

  it('returns correct color for letter M', () => {
    expect(getProjectColor('Moneat')).toBe('#8b5cf6')
  })

  it('is case insensitive', () => {
    expect(getProjectColor('alpha')).toBe('#ef4444')
    expect(getProjectColor('ALPHA')).toBe('#ef4444')
  })

  it('returns gray for numeric start', () => {
    expect(getProjectColor('123project')).toBe('#6b7280')
  })

  it('returns gray for special character start', () => {
    expect(getProjectColor('!special')).toBe('#6b7280')
  })

  it('returns gray for empty string', () => {
    expect(getProjectColor('')).toBe('#6b7280')
  })

  it('only uses first letter', () => {
    expect(getProjectColor('Backend')).toBe(getProjectColor('Bridge'))
  })

  it('returns different colors for different letters', () => {
    expect(getProjectColor('Alpha')).not.toBe(getProjectColor('Beta'))
  })
})

describe('getProjectInitial', () => {
  it('returns uppercase first letter', () => {
    expect(getProjectInitial('moneat')).toBe('M')
  })

  it('returns uppercase for already uppercase', () => {
    expect(getProjectInitial('Moneat')).toBe('M')
  })

  it('returns empty string for empty input', () => {
    expect(getProjectInitial('')).toBe('')
  })

  it('returns digit for numeric start', () => {
    expect(getProjectInitial('123')).toBe('1')
  })
})
