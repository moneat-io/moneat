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

import {render, waitFor} from '@testing-library/react'
import {QueryClient, QueryClientProvider} from '@tanstack/react-query'
import React, {type ReactElement, type ReactNode} from 'react'
import {afterEach, beforeEach, describe, expect, it, vi} from 'vitest'

type MockRouteConfig = Readonly<{component?: React.ComponentType}>

const apiMocks = {
  get: vi.fn(),
  getApmResourceStats: vi.fn(),
  getApmServices: vi.fn(),
  getApmTraces: vi.fn(),
  isAuthenticated: vi.fn(),
}

const routerMocks = {
  navigate: vi.fn(),
  pathname: '/monitoring/network-devices',
}

const mockedModuleIds = [
  '@tanstack/react-router',
  '@/components/command-palette/AiChatContent',
  '@/lib/api',
  'react-syntax-highlighter',
  'react-syntax-highlighter/dist/esm/styles/hljs',
] as const

function renderWithClient(ui: ReactElement) {
  const queryClient = new QueryClient({
    defaultOptions: {
      queries: {retry: false},
      mutations: {retry: false},
    },
  })
  const result = render(
    <QueryClientProvider client={queryClient}>
      {ui}
    </QueryClientProvider>,
  )
  return {
    ...result,
    queryClient,
  }
}

function routeElement(route: unknown): ReactElement {
  const Component = (route as MockRouteConfig).component
  if (!Component) throw new Error('route has no component')
  return <Component />
}

function installMocks() {
  vi.resetModules()
  vi.doMock('@/lib/api', () => ({api: apiMocks}))
  vi.doMock('@/components/command-palette/AiChatContent', () => ({
    AiChatContent: () => <div data-testid="ai-chat-content" />,
  }))
  vi.doMock('react-syntax-highlighter', () => {
    const SyntaxHighlighter = ({children}: {readonly children?: ReactNode}) => <pre>{children}</pre>
    return {
      default: SyntaxHighlighter,
      Prism: SyntaxHighlighter,
    }
  })
  vi.doMock('react-syntax-highlighter/dist/esm/styles/hljs', () => ({
    atomOneDark: {},
  }))
  vi.doMock('@tanstack/react-router', () => ({
    createFileRoute: () => (config: MockRouteConfig) => ({
      ...config,
      useParams: () => ({resourceType: 'pods'}),
      useSearch: () => ({}),
    }),
    Link: ({children, ...props}: {readonly children?: ReactNode}) => React.createElement('a', props, children),
    Outlet: () => null,
    redirect: (options: Record<string, unknown>) => ({...options, __redirect: true}),
    useNavigate: () => routerMocks.navigate,
    useRouterState: () => ({location: {pathname: routerMocks.pathname}}),
  }))
}

async function loadModules() {
  installMocks()
  const [
    aiSplitPanel,
    traceList,
    databaseMonitoring,
    kubernetesIndex,
    kubernetesResource,
    networkDevices,
    networkFlows,
    networkPaths,
    networkTraps,
    sbom,
    services,
    traces,
  ] = await Promise.all([
    import('@/components/AiSplitPanel'),
    import('@/components/apm/TraceList'),
    import('@/routes/monitoring.databases'),
    import('@/routes/monitoring.kubernetes.index'),
    import('@/routes/monitoring.kubernetes.$resourceType'),
    import('@/routes/monitoring.network-devices'),
    import('@/routes/monitoring.network-devices.flows'),
    import('@/routes/monitoring.network-devices.paths'),
    import('@/routes/monitoring.network-devices.traps'),
    import('@/routes/monitoring.sbom'),
    import('@/routes/services.index'),
    import('@/routes/traces'),
  ])

  return {
    AiSplitPanel: aiSplitPanel.AiSplitPanel,
    TraceList: traceList.TraceList,
    DatabaseMonitoringRoute: databaseMonitoring.Route,
    KubernetesIndexRoute: kubernetesIndex.Route,
    KubernetesResourceRoute: kubernetesResource.Route,
    NetworkDevicesRoute: networkDevices.Route,
    NetworkFlowsRoute: networkFlows.Route,
    NetworkPathsRoute: networkPaths.Route,
    NetworkTrapsRoute: networkTraps.Route,
    SbomRoute: sbom.Route,
    ServicesRoute: services.Route,
    TracesRoute: traces.Route,
  }
}

function emptyGetResponse(url: string): Promise<unknown> {
  if (url.includes('/infra/dbm/queries')) return Promise.resolve({queries: []})
  if (url.includes('/infra/k8s-resources')) return Promise.resolve({resources: []})
  if (url.includes('/network-devices/flows')) return Promise.resolve({flows: []})
  if (url.includes('/network-devices/paths')) return Promise.resolve({paths: []})
  if (url.includes('/network-devices/traps')) return Promise.resolve({traps: []})
  if (url.includes('/network-devices')) return Promise.resolve({devices: []})
  if (url.includes('/infra/sbom')) return Promise.resolve({packages: []})
  return Promise.resolve({})
}

describe('static-quality fallback coverage', () => {
  beforeEach(() => {
    vi.clearAllMocks()
    routerMocks.pathname = '/monitoring/network-devices'
    apiMocks.get.mockImplementation((url: string) => emptyGetResponse(url))
    apiMocks.getApmResourceStats.mockResolvedValue({resources: []})
    apiMocks.getApmServices.mockResolvedValue({
      services: [],
      summary: {total: 0, alerting: 0, degraded: 0},
    })
    apiMocks.getApmTraces.mockResolvedValue({traces: []})
    apiMocks.isAuthenticated.mockReturnValue(true)
  })

  afterEach(() => {
    mockedModuleIds.forEach((moduleId) => {
      vi.doUnmock(moduleId)
    })
    vi.resetModules()
  })

  it('renders small empty API states for changed fallback paths', async () => {
    const modules = await loadModules()
    const {queryClient} = renderWithClient(
      <>
        <modules.AiSplitPanel>
          <div>Main content</div>
        </modules.AiSplitPanel>
        <modules.TraceList />
        {routeElement(modules.DatabaseMonitoringRoute)}
        {routeElement(modules.KubernetesIndexRoute)}
        {routeElement(modules.KubernetesResourceRoute)}
        {routeElement(modules.NetworkDevicesRoute)}
        {routeElement(modules.NetworkFlowsRoute)}
        {routeElement(modules.NetworkPathsRoute)}
        {routeElement(modules.NetworkTrapsRoute)}
        {routeElement(modules.SbomRoute)}
        {routeElement(modules.ServicesRoute)}
        {routeElement(modules.TracesRoute)}
      </>,
    )

    await waitFor(() => {
      expect(apiMocks.get).toHaveBeenCalledWith('/v1/network-devices?limit=100')
      expect(apiMocks.get).toHaveBeenCalledWith('/v1/infra/dbm/queries?limit=50')
      expect(apiMocks.getApmTraces).toHaveBeenCalled()
    })

    queryClient.clear()
  })
})
