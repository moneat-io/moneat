import React from 'react'
import {beforeEach, describe, expect, it, vi} from 'vitest'
import {screen, fireEvent} from '@testing-library/react'
import {renderRoute, clearAuthStorage} from '@/test/utils'

const {mockApi} = vi.hoisted(() => ({
  mockApi: {
    isAuthenticated: vi.fn(),
    getProjects: vi.fn(),
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

import {Route as ConfigurationRoute} from '../configuration'

const mockProject = {
  id: 1,
  resourceId: 'proj-1',
  name: 'Test Service',
  slug: 'test-service',
  framework: 'react',
  keys: [],
}

describe('Configuration page', () => {
  beforeEach(() => {
    vi.clearAllMocks()
    clearAuthStorage()
    mockApi.isAuthenticated.mockReturnValue(true)
    mockApi.getProjects.mockResolvedValue([mockProject])
    mockApi.getProject.mockResolvedValue(mockProject)
    mockApi.getCurrentUser.mockResolvedValue({organizationSlug: 'acme'})
  })

  it('shows an empty state with a create affordance when there are no services', async () => {
    mockApi.getProjects.mockResolvedValue([])
    renderRoute(ConfigurationRoute)

    expect(await screen.findByText('No services yet')).toBeInTheDocument()
    expect(screen.getAllByRole('button', {name: /New Service/}).length).toBeGreaterThan(0)
  })

  it('renders the service selector, telemetry picker, and settings card', async () => {
    renderRoute(ConfigurationRoute)

    expect(await screen.findByRole('option', {name: 'Test Service'})).toBeInTheDocument()
    expect(screen.getByRole('button', {name: /Sentry SDK/})).toBeInTheDocument()
    expect(await screen.findByText('General')).toBeInTheDocument()
    expect(screen.getByLabelText('Service name')).toBeInTheDocument()
  })

  it('reveals the Sentry config only when the Sentry SDK source is enabled', async () => {
    renderRoute(ConfigurationRoute)

    await screen.findByText('General')
    // Default source is OpenTelemetry → no Sentry-specific config.
    expect(screen.queryByLabelText('Service slug')).not.toBeInTheDocument()

    fireEvent.click(screen.getByRole('button', {name: /Sentry SDK/}))

    expect(await screen.findByLabelText('Service slug')).toBeInTheDocument()
  })
})
