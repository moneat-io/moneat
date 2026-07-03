import {fireEvent, render, screen, waitFor} from '@testing-library/react'
import type {ComponentType, ReactNode} from 'react'
import {beforeEach, describe, expect, it, vi} from 'vitest'
import {storePendingAuthRedirect} from '@/lib/auth-redirect'

const {mockApi, mockNavigate, mockTrackEvent} = vi.hoisted(() => ({
  mockApi: {
    isAuthenticated: vi.fn(),
    login: vi.fn(),
    logout: vi.fn(),
    initSso: vi.fn(),
  },
  mockNavigate: vi.fn(),
  mockTrackEvent: vi.fn(),
}))

vi.mock('@tanstack/react-router', () => ({
  createFileRoute: () => (options: Record<string, unknown>) => ({options}),
  Link: ({children}: {children: ReactNode}) => <>{children}</>,
  redirect: (options: Record<string, unknown>) => ({...options, __redirect: true}),
  useNavigate: () => mockNavigate,
}))

vi.mock('@/lib/api', () => ({
  api: mockApi,
}))

vi.mock('@/lib/analytics', () => ({
  trackEvent: mockTrackEvent,
}))

vi.mock('@/components/auth/AuthShell', () => ({
  AuthAlert: ({children}: {children: ReactNode}) => <div>{children}</div>,
  AuthDivider: () => <div />,
  AuthField: ({children, label}: {children: ReactNode; label: string}) => (
    <label>
      {label}
      {children}
    </label>
  ),
  AuthShell: ({children}: {children: ReactNode}) => <div>{children}</div>,
}))

vi.mock('@/components/ui/button', () => ({
  Button: ({children, ...props}: React.ButtonHTMLAttributes<HTMLButtonElement>) => (
    <button {...props}>{children}</button>
  ),
}))

vi.mock('@/components/ui/input', () => ({
  Input: (props: React.InputHTMLAttributes<HTMLInputElement>) => <input {...props} />,
}))

vi.mock('@/components/landing/Landing', () => ({
  GRADIENT_BG: '',
  GRADIENT_TEXT: '',
}))

import {Route} from '../login'

type LoginBeforeLoadContext = Parameters<NonNullable<typeof Route.options.beforeLoad>>[0]

function beforeLoadContext(search: Record<string, unknown>): LoginBeforeLoadContext {
  return {search} as unknown as LoginBeforeLoadContext
}

async function submitEmailLogin() {
  const LoginPage = Route.options.component as ComponentType

  render(<LoginPage />)

  fireEvent.change(screen.getByLabelText('Email'), {target: {value: 'test@example.com'}})
  fireEvent.change(screen.getByPlaceholderText('Enter your password'), {target: {value: 'password'}})
  fireEvent.click(screen.getByRole('button', {name: 'Sign in'}))

  await waitFor(() => {
    expect(mockApi.login).toHaveBeenCalledWith('test@example.com', 'password')
  })
}

describe('login route', () => {
  beforeEach(() => {
    vi.clearAllMocks()
    mockApi.isAuthenticated.mockReturnValue(false)
    mockApi.login.mockResolvedValue({token: 'token-1'})
    window.history.replaceState(null, '', '/login')
  })

  it('navigates to a pending private route after email login', async () => {
    storePendingAuthRedirect({
      pathname: '/replays/replay-1',
      search: '?tab=errors',
      hash: '#event',
    })

    await submitEmailLogin()

    await waitFor(() => {
      expect(mockNavigate).toHaveBeenCalledWith({to: '/replays/replay-1?tab=errors#event'})
    })
  })

  it('uses an internal redirect query after email login', async () => {
    window.history.replaceState(null, '', '/login?redirect=/issues?status=unresolved')

    await submitEmailLogin()

    expect(mockNavigate).toHaveBeenCalledWith({to: '/issues?status=unresolved'})
  })

  it('routes invite tokens through the accept-invite page after email login', async () => {
    window.history.replaceState(null, '', '/login?inviteToken=invite%201')

    await submitEmailLogin()

    expect(mockNavigate).toHaveBeenCalledWith({to: '/accept-invite?token=invite+1'})
  })

  it('falls back to the app overview for missing or external redirects', async () => {
    window.history.replaceState(null, '', '/login?redirect=https%3A%2F%2Fevil.example%2Fissues')

    await submitEmailLogin()

    expect(mockNavigate).toHaveBeenCalledWith({to: '/?view=overview'})
  })

  it('shows an invalid credentials error when email login fails', async () => {
    mockApi.login.mockRejectedValue(new Error('UNAUTHORIZED'))
    const LoginPage = Route.options.component as ComponentType

    render(<LoginPage />)

    fireEvent.change(screen.getByLabelText('Email'), {target: {value: 'test@example.com'}})
    fireEvent.change(screen.getByPlaceholderText('Enter your password'), {target: {value: 'wrong'}})
    fireEvent.click(screen.getByRole('button', {name: 'Sign in'}))

    expect(await screen.findByText('Invalid email or password. Please try again.')).toBeTruthy()
  })

  it('redirects authenticated users with an internal redirect before loading the page', () => {
    mockApi.isAuthenticated.mockReturnValue(true)

    expect(() => {
      Route.options.beforeLoad?.(beforeLoadContext({redirect: '/replays/replay-1'}))
    }).toThrow(expect.objectContaining({to: '/replays/replay-1'}))
  })

  it('logs out authenticated mobile redirect sessions before loading the page', () => {
    mockApi.isAuthenticated.mockReturnValue(true)

    Route.options.beforeLoad?.(beforeLoadContext({redirect_uri: 'bandapella://auth'}))

    expect(mockApi.logout).toHaveBeenCalled()
  })

  it('redirects authenticated users without special params to the app overview', () => {
    mockApi.isAuthenticated.mockReturnValue(true)

    expect(() => {
      Route.options.beforeLoad?.(beforeLoadContext({}))
    }).toThrow(expect.objectContaining({to: '/', search: {view: 'overview'}}))
  })

  it('starts SSO login from a work email', async () => {
    mockApi.initSso.mockResolvedValue({redirectUrl: 'https://sso.example.test/start'})
    const LoginPage = Route.options.component as ComponentType

    render(<LoginPage />)

    fireEvent.click(screen.getByRole('button', {name: 'Sign in with SSO instead'}))
    fireEvent.change(screen.getByLabelText('Work email'), {target: {value: 'sso@example.com'}})
    fireEvent.click(screen.getByRole('button', {name: 'Continue with SSO'}))

    await waitFor(() => {
      expect(mockApi.initSso).toHaveBeenCalledWith('sso@example.com')
    })
  })

  it('rejects non-HTTPS SSO redirect URLs', async () => {
    mockApi.initSso.mockResolvedValue({redirectUrl: 'javascript:alert(1)'})
    const LoginPage = Route.options.component as ComponentType

    render(<LoginPage />)

    fireEvent.click(screen.getByRole('button', {name: 'Sign in with SSO instead'}))
    fireEvent.change(screen.getByLabelText('Work email'), {target: {value: 'sso@example.com'}})
    fireEvent.click(screen.getByRole('button', {name: 'Continue with SSO'}))

    expect(await screen.findByText('Invalid SSO redirect URL')).toBeTruthy()
  })


  it('shows a network error when SSO initialization cannot reach the backend', async () => {
    mockApi.initSso.mockRejectedValue(new Error('NETWORK_ERROR'))
    const LoginPage = Route.options.component as ComponentType

    render(<LoginPage />)

    fireEvent.click(screen.getByRole('button', {name: 'Sign in with SSO instead'}))
    fireEvent.change(screen.getByLabelText('Work email'), {target: {value: 'sso@example.com'}})
    fireEvent.click(screen.getByRole('button', {name: 'Continue with SSO'}))

    expect(await screen.findByText('Unable to connect to the server. Please check your connection and try again.')).toBeTruthy()
  })

  it('shows the backend SSO error message when SSO initialization is rejected', async () => {
    mockApi.initSso.mockRejectedValue(new Error('SSO is not configured'))
    const LoginPage = Route.options.component as ComponentType

    render(<LoginPage />)

    fireEvent.click(screen.getByRole('button', {name: 'Sign in with SSO instead'}))
    fireEvent.change(screen.getByLabelText('Work email'), {target: {value: 'sso@example.com'}})
    fireEvent.click(screen.getByRole('button', {name: 'Continue with SSO'}))

    expect(await screen.findByText('SSO is not configured')).toBeTruthy()
  })

  it('can cancel the SSO form after entering an email', () => {
    const LoginPage = Route.options.component as ComponentType

    render(<LoginPage />)

    fireEvent.click(screen.getByRole('button', {name: 'Sign in with SSO instead'}))
    fireEvent.change(screen.getByLabelText('Work email'), {target: {value: 'sso@example.com'}})
    fireEvent.click(screen.getByRole('button', {name: 'Cancel'}))

    expect(screen.queryByLabelText('Work email')).toBeNull()
  })

  it('starts GitHub OAuth through the backend', () => {
    const LoginPage = Route.options.component as ComponentType

    render(<LoginPage />)

    fireEvent.click(screen.getByRole('button', {name: 'GitHub'}))

    expect(screen.getByRole('button', {name: 'GitHub'})).toBeTruthy()
  })
})
