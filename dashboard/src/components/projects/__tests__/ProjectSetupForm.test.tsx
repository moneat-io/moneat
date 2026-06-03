import {render, screen} from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import {describe, expect, it, vi} from 'vitest'
import {ProjectSetupForm, type ProjectSetupSubmission} from '@/components/projects/ProjectSetupForm'

describe('ProjectSetupForm', () => {
  it('submits project, platform, targets, and selected telemetry sources', async () => {
    const user = userEvent.setup()
    const onSubmit = vi.fn<(submission: ProjectSetupSubmission) => void>()

    render(<ProjectSetupForm onSubmit={onSubmit} />)

    await user.type(screen.getByLabelText('Service name'), 'Checkout API')
    await user.click(screen.getByRole('button', {name: /React$/}))
    await user.click(screen.getByRole('button', {name: /Sentry SDK/}))
    await user.click(screen.getByRole('button', {name: 'Create Service'}))

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

    expect(screen.getByRole('button', {name: 'Create Service'})).toBeDisabled()

    await user.type(screen.getByLabelText('Service name'), 'Mobile app')
    expect(screen.getByRole('button', {name: 'Create Service'})).toBeDisabled()

    await user.click(screen.getByRole('button', {name: 'iOS'}))
    expect(screen.getByRole('button', {name: 'Create Service'})).toBeEnabled()
  })

  it('filters platform choices by category', async () => {
    const user = userEvent.setup()
    const onSubmit = vi.fn()

    render(<ProjectSetupForm onSubmit={onSubmit} />)

    await user.click(screen.getByRole('button', {name: 'Backend'}))
    expect(screen.getByRole('button', {name: 'Node.js'})).toBeInTheDocument()
    expect(screen.queryByRole('button', {name: 'React'})).not.toBeInTheDocument()

    await user.click(screen.getByRole('button', {name: 'Desktop & Gaming'}))
    expect(screen.getByRole('button', {name: 'Electron'})).toBeInTheDocument()
    expect(screen.getByRole('button', {name: 'Unity'})).toBeInTheDocument()
    expect(screen.queryByRole('button', {name: 'Node.js'})).not.toBeInTheDocument()
  })

  it('requires at least one target platform for multi-target apps', async () => {
    const user = userEvent.setup()
    const onSubmit = vi.fn<(submission: ProjectSetupSubmission) => void>()

    render(<ProjectSetupForm onSubmit={onSubmit} />)

    await user.type(screen.getByLabelText('Service name'), 'Game client')
    await user.click(screen.getByRole('button', {name: 'Desktop & Gaming'}))
    await user.click(screen.getByRole('button', {name: 'Unity'}))
    expect(screen.getByRole('button', {name: 'Create Service'})).toBeEnabled()

    await user.click(screen.getByRole('button', {name: 'Android'}))
    await user.click(screen.getByRole('button', {name: 'iOS'}))
    expect(screen.getByText('Select at least one target platform.')).toBeInTheDocument()
    expect(screen.getByRole('button', {name: 'Create Service'})).toBeDisabled()

    await user.click(screen.getByRole('button', {name: 'Web'}))
    await user.click(screen.getByRole('button', {name: /Datadog Agent/}))
    await user.click(screen.getByRole('button', {name: 'Create Service'}))

    expect(onSubmit).toHaveBeenCalledWith({
      name: 'Game client',
      framework: 'unity',
      targets: ['web'],
      sourceIds: ['opentelemetry', 'datadog-agent'],
    })
  })

  it('shows error, cancel, and submitting states', async () => {
    const user = userEvent.setup()
    const onSubmit = vi.fn()
    const onCancel = vi.fn()

    const {rerender} = render(
      <ProjectSetupForm
        onSubmit={onSubmit}
        onCancel={onCancel}
        cancelLabel="Back"
        error="Unable to create project"
      />
    )

    expect(screen.getByText('Unable to create project')).toBeInTheDocument()
    await user.click(screen.getByRole('button', {name: 'Back'}))
    expect(onCancel).toHaveBeenCalledTimes(1)

    rerender(
      <ProjectSetupForm
        onSubmit={onSubmit}
        onCancel={onCancel}
        cancelLabel="Back"
        isSubmitting
        submittingLabel="Saving project..."
      />
    )

    expect(screen.getByRole('button', {name: 'Saving project...'})).toBeDisabled()
    expect(screen.getByRole('button', {name: 'Back'})).toBeDisabled()
  })
})
