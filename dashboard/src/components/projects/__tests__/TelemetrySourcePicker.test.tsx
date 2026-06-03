import {render, screen, fireEvent} from '@testing-library/react'
import {describe, it, expect, vi} from 'vitest'

import {TelemetrySourcePicker} from '@/components/projects/TelemetrySourcePicker'

describe('TelemetrySourcePicker', () => {
  it('renders the available sources', () => {
    render(<TelemetrySourcePicker value={['opentelemetry']} onChange={() => {}} />)
    expect(screen.getByRole('button', {name: /OpenTelemetry/})).toBeTruthy()
    expect(screen.getByRole('button', {name: /Sentry SDK/})).toBeTruthy()
    expect(screen.getByRole('button', {name: /Datadog Agent/})).toBeTruthy()
  })

  it('adds a source on click', () => {
    const onChange = vi.fn()
    render(<TelemetrySourcePicker value={['opentelemetry']} onChange={onChange} />)
    fireEvent.click(screen.getByRole('button', {name: /Sentry SDK/}))
    expect(onChange).toHaveBeenCalledWith(['opentelemetry', 'sentry-sdk'])
  })

  it('removes a selected source while another remains', () => {
    const onChange = vi.fn()
    render(<TelemetrySourcePicker value={['opentelemetry', 'sentry-sdk']} onChange={onChange} />)
    fireEvent.click(screen.getByRole('button', {name: /Sentry SDK/}))
    expect(onChange).toHaveBeenCalledWith(['opentelemetry'])
  })

  it('keeps the last source when toggling it off', () => {
    const onChange = vi.fn()
    render(<TelemetrySourcePicker value={['opentelemetry']} onChange={onChange} />)
    fireEvent.click(screen.getByRole('button', {name: /OpenTelemetry/}))
    expect(onChange).toHaveBeenCalledWith(['opentelemetry'])
  })
})
