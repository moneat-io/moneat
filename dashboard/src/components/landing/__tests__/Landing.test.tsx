import React from 'react'
import {render, screen} from '@testing-library/react'
import {afterEach, beforeEach, describe, expect, it, vi} from 'vitest'

vi.mock('@tanstack/react-router', () => ({
  Link: ({children, to, ...props}: {readonly children: React.ReactNode; readonly to: string}) => (
    <a href={to} {...props}>
      {children}
    </a>
  ),
}))

import {Landing} from '../Landing'

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
  })

  afterEach(() => {
    vi.restoreAllMocks()
    restoreProperty(globalThis.window, 'matchMedia', originalMatchMedia)
    restoreProperty(globalThis.SVGSVGElement.prototype, 'pauseAnimations', originalPauseAnimations)
  })

  it('renders the hero service map with deterministic source and service wiring', () => {
    const {container} = render(<Landing />)

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

    render(<Landing />)

    expect(pauseAnimations).toHaveBeenCalledTimes(1)
  })
})
