import { describe, it, expect } from 'vitest'
import { render, screen } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { Logo } from '../logo'
import { Button } from '../ui/button'
import { Input } from '../ui/input'
import { Label } from '../ui/label'

describe('UI Components', () => {
  describe('Button', () => {
    it('renders button with text', () => {
      render(<Button>Click me</Button>)
      expect(screen.getByRole('button', { name: 'Click me' })).toBeInTheDocument()
    })

    it('handles click events', async () => {
      let clicked = false
      const user = userEvent.setup()
      render(<Button onClick={() => { clicked = true }}>Click me</Button>)
      
      await user.click(screen.getByRole('button'))
      expect(clicked).toBe(true)
    })

    it('respects disabled state', () => {
      render(<Button disabled>Disabled</Button>)
      expect(screen.getByRole('button')).toBeDisabled()
    })

    it('applies variant classes', () => {
      const { container } = render(<Button variant="destructive">Delete</Button>)
      expect(container.firstChild).toHaveClass('bg-destructive')
    })
  })

  describe('Input', () => {
    it('renders input with placeholder', () => {
      render(<Input placeholder="Enter text" />)
      expect(screen.getByPlaceholderText('Enter text')).toBeInTheDocument()
    })

    it('handles text input', async () => {
      const user = userEvent.setup()
      render(<Input />)
      
      const input = screen.getByRole('textbox')
      await user.type(input, 'test input')
      expect(input).toHaveValue('test input')
    })

    it('respects disabled state', () => {
      render(<Input disabled />)
      expect(screen.getByRole('textbox')).toBeDisabled()
    })

    it('applies type attribute', () => {
      render(<Input type="email" />)
      const input = screen.getByRole('textbox') as HTMLInputElement
      expect(input.type).toBe('email')
    })
  })

  describe('Label', () => {
    it('renders label text', () => {
      render(<Label>Email address</Label>)
      expect(screen.getByText('Email address')).toBeInTheDocument()
    })

    it('associates with input via htmlFor', () => {
      render(
        <>
          <Label htmlFor="test-input">Test Label</Label>
          <Input id="test-input" />
        </>
      )
      
      const label = screen.getByText('Test Label')
      const input = screen.getByRole('textbox')
      expect(label).toHaveAttribute('for', 'test-input')
      expect(input).toHaveAttribute('id', 'test-input')
    })
  })

  describe('Logo', () => {
    it('renders logo', () => {
      const { container } = render(<Logo />)
      expect(container.querySelector('svg')).toBeInTheDocument()
    })

    it('applies custom className', () => {
      const { container } = render(<Logo className="custom-class" />)
      const svg = container.querySelector('svg')
      expect(svg).toHaveClass('custom-class')
    })
  })
})
