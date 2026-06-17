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

function eventResponse(overrides: Record<string, unknown> = {}) {
  return {
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
        ...overrides,
      },
    ],
  }
}

describe('EventStream', () => {
  beforeEach(() => {
    vi.clearAllMocks()
    mockApi.isAuthenticated.mockReturnValue(true)
    mockApi.getEvents.mockResolvedValue(eventResponse())
  })

  it('shows a loading state while events are pending', () => {
    mockApi.getEvents.mockReturnValue(new Promise(() => undefined))

    renderWithQueryClient(<EventStream />)

    expect(screen.getByText('Loading events...')).toBeInTheDocument()
  })

  it('shows an empty state when no events are returned', async () => {
    mockApi.getEvents.mockResolvedValue({totalCount: 0, events: []})

    renderWithQueryClient(<EventStream />)

    expect(await screen.findByText('No events found')).toBeInTheDocument()
    expect(screen.getByText('Events will appear when an agent sends event data.')).toBeInTheDocument()
  })

  it('shows an empty filtered state when search removes every event', async () => {
    const user = userEvent.setup()
    renderWithQueryClient(<EventStream />)

    await screen.findByText(/Datadog container lifecycle delete/)
    await user.type(screen.getByPlaceholderText('Search by host, title, or source...'), 'missing-host')

    expect(screen.getByText('No events match your filters')).toBeInTheDocument()
  })

  it('renders events without expandable details', async () => {
    mockApi.getEvents.mockResolvedValue(
      eventResponse({
        text: '',
        tags: {},
        aggregationKey: '',
        deviceName: '',
      }),
    )

    renderWithQueryClient(<EventStream />)

    expect(await screen.findByText(/Datadog container lifecycle delete/)).toBeInTheDocument()
    expect(screen.queryByText('agg:')).not.toBeInTheDocument()
  })

  it('renders fallback variants and applies alert filters', async () => {
    const user = userEvent.setup()
    mockApi.getEvents.mockResolvedValue({
      totalCount: 2,
      events: [
        eventResponse({
          eventId: 'evt-low',
          title: 'Low priority warning',
          priority: 'low',
          alertType: 'warning',
          text: '',
          tags: {},
          aggregationKey: '',
        }).events[0],
        eventResponse({
          eventId: 'evt-custom',
          title: 'Custom alert event',
          priority: '',
          alertType: 'notice',
          text: '',
          tags: {},
          aggregationKey: '',
        }).events[0],
      ],
    })

    renderWithQueryClient(<EventStream />)

    expect(await screen.findByText('Low priority warning')).toBeInTheDocument()
    expect(screen.getByText('Low')).toBeInTheDocument()
    expect(screen.getByText('Custom alert event')).toBeInTheDocument()
    expect(screen.getByText('Normal')).toBeInTheDocument()

    await user.click(screen.getByRole('button', {name: /warning/i}))

    expect(mockApi.getEvents).toHaveBeenLastCalledWith(
      expect.objectContaining({alertType: 'warning'}),
    )
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
