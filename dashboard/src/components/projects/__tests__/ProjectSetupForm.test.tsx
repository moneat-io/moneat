import {render, screen} from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import {describe, expect, it, vi} from 'vitest'
import {ProjectSetupForm, type ProjectSetupSubmission} from '@/components/projects/ProjectSetupForm'

describe('ProjectSetupForm', () => {
  it('submits project, platform, targets, and selected telemetry sources', async () => {
    const user = userEvent.setup()
    const onSubmit = vi.fn<(submission: ProjectSetupSubmission) => void>()

    render(<ProjectSetupForm onSubmit={onSubmit} />)

    await user.type(screen.getByLabelText('Project Name'), 'Checkout API')
    await user.click(screen.getByRole('button', {name: /React$/}))
    await user.click(screen.getByRole('button', {name: /Sentry SDK/}))
    await user.click(screen.getByRole('button', {name: 'Create Project'}))

    expect(screen.queryByRole('button', {name: /HTTP logs/})).not.toBeInTheDocument()
    expect(onSubmit).toHaveBeenCalledWith({
      name: 'Checkout API',
      framework: 'react',
      targets: undefined,
      sourceIds: ['opentelemetry', 'sentry-sdk'],
    })
  })

  it('keeps submit disabled until a project name and platform are selected', async () => {
    const user = userEvent.setup()
    const onSubmit = vi.fn()

    render(<ProjectSetupForm onSubmit={onSubmit} />)

    expect(screen.getByRole('button', {name: 'Create Project'})).toBeDisabled()

    await user.type(screen.getByLabelText('Project Name'), 'Mobile app')
    expect(screen.getByRole('button', {name: 'Create Project'})).toBeDisabled()

    await user.click(screen.getByRole('button', {name: 'iOS'}))
    expect(screen.getByRole('button', {name: 'Create Project'})).toBeEnabled()
  })
})
