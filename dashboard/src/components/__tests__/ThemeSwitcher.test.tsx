import {render, screen} from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import {describe, it, expect, vi, beforeEach} from 'vitest'
import {ThemeSwitcher} from '../ThemeSwitcher'

// Mock the lucide-react icons
vi.mock('lucide-react', () => ({
    Moon: () => <div data-testid="moon-icon" />,
    Sun: () => <div data-testid="sun-icon" />,
    Palette: () => <div data-testid="palette-icon" />,
    CloudMoon: () => <div data-testid="cloud-moon-icon" />,
    Leaf: () => <div data-testid="leaf-icon" />,
    Sunset: () => <div data-testid="sunset-icon" />,
    Gamepad2: () => <div data-testid="gamepad-icon" />,
    Check: () => <div data-testid="check-icon" />,
    Newspaper: () => <div data-testid="newspaper-icon" />,
    Monitor: () => <div data-testid="monitor-icon" />,
}))

// Mock ResizeObserver which is used by Radix UI
global.ResizeObserver = class ResizeObserver {
    observe() { /* no-op */ }
    unobserve() { /* no-op */ }
    disconnect() { /* no-op */ }
}

// Mock scrollIntoView
window.HTMLElement.prototype.scrollIntoView = vi.fn()
window.HTMLElement.prototype.releasePointerCapture = vi.fn()
window.HTMLElement.prototype.hasPointerCapture = vi.fn()

describe('ThemeSwitcher', () => {
    beforeEach(() => {
        // Clear localStorage and reset document classes
        localStorage.clear()
        document.documentElement.className = ''
        vi.clearAllMocks()
    })

    it('renders correctly', () => {
        render(<ThemeSwitcher />)
        expect(screen.getByRole('button', {name: /select theme/i})).toBeInTheDocument()
    })

    it('defaults to dark theme if no theme is saved', () => {
        render(<ThemeSwitcher />)
        expect(document.documentElement.classList.contains('dark')).toBe(true)
    })

    it('loads saved theme from localStorage', () => {
        localStorage.setItem('theme', 'light')
        render(<ThemeSwitcher />)
        expect(document.documentElement.classList.contains('dark')).toBe(false)
    })

    it('switches to light theme', async () => {
        const user = userEvent.setup()
        render(<ThemeSwitcher />)
        
        // Open dropdown
        await user.click(screen.getByRole('button', {name: /select theme/i}))
        
        // Click Light option
        const lightOption = await screen.findByText('Light')
        await user.click(lightOption)
        
        expect(localStorage.getItem('theme')).toBe('light')
        expect(document.documentElement.classList.contains('dark')).toBe(false)
    })

    it('switches to midnight theme', async () => {
        const user = userEvent.setup()
        render(<ThemeSwitcher />)
        
        // Open dropdown
        await user.click(screen.getByRole('button', {name: /select theme/i}))
        
        // Click Midnight option
        const midnightOption = await screen.findByText('Midnight')
        await user.click(midnightOption)
        
        expect(localStorage.getItem('theme')).toBe('midnight')
        expect(document.documentElement.classList.contains('dark')).toBe(true)
        expect(document.documentElement.classList.contains('theme-midnight')).toBe(true)
    })
})
