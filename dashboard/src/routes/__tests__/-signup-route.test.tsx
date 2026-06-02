import {beforeEach, describe, expect, it, vi} from 'vitest'
import {APP_OVERVIEW_SEARCH} from '@/lib/overview-route'

const {mockApi, mockIsDemo} = vi.hoisted(() => ({
  mockApi: {
    isAuthenticated: vi.fn(),
    logout: vi.fn(),
  },
  mockIsDemo: vi.fn(),
}))

vi.mock('@/lib/api', () => ({
  api: mockApi,
}))

vi.mock('@/lib/demo', () => ({
  isDemo: mockIsDemo,
}))

vi.mock('@tanstack/react-router', () => ({
  createFileRoute: () => (options: Record<string, unknown>) => ({
    ...options,
    options,
  }),
  Link: ({children}: {children: unknown}) => children,
  redirect: (opts: Record<string, unknown>) => ({...opts, __redirect: true}),
}))

import {Route as SignupRoute} from '../signup'

const beforeLoad = (SignupRoute as unknown as {beforeLoad: () => Promise<void>}).beforeLoad

describe('signup route guard', () => {
  beforeEach(() => {
    vi.clearAllMocks()
    mockApi.isAuthenticated.mockReturnValue(false)
    mockApi.logout.mockResolvedValue(undefined)
    mockIsDemo.mockReturnValue(false)
  })

  it('lets unauthenticated visitors load signup', async () => {
    await expect(beforeLoad()).resolves.toBeUndefined()

    expect(mockApi.logout).not.toHaveBeenCalled()
  })

  it('logs out demo viewers before loading signup', async () => {
    mockApi.isAuthenticated.mockReturnValue(true)
    mockIsDemo.mockReturnValue(true)

    await expect(beforeLoad()).resolves.toBeUndefined()

    expect(mockApi.logout).toHaveBeenCalledTimes(1)
  })

  it('redirects signed-in non-demo users to the app overview', async () => {
    mockApi.isAuthenticated.mockReturnValue(true)

    await expect(beforeLoad()).rejects.toMatchObject({
      __redirect: true,
      to: '/',
      search: APP_OVERVIEW_SEARCH,
    })

    expect(mockApi.logout).not.toHaveBeenCalled()
  })
})
