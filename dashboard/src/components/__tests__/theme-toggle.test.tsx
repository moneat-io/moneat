import { describe, it, expect, beforeEach } from 'vitest'
import { render, screen, fireEvent } from '@testing-library/react'
import { ThemeToggle } from '../theme-toggle'

describe('ThemeToggle', () => {
  beforeEach(() => {
    localStorage.clear()
    document.documentElement.classList.remove('dark')
  })

  it('renders a button with aria-label', () => {
    render(<ThemeToggle />)
    expect(screen.getByRole('button', { name: /toggle theme/i })).toBeInTheDocument()
  })

  it('defaults to dark theme', () => {
    render(<ThemeToggle />)
    expect(localStorage.getItem('theme')).toBe('dark')
  })

  it('reads saved theme from localStorage', () => {
    localStorage.setItem('theme', 'light')
    render(<ThemeToggle />)
    expect(document.documentElement.classList.contains('dark')).toBe(false)
  })

  it('toggles from dark to light', () => {
    render(<ThemeToggle />)
    fireEvent.click(screen.getByRole('button', { name: /toggle theme/i }))
    expect(localStorage.getItem('theme')).toBe('light')
    expect(document.documentElement.classList.contains('dark')).toBe(false)
  })

  it('toggles from light to dark', () => {
    localStorage.setItem('theme', 'light')
    render(<ThemeToggle />)
    fireEvent.click(screen.getByRole('button', { name: /toggle theme/i }))
    expect(localStorage.getItem('theme')).toBe('dark')
  })
})
