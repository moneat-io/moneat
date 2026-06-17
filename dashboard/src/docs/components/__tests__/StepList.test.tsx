import {render, screen} from '@testing-library/react'
import {describe, expect, it} from 'vitest'
import StepList from '../StepList'

describe('StepList', () => {
  it('renders ordered setup steps with their content', () => {
    render(
      <StepList
        steps={[
          {title: 'Create a key', content: <span>Copy it from settings.</span>},
          {title: 'Configure the exporter', content: <span>Set the OTLP endpoint.</span>},
        ]}
      />,
    )

    expect(screen.getByText('1')).toBeInTheDocument()
    expect(screen.getByText('Create a key')).toBeInTheDocument()
    expect(screen.getByText('Set the OTLP endpoint.')).toBeInTheDocument()
  })
})
