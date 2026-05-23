// Moneat - observability platform
// Copyright (C) 2026 Moneat
//
// This program is free software: you can redistribute it and/or modify
// it under the terms of the GNU Affero General Public License as published by
// the Free Software Foundation, either version 3 of the License, or
// (at your option) any later version.
//
// This program is distributed in the hope that it will be useful,
// but WITHOUT ANY WARRANTY; without even the implied warranty of
// MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the
// GNU Affero General Public License for more details.
//
// You should have received a copy of the GNU Affero General Public License
// along with this program. If not, see <https://www.gnu.org/licenses/>.

import {render, screen} from '@testing-library/react'
import {describe, it, expect, vi, beforeEach, afterEach} from 'vitest'
import {LandingNavbar, LandingFooter} from '../LandingNavbar'

// Mock tanstack router Link to render a simple anchor
vi.mock('@tanstack/react-router', () => ({
  Link: ({children, to, className, 'aria-label': ariaLabel}: {
    children?: React.ReactNode
    to: string
    className?: string
    'aria-label'?: string
  }) => (
    <a href={to} className={className} aria-label={ariaLabel}>
      {children}
    </a>
  ),
}))

// Mock the Logo component
vi.mock('@/components/Logo', () => ({
  Logo: ({className, markOnly}: {className?: string; markOnly?: boolean}) => (
    <svg data-testid="logo" className={className} data-mark-only={markOnly} aria-label="Moneat" />
  ),
}))

// Mock Button
vi.mock('@/components/ui/button', () => ({
  Button: ({children, className, variant, size, asChild, ...props}: {
    children?: React.ReactNode
    className?: string
    variant?: string
    size?: string
    asChild?: boolean
    [key: string]: unknown
  }) => {
    if (asChild && children) {
      return <>{children}</>
    }
    return (
      <button className={className} data-variant={variant} data-size={size} {...props}>
        {children}
      </button>
    )
  },
}))

// Mock Sheet components (Radix UI)
vi.mock('@/components/ui/sheet', () => ({
  Sheet: ({children, open, onOpenChange}: {
    children?: React.ReactNode
    open?: boolean
    onOpenChange?: (open: boolean) => void
  }) => <div data-testid="sheet" data-open={open} onClick={() => onOpenChange?.(!open)}>{children}</div>,
  SheetTrigger: ({children, asChild}: {children?: React.ReactNode; asChild?: boolean}) => (
    asChild ? <>{children}</> : <div data-testid="sheet-trigger">{children}</div>
  ),
  SheetContent: ({children, className}: {children?: React.ReactNode; className?: string}) => (
    <div data-testid="sheet-content" className={className}>{children}</div>
  ),
  SheetTitle: ({children, className}: {children?: React.ReactNode; className?: string}) => (
    <h2 data-testid="sheet-title" className={className}>{children}</h2>
  ),
  SheetDescription: ({children, className}: {children?: React.ReactNode; className?: string}) => (
    <p data-testid="sheet-description" className={className}>{children}</p>
  ),
}))

// Mock lucide-react icons
vi.mock('lucide-react', () => ({
  Activity: () => <span data-testid="icon-activity" />,
  Bell: () => <span data-testid="icon-bell" />,
  Bot: () => <span data-testid="icon-bot" />,
  Box: () => <span data-testid="icon-box" />,
  Brain: () => <span data-testid="icon-brain" />,
  ChevronDown: () => <span data-testid="icon-chevron-down" />,
  ChevronRight: () => <span data-testid="icon-chevron-right" />,
  FileText: () => <span data-testid="icon-file-text" />,
  Flame: () => <span data-testid="icon-flame" />,
  GitBranch: () => <span data-testid="icon-git-branch" />,
  Globe: () => <span data-testid="icon-globe" />,
  LayoutDashboard: () => <span data-testid="icon-layout-dashboard" />,
  Menu: () => <span data-testid="icon-menu" />,
  Phone: () => <span data-testid="icon-phone" />,
  Play: () => <span data-testid="icon-play" />,
  Server: () => <span data-testid="icon-server" />,
  ShieldCheck: () => <span data-testid="icon-shield-check" />,
  Zap: () => <span data-testid="icon-zap" />,
  Check: () => <span data-testid="icon-check" />,
  Database: () => <span data-testid="icon-database" />,
  ArrowRight: () => <span data-testid="icon-arrow-right" />,
}))

// Mock ResizeObserver used by Radix UI
globalThis.ResizeObserver = class ResizeObserver {
  observe() {}
  unobserve() {}
  disconnect() {}
}

globalThis.HTMLElement.prototype.scrollIntoView = vi.fn()
globalThis.HTMLElement.prototype.releasePointerCapture = vi.fn()
globalThis.HTMLElement.prototype.hasPointerCapture = vi.fn()

describe('LandingNavbar', () => {
  beforeEach(() => {
    document.documentElement.classList.remove('dark')
    document.documentElement.style.colorScheme = ''
    vi.clearAllMocks()
  })

  afterEach(() => {
    document.documentElement.classList.remove('dark')
    document.documentElement.style.colorScheme = ''
  })

  it('renders without crashing with default dark tone', () => {
    render(<LandingNavbar />)
    expect(screen.getByRole('banner')).toBeInTheDocument()
  })

  it('renders navigation links', () => {
    render(<LandingNavbar />)
    expect(screen.getByText('Pricing')).toBeInTheDocument()
    expect(screen.getByText('Docs')).toBeInTheDocument()
    expect(screen.getByText('Blog')).toBeInTheDocument()
  })

  it('renders the Moneat Home logo link with aria-label', () => {
    render(<LandingNavbar />)
    expect(screen.getByRole('link', {name: 'Moneat Home'})).toBeInTheDocument()
  })

  it('renders Log in and Sign up free buttons', () => {
    render(<LandingNavbar />)
    expect(screen.getByText('Log in')).toBeInTheDocument()
    expect(screen.getByText('Sign up free')).toBeInTheDocument()
  })

  describe('light tone', () => {
    it('header has light background class when tone is light', () => {
      render(<LandingNavbar tone="light" />)
      const header = screen.getByRole('banner')
      expect(header.className).toContain('bg-white/90')
    })

    it('header has light border class when tone is light', () => {
      render(<LandingNavbar tone="light" />)
      const header = screen.getByRole('banner')
      expect(header.className).toContain('border-slate-200')
    })

    it('removes dark class from document.documentElement when tone is light', () => {
      document.documentElement.classList.add('dark')
      render(<LandingNavbar tone="light" />)
      expect(document.documentElement.classList.contains('dark')).toBe(false)
    })

    it('sets colorScheme to light on document.documentElement when tone is light', () => {
      render(<LandingNavbar tone="light" />)
      expect(document.documentElement.style.colorScheme).toBe('light')
    })

    it('restores previous colorScheme when unmounted', () => {
      document.documentElement.style.colorScheme = 'dark'
      const {unmount} = render(<LandingNavbar tone="light" />)
      expect(document.documentElement.style.colorScheme).toBe('light')
      unmount()
      expect(document.documentElement.style.colorScheme).toBe('dark')
    })
  })

  describe('dark tone', () => {
    it('header has dark background class when tone is dark', () => {
      render(<LandingNavbar tone="dark" />)
      const header = screen.getByRole('banner')
      expect(header.className).toContain('bg-[#0a0b14]/80')
    })

    it('does NOT set colorScheme on document.documentElement when tone is dark', () => {
      document.documentElement.style.colorScheme = ''
      render(<LandingNavbar tone="dark" />)
      // Dark tone should not override colorScheme
      expect(document.documentElement.style.colorScheme).toBe('')
    })

    it('does NOT remove dark class from documentElement when tone is dark', () => {
      document.documentElement.classList.add('dark')
      render(<LandingNavbar tone="dark" />)
      expect(document.documentElement.classList.contains('dark')).toBe(true)
    })
  })
})

describe('LandingFooter', () => {
  it('renders dark footer by default', () => {
    const {container} = render(<LandingFooter />)
    const footer = container.querySelector('footer')
    expect(footer).toBeInTheDocument()
    expect(footer!.className).toContain('bg-[#070810]')
  })

  it('renders light footer when tone is light', () => {
    const {container} = render(<LandingFooter tone="light" />)
    const footer = container.querySelector('footer')
    expect(footer).toBeInTheDocument()
    expect(footer!.className).toContain('bg-white')
  })

  it('light footer includes company address', () => {
    render(<LandingFooter tone="light" />)
    expect(screen.getByText(/Charlotte, NC/i)).toBeInTheDocument()
  })

  it('light footer includes copyright notice', () => {
    render(<LandingFooter tone="light" />)
    expect(screen.getByText(/Moneat\./)).toBeInTheDocument()
  })

  it('dark footer does not render company address', () => {
    render(<LandingFooter tone="dark" />)
    expect(screen.queryByText(/Charlotte, NC/i)).not.toBeInTheDocument()
  })

  it('light footer includes navigation links for Product, Resources, and Company', () => {
    render(<LandingFooter tone="light" />)
    expect(screen.getByText('Product')).toBeInTheDocument()
    expect(screen.getByText('Resources')).toBeInTheDocument()
    expect(screen.getByText('Company')).toBeInTheDocument()
  })
})
