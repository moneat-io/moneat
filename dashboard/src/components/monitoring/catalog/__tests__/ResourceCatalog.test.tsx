import {type MouseEvent, type ReactNode} from 'react'
import {screen, waitFor} from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import {beforeEach, describe, expect, it, vi} from 'vitest'
import {renderWithQueryClient} from '@/test/utils'
import {ResourceCatalog} from '../ResourceCatalog'
import {ResourceDetailPanel} from '../ResourceDetailPanel'
import {MOCK_RESOURCES, type Resource} from '../resourceCatalogData'

const mockApi = vi.hoisted(() => ({
  get: vi.fn(),
}))

vi.mock('@/lib/api', () => ({
  api: mockApi,
}))

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

const checkout = MOCK_RESOURCES.find((resource) => resource.id === 'svc-checkout')
if (!checkout) throw new Error('checkout fixture missing')

const emptyResource: Resource = {
  ...checkout,
  id: 'host:1:42',
  name: 'empty-host',
  kind: 'host',
  health: 'unknown',
  owner: null,
  tags: [],
  telemetry: {cpuPct: null, memPct: null},
  vulns: {critical: 0, high: 0, medium: 0, low: 0},
  posture: [],
  monthlyUsd: 0,
  costTrendPct: 0,
  costBreakdown: [],
  relationships: [],
  changes: [],
  metadata: [],
}

describe('ResourceCatalog', () => {
  beforeEach(() => {
    vi.clearAllMocks()
    mockApi.get.mockResolvedValue(MOCK_RESOURCES)
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

    await user.click(screen.getByRole('tab', {name: 'Telemetry'}))
    expect(screen.getByText('CPU utilization')).toBeInTheDocument()
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

    renderWithQueryClient(<ResourceDetailPanel resource={emptyResource} onSelect={onSelect} />)

    expect(screen.getByRole('heading', {name: 'empty-host'})).toBeInTheDocument()

    await user.click(screen.getByRole('tab', {name: 'Telemetry'}))
    expect(screen.getByRole('link', {name: /Open in Metrics/})).toHaveAttribute('href', '/monitoring/hosts/$hostId')
    expect(screen.getByText('No telemetry data')).toBeInTheDocument()

    await user.click(screen.getByRole('tab', {name: 'Relationships'}))
    expect(screen.getByText('No mapped relationships')).toBeInTheDocument()

    await user.click(screen.getByRole('tab', {name: 'Ownership & Tags'}))
    expect(screen.getByText('No owner assigned')).toBeInTheDocument()
    expect(screen.getByRole('link', {name: /Claim ownership/})).toHaveAttribute('href', '/settings')
    expect(screen.getByText('No tags.')).toBeInTheDocument()

    await user.click(screen.getByRole('tab', {name: 'Security'}))
    expect(screen.getByText('No open vulnerabilities')).toBeInTheDocument()

    await user.click(screen.getByRole('tab', {name: 'Cost'}))
    expect(screen.getByText('$0')).toBeInTheDocument()

    await user.click(screen.getByRole('tab', {name: 'Changes'}))
    expect(screen.getByText('No recent changes')).toBeInTheDocument()
    expect(onSelect).not.toHaveBeenCalled()
  })
})
