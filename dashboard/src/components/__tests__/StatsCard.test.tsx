import {render, screen} from '@testing-library/react'
import {Activity} from 'lucide-react'
import {describe, expect, it} from 'vitest'
import {StatsCard, StatsCardSkeleton} from '@/components/charts/StatsCard'

describe('StatsCard', () => {
  it('renders compact accent and default skeleton variants', () => {
    const {container, rerender} = render(<StatsCardSkeleton />)
    expect(container.querySelector('.bg-muted')).toBeInTheDocument()

    rerender(<StatsCardSkeleton accent="emerald" compact className="custom-skeleton" />)
    expect(container.querySelector('.custom-skeleton')).toBeInTheDocument()
    expect(container.querySelector('.bg-success-solid')).toBeInTheDocument()
  })

  it('renders accent, subtitle, and trend variants', () => {
    const {container, rerender} = render(
      <StatsCard
        title="Healthy"
        value="99%"
        icon={Activity}
        accent="blue"
        compact
        subtitle="All clear"
        trend={{value: 12, positive: true}}
      />,
    )

    expect(screen.getByText('Healthy')).toBeInTheDocument()
    expect(screen.getByText('All clear')).toBeInTheDocument()
    expect(screen.getByText(/12%/)).toHaveClass('text-success-fg')
    expect(container.querySelector('.bg-info-solid')).toBeInTheDocument()

    rerender(
      <StatsCard
        title="Regressed"
        value={7}
        icon={Activity}
        valueColor="text-danger-fg"
        trend={{value: -4, positive: false}}
      />,
    )

    expect(screen.getByText('Regressed')).toBeInTheDocument()
    expect(screen.getByText('7')).toHaveClass('text-danger-fg')
    expect(screen.getByText(/4%/)).toHaveClass('text-danger-fg')
  })
})
