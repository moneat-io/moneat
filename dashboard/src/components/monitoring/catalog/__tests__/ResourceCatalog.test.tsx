import {useState, type MouseEvent, type ReactNode} from 'react'
import {screen, waitFor} from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import {beforeEach, describe, expect, it, vi} from 'vitest'
import {renderWithQueryClient} from '@/test/utils'
import {HealthBadge} from '../CatalogPrimitives'
import {ResourceCatalog} from '../ResourceCatalog'
import {ResourceDetailPanel} from '../ResourceDetailPanel'
import type {Resource} from '../resourceCatalogData'

const mockApi = vi.hoisted(() => ({
  get: vi.fn(),
  isAuthenticated: vi.fn(() => false),
  getCurrentUser: vi.fn(),
  getOrganizationTeams: vi.fn(),
  claimResourceOwnership: vi.fn(),
  deleteResourceOwnership: vi.fn(),
}))

vi.mock('@/lib/api', () => ({
  api: mockApi,
}))

// Telemetry charts render through recharts' ResponsiveContainer, which needs a
// ResizeObserver this jsdom env does not provide. Stub the chart parts (chart
// titles render outside them, so the assertions below still hold).
vi.mock('recharts', () => {
  const Pass = ({children}: {readonly children?: ReactNode}) => <div>{children}</div>
  const Nothing = () => null
  return {
    Area: Nothing,
    AreaChart: Pass,
    CartesianGrid: Nothing,
    Legend: Nothing,
    Line: Nothing,
    LineChart: Pass,
    ResponsiveContainer: Pass,
    Tooltip: Nothing,
    XAxis: Nothing,
    YAxis: Nothing,
  }
})

vi.mock('@tanstack/react-router', async (importOriginal) => {
  const actual = await importOriginal<typeof import('@tanstack/react-router')>()
  return {
    ...actual,
    Link: ({to, children, className, onClick}: {
      readonly to?: unknown
      readonly children: ReactNode
      readonly className?: string
      readonly onClick?: () => void
    }) => (
      <a
        href={typeof to === 'string' ? to : '#'}
        className={className}
        onClick={(event: MouseEvent<HTMLAnchorElement>) => {
          event.preventDefault()
          onClick?.()
        }}
      >
        {children}
      </a>
    ),
  }
})

function makeResource(overrides: Partial<Resource> & Pick<Resource, 'id' | 'name' | 'kind'>): Resource {
  return {
    health: 'healthy',
    environment: 'prod',
    region: 'us-east-1',
    cloud: 'aws',
    owner: null,
    tags: [],
    telemetry: {cpuPct: 20, memPct: 30},
    vulns: {critical: 0, high: 0, medium: 0, low: 0},
    sbomComponents: 0,
    posture: [],
    findings: [],
    monthlyUsd: 0,
    costTrendPct: 0,
    costBreakdown: [],
    relationships: [],
    changes: [],
    metadata: [],
    firstSeen: '2026-01-04T00:00:00Z',
    lastChange: '2026-06-07T12:00:00.000Z',
    ...overrides,
  }
}

const checkout = makeResource({
  id: 'svc-checkout',
  name: 'checkout-api',
  kind: 'service',
  vulns: {critical: 1, high: 2, medium: 3, low: 4},
  monthlyUsd: 1200,
  metadata: [{label: 'Runtime', value: 'Go 1.23'}],
  relationships: [
    {relation: 'Depends on', name: 'payments-api', kind: 'service', health: 'healthy', targetId: 'svc-payments'},
  ],
  changes: [{ts: '2026-06-07T10:00:00.000Z', kind: 'deploy', summary: 'Deployed v2026.6.41', actor: 'theo'}],
})

const payments = makeResource({
  id: 'svc-payments',
  name: 'payments-api',
  kind: 'service',
  vulns: {critical: 0, high: 1, medium: 2, low: 3},
  monthlyUsd: 800,
  changes: [{ts: '2026-06-06T10:00:00.000Z', kind: 'deploy', summary: 'Deployed v9', actor: 'dana'}],
})

const RESOURCES: readonly Resource[] = [checkout, payments]

const emptyResource = makeResource({
  id: 'host:1:42',
  name: 'empty-host',
  kind: 'host',
  health: 'unknown',
  telemetry: {cpuPct: null, memPct: null},
})

const sampleTelemetry = {
  kind: 'service',
  rangeSeconds: 86_400,
  intervalSeconds: 300,
  metrics: [
    {key: 'cpu', label: 'CPU utilization', unit: '%', lines: [{name: 'CPU', points: [{ts: 1, value: 12}]}]},
    {key: 'errorRate', label: 'Error rate', unit: '%', lines: [{name: 'Errors', points: [{ts: 1, value: 1}]}]},
  ],
}

describe('ResourceCatalog', () => {
  beforeEach(() => {
    vi.clearAllMocks()
    mockApi.getOrganizationTeams.mockResolvedValue([])
    mockApi.get.mockImplementation((path: string) =>
      path.startsWith('/monitoring/resources/telemetry')
        ? Promise.resolve(sampleTelemetry)
        : Promise.resolve(RESOURCES),
    )
  })

  it('filters resources, opens setup links, and drills into the detail panel', async () => {
    const user = userEvent.setup()
    renderWithQueryClient(<ResourceCatalog />)

    expect(await screen.findByRole('button', {name: 'Open checkout-api'})).toBeInTheDocument()
    expect(mockApi.get).toHaveBeenCalledWith('/monitoring/resources')

    await user.type(screen.getByPlaceholderText(/Search resources/i), 'checkout')

    expect(screen.getByRole('button', {name: 'Open checkout-api'})).toBeInTheDocument()

    const typeToggle = screen.getAllByText('Type')[0].closest('button')!
    await user.click(typeToggle)
    expect(typeToggle).toHaveAttribute('aria-expanded', 'false')

    await user.click(screen.getByRole('button', {name: /Configure/}))
    const cloudSetupLink = screen.getByRole('link', {name: /Cloud accounts/})
    expect(cloudSetupLink).toHaveAttribute('href', '/setup')
    await user.click(cloudSetupLink)
    await waitFor(() => expect(screen.queryByRole('dialog', {name: 'Set up monitoring'})).not.toBeInTheDocument())

    await user.click(screen.getByRole('button', {name: 'Open checkout-api'}))

    expect(screen.getByRole('heading', {name: 'checkout-api'})).toBeInTheDocument()
    expect(screen.getByText('Go 1.23')).toBeInTheDocument()

    // Telemetry is now folded into the default Overview tab.
    expect(await screen.findByText('CPU utilization')).toBeInTheDocument()
    expect(screen.getByText('Error rate')).toBeInTheDocument()

    await user.click(screen.getByRole('tab', {name: 'Relationships'}))
    await user.click(screen.getAllByRole('button', {name: /payments-api/}).at(-1)!)
    expect(screen.getByRole('heading', {name: 'payments-api'})).toBeInTheDocument()

    await user.click(screen.getByRole('tab', {name: 'Security'}))
    expect(screen.getByText(/open findings/)).toBeInTheDocument()

    await user.click(screen.getByRole('tab', {name: 'Cost'}))
    expect(screen.getByText('Estimated monthly cost')).toBeInTheDocument()

    await user.click(screen.getByRole('tab', {name: 'Changes'}))
    expect(screen.getByText(/Deploy/)).toBeInTheDocument()

    await user.click(screen.getByRole('button', {name: 'Close detail panel'}))
    await waitFor(() => expect(screen.queryByRole('heading', {name: 'payments-api'})).not.toBeInTheDocument())
  }, 10_000)

  it('renders empty detail states for sparse resources', async () => {
    const user = userEvent.setup()
    const onSelect = vi.fn()

    mockApi.get.mockResolvedValue({kind: 'host', rangeSeconds: 86_400, intervalSeconds: 300, metrics: []})
    renderWithQueryClient(<ResourceDetailPanel resource={emptyResource} onSelect={onSelect} />)

    expect(screen.getByRole('heading', {name: 'empty-host'})).toBeInTheDocument()

    // Overview is the default tab and carries the telemetry section + alert rules link.
    expect(screen.getByRole('link', {name: /Alert rules/})).toHaveAttribute('href', '/monitoring/alerts')
    expect(await screen.findByText('No telemetry data')).toBeInTheDocument()

    await user.click(screen.getByRole('tab', {name: 'Relationships'}))
    expect(screen.getByText('No mapped relationships')).toBeInTheDocument()

    await user.click(screen.getByRole('tab', {name: 'Ownership & Tags'}))
    expect(screen.getByText('No owner assigned')).toBeInTheDocument()
    expect(screen.getByRole('button', {name: /Assign owner/})).toBeInTheDocument()
    expect(screen.getByText('No tags.')).toBeInTheDocument()

    await user.click(screen.getByRole('tab', {name: 'Security'}))
    expect(screen.getByText('No open vulnerabilities')).toBeInTheDocument()

    await user.click(screen.getByRole('tab', {name: 'Cost'}))
    expect(screen.getByText('No cost data')).toBeInTheDocument()

    await user.click(screen.getByRole('tab', {name: 'Changes'}))
    expect(screen.getByText('No recent changes')).toBeInTheDocument()
    expect(onSelect).not.toHaveBeenCalled()
  })

  it('renders a disk usage tile for each filesystem reported by a host', async () => {
    const onSelect = vi.fn()
    mockApi.get.mockResolvedValue({
      kind: 'host',
      rangeSeconds: 86_400,
      intervalSeconds: 300,
      metrics: [
        {
          key: 'disk:device_name=sda',
          label: 'Disk usage (/dev/sda)',
          unit: '%',
          lines: [{name: '/dev/sda', points: [{ts: 1, value: 12.5}]}],
        },
        {
          key: 'disk:device_name=vda1|mount_point=/',
          label: 'Disk usage (/dev/vda1 at /)',
          unit: '%',
          lines: [{name: '/dev/vda1 at /', points: [{ts: 1, value: 67}]}],
        },
      ],
    })

    renderWithQueryClient(<ResourceDetailPanel resource={emptyResource} onSelect={onSelect} />)

    expect(await screen.findByText('Disk usage (/dev/sda)')).toBeInTheDocument()
    expect(screen.getByText('Disk usage (/dev/vda1 at /)')).toBeInTheDocument()
    expect(document.querySelectorAll('.lucide-hard-drive')).toHaveLength(2)
    expect(onSelect).not.toHaveBeenCalled()
  })

  it('shows telemetry load failures in the detail panel', async () => {
    const onSelect = vi.fn()

    mockApi.get.mockImplementation((path: string) =>
      path.startsWith('/monitoring/resources/telemetry') ? Promise.reject(new Error('unavailable')) : Promise.resolve(RESOURCES),
    )
    renderWithQueryClient(<ResourceDetailPanel resource={emptyResource} onSelect={onSelect} />)

    expect(await screen.findByText('Could not load telemetry')).toBeInTheDocument()
    expect(screen.getByText('Telemetry is temporarily unavailable. Try again shortly.')).toBeInTheDocument()
    expect(onSelect).not.toHaveBeenCalled()
  })

  it('shows the team owner and resets ownership editing when the selected resource changes', async () => {
    const user = userEvent.setup()
    const onSelect = vi.fn()
    mockApi.getOrganizationTeams.mockResolvedValue([
      {id: 'team-1', name: 'Payments', slug: 'payments', slack: '#pay', repo: 'moneat/pay', members: [], currentOnCall: {userId: 'u1', userName: 'Dana'}},
    ])
    const ownedResource = makeResource({
      id: 'service:1:billing',
      name: 'billing-api',
      kind: 'service',
      owner: {teamId: 'team-1', teamName: 'Payments', slack: '#pay', repo: 'moneat/pay', currentOnCall: {userId: 'u1', userName: 'Dana'}},
    })

    function DetailHarness() {
      const [resource, setResource] = useState<Resource>(ownedResource)
      return (
        <>
          <button type="button" onClick={() => setResource(emptyResource)}>
            Switch resource
          </button>
          <ResourceDetailPanel resource={resource} onSelect={onSelect} />
        </>
      )
    }

    renderWithQueryClient(<DetailHarness />)

    await user.click(screen.getByRole('tab', {name: 'Ownership & Tags'}))
    // View mode reads the owning team and the server-resolved current on-call person.
    expect(screen.getByText('Payments')).toBeInTheDocument()
    expect(screen.getByText('Dana')).toBeInTheDocument()
    // No free-text on-call input anymore.
    expect(screen.queryByPlaceholderText('Dana Whitfield')).not.toBeInTheDocument()

    await user.click(screen.getByRole('button', {name: 'Edit'}))
    expect(await screen.findByRole('button', {name: 'Cancel'})).toBeInTheDocument()

    await user.click(screen.getByRole('button', {name: 'Switch resource'}))

    await waitFor(() => expect(screen.queryByRole('button', {name: 'Cancel'})).not.toBeInTheDocument())
    expect(screen.getByText('No owner assigned')).toBeInTheDocument()
    expect(onSelect).not.toHaveBeenCalled()
  })

  it('uses a status dot only for soft health badge variants', () => {
    const {container, rerender} = renderWithQueryClient(<HealthBadge health="critical" />)

    expect(screen.getByText('Critical')).toBeInTheDocument()
    expect(container.querySelector('span.relative.inline-flex')).toBeNull()

    rerender(<HealthBadge health="warn" />)

    expect(screen.getByText('Warning')).toBeInTheDocument()
    expect(container.querySelector('span.relative.inline-flex')).not.toBeNull()
  })
})
