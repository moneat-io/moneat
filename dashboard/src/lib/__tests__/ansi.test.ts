import { describe, it, expect } from 'vitest'
import { stripAnsi } from '../ansi'

describe('stripAnsi', () => {
  it('returns empty string for null', () => {
    expect(stripAnsi(null)).toBe('')
  })

  it('returns empty string for undefined', () => {
    expect(stripAnsi(undefined)).toBe('')
  })

  it('returns empty string for empty string', () => {
    expect(stripAnsi('')).toBe('')
  })

  it('returns plain text unchanged', () => {
    expect(stripAnsi('hello world')).toBe('hello world')
  })

  it('strips basic color codes', () => {
    expect(stripAnsi('\u001b[34mblue text\u001b[0m')).toBe('blue text')
  })

  it('strips multiple color codes', () => {
    expect(stripAnsi('\u001b[31mred\u001b[0m and \u001b[32mgreen\u001b[0m')).toBe('red and green')
  })

  it('strips bold/bright codes', () => {
    expect(stripAnsi('\u001b[1;33mbold yellow\u001b[0m')).toBe('bold yellow')
  })

  it('strips reset code [0m', () => {
    expect(stripAnsi('[0m')).toBe('')
  })

  it('strips bracket-style codes', () => {
    expect(stripAnsi('[31mcolored[0m')).toBe('colored')
  })

  it('handles text with no ANSI but brackets', () => {
    expect(stripAnsi('array[0]')).toBe('array[0]')
  })

  it('handles multiline text with codes', () => {
    expect(stripAnsi('\u001b[31mline1\u001b[0m\n\u001b[32mline2\u001b[0m')).toBe('line1\nline2')
  })
})
