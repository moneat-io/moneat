import React from 'react'
import {beforeEach, describe, expect, it, vi} from 'vitest'
import {render, screen, waitFor} from '@testing-library/react'

const {mockApi, mockRouteState, mockFeatures} = vi.hoisted(() => ({
  mockApi: {
    isAuthenticated: vi.fn(),
    checkAuth: vi.fn(),
  },
  mockRouteState: {
    pathname: '/monitoring',
  },
  mockFeatures: {
    modules: [] as string[],
  },
}))

vi.mock('@/lib/api', () => ({
  api: mockApi,
}))

vi.mock('@/hooks/useEnterpriseFeatures', () => ({
  useEnterpriseFeatures: () => ({data: mockFeatures}),
  hasEnterpriseModule: (features: {modules?: string[]} | undefined, moduleName: string) =>
    features?.modules?.includes(moduleName) ?? false,
}))

vi.mock('@tanstack/react-router', () => ({
  createFileRoute: () => (options: Record<string, unknown>) => ({...options, options}),
  Link: ({children, to, ...props}: {children: React.ReactNode; to?: string}) =>
    React.createElement('a', {href: to, ...props}, children),
  Outlet: () => <div data-testid="monitoring-outlet" />,
  redirect: (options: Record<string, unknown>) => ({...options, __redirect: true}),
  useRouterState: () => ({location: {pathname: mockRouteState.pathname}}),
}))

vi.mock('@/components/ui/dropdown-menu', () => ({
  DropdownMenu: ({children}: {children: React.ReactNode}) => <div>{children}</div>,
  DropdownMenuTrigger: ({children}: {children: React.ReactNode}) => <>{children}</>,
  DropdownMenuContent: ({children}: {children: React.ReactNode}) => <div role="menu">{children}</div>,
  DropdownMenuItem: ({children}: {children: React.ReactNode}) => <div role="menuitem">{children}</div>,
}))

vi.mock('@/components/ui/tooltip', () => ({
  TooltipProvider: ({children}: {children: React.ReactNode}) => <>{children}</>,
  Tooltip: ({children}: {children: React.ReactNode}) => <>{children}</>,
  TooltipTrigger: ({children}: {children: React.ReactNode}) => <>{children}</>,
  TooltipContent: ({children}: {children: React.ReactNode}) => <div>{children}</div>,
}))

import {Route as MonitoringRoute} from '../monitoring'

let monitoringNavWidth = 900

const tabWidths: Record<string, number> = {
  Hosts: 78,
  Map: 66,
  Containers: 112,
  Processes: 106,
  Network: 98,
  Events: 88,
  Kubernetes: 118,
  Databases: 106,
  Debugger: 104,
  'Network Devices': 146,
  SBOM: 74,
  More: 76,
}

function getElementWidth(element: HTMLElement): number {
  const label = element.textContent?.trim() ?? ''
  return tabWidths[label] ?? 72
}

function renderMonitoringRoute() {
  const Component = (MonitoringRoute as unknown as {component: React.ComponentType}).component
  return render(<Component />)
}

describe('monitoring route shell', () => {
  beforeEach(() => {
    vi.clearAllMocks()
    mockApi.isAuthenticated.mockReturnValue(true)
    mockApi.checkAuth.mockResolvedValue(true)
    mockRouteState.pathname = '/monitoring'
    mockFeatures.modules = []
    monitoringNavWidth = 900

    Object.defineProperty(globalThis.HTMLElement.prototype, 'clientWidth', {
      configurable: true,
      get() {
        return this.getAttribute('aria-label') === 'Monitoring tabs' ? monitoringNavWidth : 0
      },
    })
    Object.defineProperty(globalThis.HTMLElement.prototype, 'offsetWidth', {
      configurable: true,
      get() {
        return getElementWidth(this)
      },
    })
    vi.stubGlobal('ResizeObserver', class {
      observe(): void {}
      unobserve(): void {}
      disconnect(): void {}
    })
  })

  it('redirects unauthenticated sessions before loading monitoring', async () => {
    mockApi.isAuthenticated.mockReturnValue(false)
    mockApi.checkAuth.mockResolvedValue(false)

    const beforeLoad = (MonitoringRoute as unknown as {beforeLoad: () => Promise<void>}).beforeLoad

    await expect(beforeLoad()).rejects.toMatchObject({
      __redirect: true,
      to: '/login',
    })
    expect(mockApi.checkAuth).toHaveBeenCalled()
  })

  it('renders core monitoring tabs and hides Datadog tabs without the module', () => {
    renderMonitoringRoute()

    expect(screen.getByRole('link', {name: /Hosts/})).toHaveAttribute('href', '/monitoring')
    expect(screen.getByRole('link', {name: /Map/})).toHaveAttribute('href', '/monitoring/map')
    expect(screen.getByRole('link', {name: /Containers/})).toHaveAttribute('href', '/monitoring/containers')
    expect(screen.queryByRole('link', {name: /Processes/})).not.toBeInTheDocument()
    expect(screen.getByLabelText('View docs')).toHaveAttribute('href', '/docs/datadog-agent/agent-setup')
    expect(screen.getByTestId('monitoring-outlet')).toBeInTheDocument()
  })

  it('moves overflowing monitoring tabs into the More menu and keeps overflow routes active', async () => {
    mockFeatures.modules = ['datadog']
    mockRouteState.pathname = '/monitoring/debugger'
    monitoringNavWidth = 230

    renderMonitoringRoute()

    const moreButton = await screen.findByRole('button', {name: /More/})
    await waitFor(() => expect(screen.queryByRole('link', {name: /Debugger/})).toBeInTheDocument())

    expect(moreButton).toHaveClass('text-primary')
    expect(screen.getByRole('link', {name: /Hosts/})).toBeInTheDocument()
    expect(screen.getByRole('link', {name: /Map/})).toBeInTheDocument()
    expect(screen.getByRole('link', {name: /Debugger/})).toHaveClass('text-primary')
    expect(screen.getByRole('link', {name: /Network Devices/})).toHaveAttribute(
      'href',
      '/monitoring/network-devices',
    )
    expect(screen.getByLabelText('View docs')).toHaveAttribute('href', '/docs/datadog-agent/debugger')
  })

  it('uses detail shells instead of the monitoring tab strip for nested inventory pages', () => {
    mockRouteState.pathname = '/monitoring/hosts/i-123'
    const Component = (MonitoringRoute as unknown as {component: React.ComponentType}).component

    const {rerender} = render(<Component />)

    expect(screen.queryByLabelText('Monitoring tabs')).not.toBeInTheDocument()
    expect(screen.getByTestId('monitoring-outlet')).toBeInTheDocument()

    mockRouteState.pathname = '/monitoring/system-42'
    rerender(<Component />)

    expect(screen.getByRole('link', {name: /Back to Monitoring/})).toHaveAttribute('href', '/monitoring')
    expect(screen.queryByLabelText('Monitoring tabs')).not.toBeInTheDocument()
  })
})
