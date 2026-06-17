import React from 'react'
import {QueryClient, QueryClientProvider} from '@tanstack/react-query'
import {render, screen} from '@testing-library/react'
import {afterEach, beforeEach, describe, expect, it, vi} from 'vitest'

vi.mock('@tanstack/react-router', () => ({
  Link: ({children, to, ...props}: {readonly children: React.ReactNode; readonly to: string}) => (
    <a href={to} {...props}>
      {children}
    </a>
  ),
}))

// PricingBand pulls plans from /billing/plans; stub the API so it renders deterministically.
const {mockGetBillingPlans} = vi.hoisted(() => ({mockGetBillingPlans: vi.fn()}))
vi.mock('@/lib/api', () => ({api: {getBillingPlans: mockGetBillingPlans}}))

import {Landing} from '../Landing'

const GB = 1024 * 1024 * 1024

function makeTier(tierName: string, monthlyPriceCents: number, monthlyGbLimit: number) {
  return {
    tier: {
      tierName,
      monthlyPriceCents,
      yearlyPriceCents: monthlyPriceCents * 10,
      monthlyGbLimit,
      retentionDays: 3,
      maxProjects: null,
      maxSystems: 10,
      monitorIntervalSeconds: 60,
      sessionReplayEnabled: true,
      statusPagesEnabled: true,
      statusPageCustomDomainEnabled: true,
      slackEnabled: true,
      discordEnabled: true,
      incidentIoEnabled: true,
      samlEnabled: tierName === 'TEAM',
      oidcEnabled: tierName === 'TEAM',
      prioritySupportEnabled: tierName === 'TEAM',
      slaEnabled: false,
      customRetentionEnabled: false,
      overageRateCentsPerGb: 40,
    },
    trialDays: 14,
  }
}

function renderLanding() {
  const queryClient = new QueryClient({defaultOptions: {queries: {retry: false}}})
  return render(
    <QueryClientProvider client={queryClient}>
      <Landing />
    </QueryClientProvider>,
  )
}

const serviceMapLabel = 'Live service map: Datadog, Sentry, and OTLP telemetry flowing into one platform'
const originalMatchMedia = Object.getOwnPropertyDescriptor(globalThis.window, 'matchMedia')
const originalPauseAnimations = Object.getOwnPropertyDescriptor(
  globalThis.SVGSVGElement.prototype,
  'pauseAnimations',
)

function restoreProperty(target: object, property: PropertyKey, descriptor: PropertyDescriptor | undefined) {
  if (descriptor) {
    Object.defineProperty(target, property, descriptor)
  } else {
    Reflect.deleteProperty(target, property)
  }
}

function stubReducedMotion(matches: boolean) {
  Object.defineProperty(globalThis.window, 'matchMedia', {
    configurable: true,
    value: vi.fn().mockReturnValue({
      matches,
      addEventListener: vi.fn(),
      removeEventListener: vi.fn(),
    }),
  })
}

describe('Landing', () => {
  beforeEach(() => {
    stubReducedMotion(false)
    mockGetBillingPlans.mockResolvedValue({
      plans: [
        makeTier('FREE', 0, 1 * GB),
        makeTier('PRO', 2900, 50 * GB),
        makeTier('TEAM', 7900, 200 * GB),
        makeTier('BUSINESS', 49900, 1000 * GB),
      ],
      stripeEnabled: false,
    })
  })

  afterEach(() => {
    vi.restoreAllMocks()
    restoreProperty(globalThis.window, 'matchMedia', originalMatchMedia)
    restoreProperty(globalThis.SVGSVGElement.prototype, 'pauseAnimations', originalPauseAnimations)
  })

  it('renders the hero service map with deterministic source and service wiring', () => {
    const {container} = renderLanding()

    expect(
      screen.getByRole('heading', {
        name: 'Switch from Sentry and Datadog. Keep your SDK and agent.',
      }),
    ).toBeInTheDocument()
    expect(screen.getAllByText('Datadog Agent')[0]).toBeInTheDocument()
    expect(screen.getAllByText('Sentry SDK')[0]).toBeInTheDocument()
    expect(screen.getByText('OTLP')).toBeInTheDocument()
    expect(screen.getByText('payments')).toBeInTheDocument()
    expect(screen.getByText('512ms p95')).toBeInTheDocument()
    expect(screen.getByText('cluster · last 60s')).toBeInTheDocument()

    const svg = container.querySelector(`svg[aria-label="${serviceMapLabel}"]`)
    expect(svg).toBeInTheDocument()
    expect(svg).toHaveAttribute('viewBox', '0 0 1120 560')
    expect(svg).not.toHaveAttribute('role', 'img')

    const wireIds = Array.from(svg?.querySelectorAll('defs path') ?? []).map((path) => path.id)
    expect(wireIds).toHaveLength(15)
    expect(wireIds.every((id) => id.length > 0 && !id.includes(':'))).toBe(true)
    expect(svg?.querySelectorAll('animateMotion')).toHaveLength(32)
    expect(svg?.querySelectorAll('mpath[href^="#"]')).toHaveLength(32)
  })

  it('pauses service-map animations for reduced-motion users', () => {
    const pauseAnimations = vi.fn()
    Object.defineProperty(globalThis.SVGSVGElement.prototype, 'pauseAnimations', {
      configurable: true,
      value: pauseAnimations,
    })
    stubReducedMotion(true)

    renderLanding()

    expect(pauseAnimations).toHaveBeenCalledTimes(1)
  })

  it('renders the condensed pricing band from the billing API', async () => {
    renderLanding()

    // Ingestion figures and prices come straight from /billing/plans, like the /pricing page.
    expect(await screen.findByText('1 GB ingestion')).toBeInTheDocument()
    expect(screen.getByText('50 GB ingestion')).toBeInTheDocument()
    expect(screen.getByText('200 GB ingestion')).toBeInTheDocument()
    expect(screen.getByText('$29')).toBeInTheDocument()
    expect(screen.getByText('$79')).toBeInTheDocument()

    // One stable highlight per tier.
    expect(screen.getByText('Every signal type included')).toBeInTheDocument()
    expect(screen.getByText('Slack & Discord alerts')).toBeInTheDocument()
    expect(screen.getByText('SSO (SAML / OIDC)')).toBeInTheDocument()

    // Static Enterprise card is appended; BUSINESS is filtered out of the self-serve band.
    expect(screen.getByText('Volume discounts')).toBeInTheDocument()
    expect(screen.queryByText('$499')).not.toBeInTheDocument()
  })
})
