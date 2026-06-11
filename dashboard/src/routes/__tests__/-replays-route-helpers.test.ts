import {describe, expect, it, vi} from 'vitest'
import {api, type Replay} from '@/lib/api'

const routerMocks = vi.hoisted(() => ({
  navigate: vi.fn(),
  useMatches: vi.fn(),
}))

vi.mock('@tanstack/react-router', () => ({
  createFileRoute: () => (options: Record<string, unknown>) => options,
  Link: ({children}: {children: unknown}) => children,
  Outlet: () => null,
  redirect: (options: Record<string, unknown>) => options,
  useMatches: routerMocks.useMatches,
  useNavigate: () => routerMocks.navigate,
}))

import {Route as ReplaysRoute, replaysHelperTestHooks as helpers} from '../replays'

function replay(overrides: Partial<Replay> = {}): Replay {
  return {
    replayId: 'replay-123456789',
    projectId: 'svc-api',
    startedAt: '2026-06-01T00:00:00.000Z',
    finishedAt: '2026-06-01T00:01:00.000Z',
    durationMs: 60_000,
    urls: ['https://app.example.com/cart'],
    errorCount: 0,
    activity: 42,
    browserName: 'Chrome',
    osName: 'macOS',
    user: {email: 'user@example.com'},
    ...overrides,
  }
}

function elementProps(value: unknown): Record<string, unknown> {
  return (value as {props: Record<string, unknown>}).props
}

describe('replays route helper coverage', () => {
  it('redirects unauthenticated replay list loads', async () => {
    const beforeLoad = (ReplaysRoute as unknown as {
      beforeLoad: (args: {location: {href: string}}) => Promise<void>
    }).beforeLoad
    const isAuthenticated = vi.spyOn(api, 'isAuthenticated')

    isAuthenticated.mockReturnValueOnce(false)
    await expect(beforeLoad({location: {href: '/replays'}})).rejects.toEqual({
      to: '/login',
      search: {redirect: '/replays'},
    })

    isAuthenticated.mockReturnValueOnce(true)
    await expect(beforeLoad({location: {href: '/replays'}})).resolves.toBeUndefined()
    isAuthenticated.mockRestore()
  })

  it('renders the outlet when a replay detail child route is active', () => {
    routerMocks.useMatches.mockReturnValueOnce([{id: '/replays/$replayId'}])

    expect(helpers.ReplaysLayout()).toBeTruthy()
  })

  it('selects replay content empty states for filter edge cases', () => {
    const baseProps = {
      hasReplayScope: true,
      isLoading: false,
      replays: [replay()],
      filteredReplays: [replay()],
      visibleReplays: [replay()],
      timezone: 'UTC',
      onOpenReplay: vi.fn(),
    }

    const noScope = helpers.ReplayContent({...baseProps, hasReplayScope: false})
    expect(elementProps(noScope).title).toBe('No services match filters')

    const noVisibleRows = helpers.ReplayContent({...baseProps, visibleReplays: []})
    expect(elementProps(noVisibleRows).title).toBe('No replays in this view')
  })

  it('opens replay rows with click and keyboard activation', () => {
    const selectedReplay = replay()
    const onOpen = vi.fn()
    const row = helpers.ReplayRow({replay: selectedReplay, timezone: 'UTC', onOpen})
    const props = elementProps(row)
    const event = {key: 'Enter', preventDefault: vi.fn()}
    const onClick = props.onClick as () => void
    const onKeyDown = props.onKeyDown as (event: {key: string; preventDefault: () => void}) => void

    onClick()
    onKeyDown(event)

    expect(event.preventDefault).toHaveBeenCalledOnce()
    expect(onOpen).toHaveBeenCalledWith(selectedReplay)
    expect(onOpen).toHaveBeenCalledTimes(2)
  })
})
