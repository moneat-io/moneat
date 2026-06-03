import React from 'react'
import {beforeEach, describe, expect, it, vi} from 'vitest'
import {fireEvent, render, screen, waitFor} from '@testing-library/react'
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

const originalClipboard = Object.getOwnPropertyDescriptor(globalThis.navigator, 'clipboard')

function stubClipboard(writeText = vi.fn()) {
  Object.defineProperty(globalThis.navigator, 'clipboard', {
    configurable: true,
    value: {writeText},
  })
  return writeText
}

function renderCard(sourceIds: TelemetrySourceId[], onDeleted?: () => void) {
  const queryClient = new QueryClient({
    defaultOptions: {queries: {retry: false}, mutations: {retry: false}},
  })
  return render(
    <QueryClientProvider client={queryClient}>
      <ProjectProvider>
        <ServiceSettingsCard projectId="proj-1" sourceIds={sourceIds} onDeleted={onDeleted} />
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
    mockApi.updateProject.mockResolvedValue(mockProject)
    mockApi.deleteProject.mockResolvedValue(undefined)
    mockApi.addProjectTarget.mockResolvedValue(undefined)
    if (originalClipboard) {
      Object.defineProperty(globalThis.navigator, 'clipboard', originalClipboard)
    } else {
      delete (globalThis.navigator as {clipboard?: unknown}).clipboard
    }
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

  it('copies Sentry identifiers and renders no CLI config before the org slug loads', async () => {
    const writeText = stubClipboard()
    mockApi.getCurrentUser.mockResolvedValueOnce({organizationSlug: null})

    renderCard(['sentry-sdk'])
    expect(await screen.findByLabelText('Service slug')).toBeInTheDocument()
    expect(screen.queryByText('Sentry CLI Configuration')).not.toBeInTheDocument()

    const slugInput = screen.getByLabelText('Service slug')
    const slugCopyButton = slugInput.parentElement?.querySelector('button')
    expect(slugCopyButton).not.toBeNull()
    fireEvent.click(slugCopyButton as HTMLButtonElement)

    expect(writeText).toHaveBeenCalledWith('test-service')
  })

  it('copies the Sentry CLI config after the organization slug loads', async () => {
    const writeText = stubClipboard()

    const {container} = renderCard(['sentry-sdk'])
    expect(await screen.findByText('Sentry CLI Configuration')).toBeInTheDocument()

    const configCopyButton = container.querySelector('pre')?.parentElement?.querySelector('button')
    expect(configCopyButton).not.toBeNull()
    fireEvent.click(configCopyButton as HTMLButtonElement)

    expect(writeText).toHaveBeenCalledWith(expect.stringContaining('project=test-service'))
  })

  it('saves name and framework changes', async () => {
    renderCard(['sentry-sdk'])
    const nameInput = await screen.findByLabelText('Service name')

    fireEvent.change(nameInput, {target: {value: 'Renamed Service'}})
    fireEvent.click(screen.getByText('Node.js').closest('button') as HTMLButtonElement)
    fireEvent.click(screen.getByRole('button', {name: /Save Changes/}))

    await waitFor(() => {
      expect(mockApi.updateProject).toHaveBeenCalledWith('proj-1', {
        name: 'Renamed Service',
        framework: 'node',
      })
    })
  })

  it('filters platform tabs and blocks target edits until framework changes are saved', async () => {
    renderCard(['sentry-sdk'])
    await screen.findByText('General')

    fireEvent.click(screen.getByRole('button', {name: /Desktop & Gaming/}))
    expect(screen.getByText('Unity')).toBeInTheDocument()
    expect(screen.queryByText('Android')).not.toBeInTheDocument()

    fireEvent.click(screen.getByText('Unity').closest('button') as HTMLButtonElement)

    expect(screen.getByText('Target Platforms')).toBeInTheDocument()
    expect(screen.getByText('Save framework changes first, then add target platforms here.')).toBeInTheDocument()
  })

  it('renders current target platforms and adds another target', async () => {
    mockApi.getProject.mockResolvedValueOnce({
      ...mockProject,
      framework: 'unity',
      keys: [{platformTarget: 'android'}],
    })

    renderCard(['sentry-sdk'])
    expect(await screen.findByText('Target Platforms')).toBeInTheDocument()
    expect(screen.getAllByText('Android').length).toBeGreaterThan(1)

    fireEvent.click(screen.getByRole('button', {name: /Add iOS/}))

    await waitFor(() => {
      expect(mockApi.addProjectTarget).toHaveBeenCalledWith('proj-1', 'ios')
    })
  })

  it('renders Datadog setup guidance when that telemetry source is enabled', async () => {
    renderCard(['datadog-agent'])
    expect(await screen.findByText('Datadog Agent')).toBeInTheDocument()
    expect(screen.queryByText('OpenTelemetry')).not.toBeInTheDocument()
    expect(screen.queryByLabelText('Service slug')).not.toBeInTheDocument()
  })

  it('deletes the selected service and calls the supplied delete callback', async () => {
    localStorage.setItem('selectedProjectId', 'proj-1')
    const onDeleted = vi.fn()

    renderCard(['opentelemetry'], onDeleted)
    fireEvent.click(await screen.findByRole('button', {name: /Delete Service/}))
    fireEvent.click(screen.getAllByRole('button', {name: /Delete Service/}).at(-1) as HTMLButtonElement)

    await waitFor(() => {
      expect(mockApi.deleteProject).toHaveBeenCalledWith('proj-1')
      expect(onDeleted).toHaveBeenCalledTimes(1)
    })
    expect(localStorage.getItem('selectedProjectId')).toBeNull()
  })
})
