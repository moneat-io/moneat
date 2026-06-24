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

import {fireEvent, screen} from '@testing-library/react'
import type {ReactNode} from 'react'
import {describe, expect, it, vi} from 'vitest'

import {Sidebar} from '@/components/Sidebar'
import {TooltipProvider} from '@/components/ui/tooltip'
import {renderWithQueryClient} from '@/test/utils'

const mockNavigate = vi.fn()

vi.mock('@tanstack/react-router', async (importOriginal) => {
  const actual = await importOriginal<Record<string, unknown>>()
  const React = await import('react')
  return {
    ...actual,
    Link: ({
      to,
      onClick,
      className,
      children,
    }: {
      to?: string
      onClick?: () => void
      className?: string
      children: ReactNode
    }) => React.createElement('a', {href: typeof to === 'string' ? to : '#', onClick, className}, children),
    useNavigate: () => mockNavigate,
    useRouterState: () => ({location: {pathname: '/', search: {}}}),
  }
})

// The sidebar fetches enterprise features through react-query; stub the hook so
// the component renders offline and the enterprise-gated items stay hidden.
vi.mock('@/hooks/useEnterpriseFeatures', () => ({
  useEnterpriseFeatures: () => ({data: undefined}),
  hasEnterpriseModule: () => false,
}))

vi.mock('@/components/ThemeSwitcher', async () => {
  const React = await import('react')
  return {ThemeSwitcher: () => React.createElement('button', {type: 'button'}, 'theme')}
})

type SidebarProps = Parameters<typeof Sidebar>[0]

function renderSidebar(props: Partial<SidebarProps> = {}) {
  const merged: SidebarProps = {
    isExpanded: true,
    onExpandedChange: vi.fn(),
    headerHeight: 48,
    ...props,
  }
  return renderWithQueryClient(
    <TooltipProvider>
      <Sidebar {...merged} />
    </TooltipProvider>,
  )
}

function sidebarEl(container: HTMLElement) {
  return container.querySelector('.sidebar') as HTMLElement
}

describe('Sidebar', () => {
  it('renders the full-width drawer on mobile and closes it on navigation', () => {
    const onMobileOpenChange = vi.fn()
    renderSidebar({isMobile: true, isMobileOpen: true, onMobileOpenChange, isExpanded: false})

    // Mobile forces the expanded layout, so group labels are visible.
    expect(screen.getByText('Observability')).toBeInTheDocument()
    expect(screen.getByText('Management')).toBeInTheDocument()

    // The dedicated close affordance only exists in the mobile drawer.
    fireEvent.click(screen.getByRole('button', {name: /close navigation/i}))
    expect(onMobileOpenChange).toHaveBeenCalledWith(false)

    // Tapping a nav link also dismisses the drawer.
    fireEvent.click(screen.getByRole('link', {name: /Overview/i}))
    expect(onMobileOpenChange).toHaveBeenCalledTimes(2)
  })

  it('translates the drawer off-canvas when closed on mobile', () => {
    const {container} = renderSidebar({
      isMobile: true,
      isMobileOpen: false,
      onMobileOpenChange: vi.fn(),
      isExpanded: false,
    })

    const el = sidebarEl(container)
    expect(el.className).toContain('-translate-x-full')
    // Height tracks the dynamic viewport so the Management group never falls
    // behind a mobile browser's bottom toolbar.
    expect(el.style.height).toContain('100dvh')
  })

  it('renders the expanded desktop rail with the collapse control', () => {
    renderSidebar({isMobile: false, isExpanded: true, onExpandedChange: vi.fn()})

    expect(screen.getByText('Management')).toBeInTheDocument()
    expect(screen.getByRole('link', {name: /Setup/i})).toBeInTheDocument()
  })

  it('renders the collapsed icon rail without group labels', () => {
    const {container} = renderSidebar({isMobile: false, isExpanded: false, onExpandedChange: vi.fn()})

    const el = sidebarEl(container)
    expect(el.className).toContain('w-14')
    // Group labels are hidden in the collapsed rail.
    expect(screen.queryByText('Management')).not.toBeInTheDocument()
  })
})
