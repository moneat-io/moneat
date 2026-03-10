import { describe, it, expect } from 'vitest'
import { render, screen } from '@testing-library/react'
import { Logo } from '@/components/Logo'

describe('Logo', () => {
  it('renders full logo by default', () => {
    render(<Logo />)
    const svg = screen.getByLabelText('Moneat')
    expect(svg).toBeDefined()
    expect(svg.getAttribute('viewBox')).toBe('0 0 160 48')
  })

  it('renders mark-only logo', () => {
    render(<Logo markOnly />)
    const svg = screen.getByLabelText('Moneat')
    expect(svg.getAttribute('viewBox')).toBe('0 0 48 48')
  })

  it('applies custom className', () => {
    render(<Logo className="h-12 w-12" />)
    const svg = screen.getByLabelText('Moneat')
    expect(svg.getAttribute('class')).toContain('h-12')
  })

  it('contains wordmark text in full logo', () => {
    render(<Logo />)
    expect(screen.getByText('moneat')).toBeDefined()
  })

  it('does not contain wordmark text in mark-only', () => {
    render(<Logo markOnly />)
    expect(screen.queryByText('moneat')).toBeNull()
  })
})
