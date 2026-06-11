import {screen} from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import {beforeEach, describe, expect, it, vi} from 'vitest'
import {renderWithQueryClient} from '@/test/utils'
import {EventStream} from '../EventStream'

const mockApi = vi.hoisted(() => ({
  getEvents: vi.fn(),
  isAuthenticated: vi.fn(),
}))

vi.mock('@/lib/api', () => ({
  api: mockApi,
}))

describe('EventStream', () => {
  beforeEach(() => {
    vi.clearAllMocks()
    mockApi.isAuthenticated.mockReturnValue(true)
    mockApi.getEvents.mockResolvedValue({
      totalCount: 1,
      events: [
        {
          eventId: 'evt-1',
          title: 'Datadog container lifecycle delete: container 3eca...',
          text: 'container 3eca142d7f7f1e3f287f9cc4449f9a1b9fb68d5afb83b9d20e955ffea8f2e11e lifecycle event',
          timestamp: new Date().toISOString(),
          priority: 'normal',
          host: 'moneat-prod-01',
          tags: {event_type: 'delete', object_kind: 'container'},
          alertType: 'info',
          aggregationKey:
            'datadog_container_lifecycle:container:3eca142d7f7f1e3f287f9cc4449f9a1b9fb68d5afb83b9d20e955ffea8f2e11e',
          sourceTypeName: 'datadog_container_lifecycle',
          deviceName: '',
        },
      ],
    })
  })

  it('keeps expanded event metadata out of the alert column', async () => {
    const user = userEvent.setup()
    renderWithQueryClient(<EventStream />)

    await user.click(await screen.findByText(/Datadog container lifecycle delete/))

    const aggregationValue = screen.getByText(/datadog_container_lifecycle:container/)
    expect(aggregationValue).toHaveClass('break-all')
    const alertBadge = screen.getAllByText('info').find((element) => element.closest('td') !== null)

    expect(alertBadge?.closest('td')).toHaveClass('w-[140px]')
  })
})
