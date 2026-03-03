import { describe, it, expect } from 'vitest'
import { render, screen } from '@testing-library/react'
import { LoginLogo } from '@/components/login-logo'

describe('LoginLogo', () => {
  it('renders without crashing', () => {
    render(<LoginLogo />)
    const svg = screen.getByLabelText('Moneat')
    expect(svg).toBeDefined()
  })

  it('applies custom className', () => {
    render(<LoginLogo className="my-class" />)
    const svg = screen.getByLabelText('Moneat')
    expect(svg.getAttribute('class')).toContain('my-class')
  })
})
