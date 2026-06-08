import React from 'react'
import {beforeEach, describe, expect, it, vi} from 'vitest'
import {fireEvent, render, screen, waitFor} from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import {QueryClient, QueryClientProvider} from '@tanstack/react-query'
import {ServiceTelemetrySetupHub} from '@/components/projects/ServiceTelemetrySetupHub'
import type {Project} from '@/lib/api'

const {mockApi, mockNavigate, mockRouterInvalidate, mockToast} = vi.hoisted(() => ({
  mockApi: {
    isAuthenticated: vi.fn(),
    getSdkVersions: vi.fn(),
    getCurrentUser: vi.fn(),
    getProjectStats: vi.fn(),
    getOtlpApiKeys: vi.fn(),
    getAgentApiKeys: vi.fn(),
    getMonitorHosts: vi.fn(),
    createOtlpApiKey: vi.fn(),
    createAgentApiKey: vi.fn(),
    addProjectTarget: vi.fn(),
  },
  mockNavigate: vi.fn(),
  mockRouterInvalidate: vi.fn(),
  mockToast: vi.fn(),
}))

vi.mock('@/lib/api', () => ({api: mockApi}))
vi.mock('@/hooks/useToast', () => ({useToast: () => ({toast: mockToast})}))
vi.mock('@tanstack/react-router', () => ({
  createFileRoute: () => (opts: Record<string, unknown>) => ({...opts, options: opts}),
  redirect: (opts: Record<string, unknown>) => ({...opts, __redirect: true}),
  Link: ({children, ...props}: {children: React.ReactNode}) => React.createElement('a', props, children),
  useNavigate: () => mockNavigate,
  useRouter: () => ({invalidate: mockRouterInvalidate}),
}))

const baseService: Project = {
  id: 1,
  resourceId: 'svc-checkout',
  name: 'Checkout API',
  slug: 'checkout-api',
  framework: 'react',
  dsn: 'https://public@example.test/1',
  keys: [
    {
      platformTarget: null,
      dsn: 'https://public@example.test/1',
    },
  ],
}

const originalClipboard = Object.getOwnPropertyDescriptor(globalThis.navigator, 'clipboard')

function stubClipboard(writeText = vi.fn()) {
  Object.defineProperty(globalThis.navigator, 'clipboard', {
    configurable: true,
    value: {writeText},
  })
  return writeText
}

function renderHub(service: Project = baseService, selectedSourcesParam?: string) {
  const queryClient = new QueryClient({
    defaultOptions: {queries: {retry: false}, mutations: {retry: false}},
  })
  return render(
    <QueryClientProvider client={queryClient}>
      <ServiceTelemetrySetupHub service={service} selectedSourcesParam={selectedSourcesParam} />
    </QueryClientProvider>
  )
}

describe('ServiceTelemetrySetupHub', () => {
  beforeEach(() => {
    vi.clearAllMocks()
    globalThis.localStorage.clear()
    mockApi.isAuthenticated.mockReturnValue(true)
    mockApi.getSdkVersions.mockResolvedValue({versions: {}})
    mockApi.getCurrentUser.mockResolvedValue({organizationSlug: 'acme'})
    mockApi.getProjectStats.mockResolvedValue({totalEvents: 0})
    mockApi.getOtlpApiKeys.mockResolvedValue({keys: []})
    mockApi.getAgentApiKeys.mockResolvedValue({keys: []})
    mockApi.getMonitorHosts.mockResolvedValue([])
    mockApi.createOtlpApiKey.mockResolvedValue({key: 'otlp-secret'})
    mockApi.createAgentApiKey.mockResolvedValue({key: 'agent-secret'})
    mockApi.addProjectTarget.mockResolvedValue({platformTarget: 'ios', dsn: 'https://public@example.test/ios'})
    if (originalClipboard) {
      Object.defineProperty(globalThis.navigator, 'clipboard', originalClipboard)
    } else {
      delete (globalThis.navigator as {clipboard?: unknown}).clipboard
    }
  })

  it('frames sources and ingestion as the home for a service', async () => {
    renderHub()

    expect(screen.getByRole('heading', {name: 'Sources / Ingestion'})).toBeInTheDocument()
    expect(screen.getByText('Checkout API')).toBeInTheDocument()
    expect(screen.getByText('Service ID')).toBeInTheDocument()
    expect(screen.getByText('svc-checkout')).toBeInTheDocument()
    expect(screen.getByRole('button', {name: 'Copy service ID'})).toBeInTheDocument()
    expect(screen.getByLabelText('Service settings')).toBeInTheDocument()

    expect(screen.getAllByRole('button', {name: /OpenTelemetry/}).length).toBeGreaterThan(0)
    expect(screen.getAllByRole('button', {name: /Sentry SDK/}).length).toBeGreaterThan(0)
    expect(screen.getAllByRole('button', {name: /Datadog Agent/}).length).toBeGreaterThan(0)
    expect(screen.getByRole('heading', {name: 'Connect OpenTelemetry'})).toBeInTheDocument()
    expect(await screen.findByText('OTLP API key')).toBeInTheDocument()
    expect(screen.getByText(/\/v1\/traces\/otlp/)).toBeInTheDocument()
    expect(screen.getByText(/\/v1\/metrics\/otlp/)).toBeInTheDocument()
    expect(screen.getAllByText(/\/v1\/logs\/otlp/).length).toBeGreaterThan(0)
  })

  it('shows the service DSN for the Sentry-compatible setup path and copies it', async () => {
    const writeText = stubClipboard()
    mockApi.getProjectStats.mockResolvedValueOnce({totalEvents: 12})

    renderHub(baseService, 'sentry-sdk')

    expect(screen.getByRole('heading', {name: 'Connect a Sentry-compatible SDK'})).toBeInTheDocument()
    expect((await screen.findAllByText('Receiving')).length).toBeGreaterThan(0)
    expect(await screen.findByText('Service DSN')).toBeInTheDocument()
    expect(screen.getByText('https://public@example.test/1')).toBeInTheDocument()

    fireEvent.click(screen.getByRole('button', {name: 'Copy DSN'}))

    await waitFor(() => {
      expect(writeText).toHaveBeenCalledWith('https://public@example.test/1')
    })
  })

  it('creates an OTLP key and shows the one-time secret in the ingestion snippets', async () => {
    const user = userEvent.setup()

    renderHub(baseService, 'opentelemetry')

    await user.click(screen.getByRole('button', {name: 'Create OTLP key'}))

    await waitFor(() => {
      expect(mockApi.createOtlpApiKey).toHaveBeenCalledWith('Checkout API OTLP')
    })
    expect(await screen.findByText('otlp-secret')).toBeInTheDocument()
    expect(screen.getAllByText(/Authorization=Bearer otlp-secret/).length).toBeGreaterThan(0)
  })

  it('shows service DSNs for target platforms and can add another platform', async () => {
    const user = userEvent.setup()
    const targetService: Project = {
      ...baseService,
      framework: 'unity',
      keys: [
        {platformTarget: null, dsn: 'https://public@example.test/default'},
        {platformTarget: 'android', dsn: 'https://public@example.test/android'},
      ],
    }

    renderHub(targetService, 'sentry-sdk')

    expect(await screen.findByText('Target platforms')).toBeInTheDocument()
    expect(screen.getByText('Service DSNs')).toBeInTheDocument()
    expect(screen.getByRole('tab', {name: 'Default'})).toBeInTheDocument()
    expect(screen.getByRole('tab', {name: 'Android'})).toBeInTheDocument()

    await user.click(screen.getByRole('button', {name: 'Add Platform'}))
    await user.click(screen.getByRole('button', {name: 'iOS'}))

    await waitFor(() => {
      expect(mockApi.addProjectTarget).toHaveBeenCalledWith('svc-checkout', 'ios')
    })
    expect(mockRouterInvalidate).toHaveBeenCalled()
  })

  it('creates a Datadog Agent key from an already configured source state', async () => {
    const user = userEvent.setup()
    mockApi.getAgentApiKeys.mockResolvedValueOnce({keys: [{lastUsedAt: null}]})

    renderHub(baseService, 'datadog-agent')

    expect(screen.getByRole('heading', {name: 'Connect a Datadog Agent'})).toBeInTheDocument()
    expect((await screen.findAllByText('Key created')).length).toBeGreaterThan(0)
    expect(screen.getByText('A reusable key already exists. Existing keys cannot reveal the full secret again.'))
      .toBeInTheDocument()
    expect(screen.getByRole('link', {name: /Full instructions/})).toHaveAttribute(
      'href',
      '/docs/datadog-agent/agent-setup'
    )

    await user.click(screen.getByRole('button', {name: 'Create another agent key'}))

    await waitFor(() => {
      expect(mockApi.createAgentApiKey).toHaveBeenCalledWith('Checkout API Agent')
    })
    expect((await screen.findAllByText('agent-secret')).length).toBeGreaterThan(0)
    expect(screen.getByText('datadog.yaml')).toBeInTheDocument()
    expect(screen.getByText('Run the agent')).toBeInTheDocument()
  })
})
