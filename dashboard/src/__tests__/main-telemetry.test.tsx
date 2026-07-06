import {afterEach, beforeEach, describe, expect, it, vi} from 'vitest'

const {mocks} = vi.hoisted(() => {
  const createRouter = vi.fn((options: unknown) => ({options}))
  const render = vi.fn()
  const createRoot = vi.fn(() => ({render}))
  const sentryTracing = {name: 'tracing'}
  const sentryReplay = {name: 'replay'}

  return {
    mocks: {
      createRouter,
      render,
      createRoot,
      initAnalytics: vi.fn(),
      initDatadog: vi.fn(),
      sentryInit: vi.fn(),
      sentryBrowserTracingIntegration: vi.fn(() => sentryTracing),
      sentryReplayIntegration: vi.fn(() => sentryReplay),
      sentryTracing,
      sentryReplay,
    },
  }
})

vi.mock('@tanstack/react-router', () => ({
  createRouter: mocks.createRouter,
  RouterProvider: () => null,
}))

vi.mock('react-dom/client', () => ({
  default: {createRoot: mocks.createRoot},
  createRoot: mocks.createRoot,
}))

vi.mock('@sentry/react', () => ({
  init: mocks.sentryInit,
  browserTracingIntegration: mocks.sentryBrowserTracingIntegration,
  replayIntegration: mocks.sentryReplayIntegration,
}))

vi.mock('../lib/analytics', () => ({
  initAnalytics: mocks.initAnalytics,
}))

vi.mock('../lib/datadog', () => ({
  initDatadog: mocks.initDatadog,
}))

vi.mock('../routeTree.gen', () => ({
  routeTree: {},
}))

async function importMainEntrypoint() {
  document.body.innerHTML = '<div id="root"></div>'
  await import('../main')
}

describe('main telemetry setup', () => {
  beforeEach(() => {
    vi.resetModules()
    vi.clearAllMocks()
  })

  afterEach(() => {
    vi.unstubAllEnvs()
    document.body.innerHTML = ''
  })

  it('passes release version to Sentry replay and Datadog logs', async () => {
    vi.stubEnv('VITE_MONEAT_VERSION', ' v1.2.3 ')
    vi.stubEnv('VITE_SENTRY_DSN', 'https://public@example.com/1')
    vi.stubEnv('VITE_SENTRY_ENVIRONMENT', 'preview')
    vi.stubEnv('VITE_SENTRY_TRACES_SAMPLE_RATE', 'invalid')
    vi.stubEnv('VITE_SENTRY_REPLAYS_SESSION_SAMPLE_RATE', '0.25')
    vi.stubEnv('VITE_SENTRY_REPLAYS_ON_ERROR_SAMPLE_RATE', '0.5')
    vi.stubEnv('VITE_ANALYTICS_KEY', 'analytics-key')
    vi.stubEnv('VITE_BACKEND_URL', 'https://api.preview.moneat.io')
    vi.stubEnv('VITE_DD_APPLICATION_ID', 'dd-app')
    vi.stubEnv('VITE_DD_CLIENT_TOKEN', 'dd-token')

    await importMainEntrypoint()

    expect(mocks.sentryReplayIntegration).toHaveBeenCalledWith({
      maskAllText: false,
      blockAllMedia: false,
    })
    expect(mocks.sentryInit).toHaveBeenCalledWith(expect.objectContaining({
      dsn: 'https://public@example.com/1',
      environment: 'preview',
      release: 'v1.2.3',
      tracesSampleRate: 0.1,
      replaysSessionSampleRate: 0.25,
      replaysOnErrorSampleRate: 0.5,
      integrations: [mocks.sentryTracing, mocks.sentryReplay],
    }))
    expect(mocks.initAnalytics).toHaveBeenCalledWith(expect.objectContaining({
      apiHost: 'https://api.preview.moneat.io',
      key: 'analytics-key',
    }))
    expect(mocks.initDatadog).toHaveBeenCalledWith(expect.objectContaining({
      applicationId: 'dd-app',
      clientToken: 'dd-token',
      backendUrl: 'https://api.preview.moneat.io',
      version: 'v1.2.3',
    }))
    expect(mocks.createRoot).toHaveBeenCalledWith(document.getElementById('root'))
    expect(mocks.render).toHaveBeenCalledTimes(1)
  })

  it('ignores empty and placeholder telemetry values', async () => {
    vi.stubEnv('VITE_SENTRY_DSN', '__MONEAT_SENTRY_DSN__')
    vi.stubEnv('VITE_ANALYTICS_KEY', '   ')
    vi.stubEnv('VITE_DD_APPLICATION_ID', 'dd-app')
    vi.stubEnv('VITE_DD_CLIENT_TOKEN', '__MONEAT_DD_CLIENT_TOKEN__')

    await importMainEntrypoint()

    expect(mocks.sentryInit).not.toHaveBeenCalled()
    expect(mocks.initAnalytics).not.toHaveBeenCalled()
    expect(mocks.initDatadog).not.toHaveBeenCalled()
    expect(mocks.render).toHaveBeenCalledTimes(1)
  })

  it('enables browser logging when only the Datadog-compatible client token is configured', async () => {
    vi.stubEnv('VITE_BACKEND_URL', 'https://api.moneat.io')
    vi.stubEnv('VITE_DD_CLIENT_TOKEN', 'magt_browser_logs')

    await importMainEntrypoint()

    expect(mocks.initDatadog).toHaveBeenCalledWith(expect.objectContaining({
      applicationId: undefined,
      backendUrl: 'https://api.moneat.io',
      clientToken: 'magt_browser_logs',
    }))
    expect(mocks.render).toHaveBeenCalledTimes(1)
  })
})
