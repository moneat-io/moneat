import {render, screen} from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import {beforeEach, describe, expect, it, vi} from 'vitest'
import {DocsFeedback} from '../DocsFeedback'

const {mockCaptureFeedback} = vi.hoisted(() => ({
  mockCaptureFeedback: vi.fn(),
}))

vi.mock('@sentry/react', () => ({
  captureFeedback: mockCaptureFeedback,
}))

describe('DocsFeedback', () => {
  beforeEach(() => {
    mockCaptureFeedback.mockClear()
  })

  it('thanks readers after positive feedback', async () => {
    const user = userEvent.setup()

    render(<DocsFeedback slug="intro" />)

    await user.click(screen.getByRole('button', {name: 'Yes'}))

    expect(screen.getByText('Glad it was helpful.')).toBeInTheDocument()
  })

  it('captures negative feedback with the current docs slug', async () => {
    const user = userEvent.setup()

    render(<DocsFeedback slug="logging" />)

    await user.click(screen.getByRole('button', {name: 'No'}))
    await user.type(screen.getByLabelText('What could be improved?'), 'Needs a collector example.')
    await user.click(screen.getByRole('button', {name: 'Submit feedback'}))

    expect(mockCaptureFeedback).toHaveBeenCalledWith({
      message: 'Needs a collector example.',
      tags: {slug: 'logging', type: 'docs-feedback', rating: 'negative'},
      url: 'http://localhost:3000/',
    })
    expect(screen.getByText('Thanks — your feedback was recorded.')).toBeInTheDocument()
  })
})
