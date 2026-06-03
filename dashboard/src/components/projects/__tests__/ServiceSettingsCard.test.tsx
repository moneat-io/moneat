import React from 'react'
import {beforeEach, describe, expect, it, vi} from 'vitest'
import {render, screen} from '@testing-library/react'
import {QueryClient, QueryClientProvider} from '@tanstack/react-query'
import {ProjectProvider} from '@/contexts/ProjectContext'

const {mockApi} = vi.hoisted(() => ({
  mockApi: {
    getProject: vi.fn(),
    getCurrentUser: vi.fn(),
    updateProject: vi.fn(),
    deleteProject: vi.fn(),
    addProjectTarget: vi.fn(),
  },
}))

vi.mock('@/lib/api', () => ({api: mockApi}))
vi.mock('@/hooks/useToast', () => ({useToast: () => ({toast: vi.fn()})}))
vi.mock('@tanstack/react-router', () => ({
  createFileRoute: () => (opts: Record<string, unknown>) => ({...opts, options: opts}),
  redirect: (o: Record<string, unknown>) => ({...o, __redirect: true}),
  Link: ({children, ...props}: {children: React.ReactNode}) => React.createElement('a', props, children),
  useNavigate: () => vi.fn(),
  useRouter: () => ({invalidate: vi.fn()}),
}))

import {ServiceSettingsCard} from '@/components/projects/ServiceSettingsCard'
import type {TelemetrySourceId} from '@/lib/telemetry-sources'

const mockProject = {
  id: 1,
  resourceId: 'proj-1',
  name: 'Test Service',
  slug: 'test-service',
  framework: 'react',
  keys: [],
}

function renderCard(sourceIds: TelemetrySourceId[]) {
  const queryClient = new QueryClient({
    defaultOptions: {queries: {retry: false}, mutations: {retry: false}},
  })
  return render(
    <QueryClientProvider client={queryClient}>
      <ProjectProvider>
        <ServiceSettingsCard projectId="proj-1" sourceIds={sourceIds} />
      </ProjectProvider>
    </QueryClientProvider>
  )
}

describe('ServiceSettingsCard', () => {
  beforeEach(() => {
    vi.clearAllMocks()
    localStorage.clear()
    mockApi.getProject.mockResolvedValue(mockProject)
    mockApi.getCurrentUser.mockResolvedValue({organizationSlug: 'acme'})
  })

  it('always shows General and the danger zone', async () => {
    renderCard(['opentelemetry'])
    expect(await screen.findByText('General')).toBeInTheDocument()
    expect(screen.getByLabelText('Service name')).toBeInTheDocument()
    expect(screen.getByRole('button', {name: /Delete Service/})).toBeInTheDocument()
  })

  it('hides the Sentry slug/CLI sections for non-Sentry services', async () => {
    renderCard(['opentelemetry'])
    await screen.findByText('General')
    expect(screen.queryByLabelText('Service slug')).not.toBeInTheDocument()
    expect(screen.queryByText('Sentry CLI Configuration')).not.toBeInTheDocument()
    // The OpenTelemetry pointer shows instead.
    expect(screen.getByText('OpenTelemetry')).toBeInTheDocument()
  })

  it('shows the Sentry slug and CLI config when the Sentry SDK source is enabled', async () => {
    renderCard(['sentry-sdk'])
    expect(await screen.findByLabelText('Service slug')).toBeInTheDocument()
    expect(await screen.findByText('Sentry CLI Configuration')).toBeInTheDocument()
    expect(screen.queryByText('OpenTelemetry')).not.toBeInTheDocument()
  })
})
