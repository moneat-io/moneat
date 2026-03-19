import {describe, it, expect, vi, beforeEach} from 'vitest'
import {resolveProxyUrl, initDatadog} from '../datadog'

vi.mock('@datadog/browser-rum', () => ({
  datadogRum: {init: vi.fn()},
}))

vi.mock('@datadog/browser-logs', () => ({
  datadogLogs: {init: vi.fn()},
}))

describe('resolveProxyUrl', () => {
  it('uses explicit proxyUrl when provided', () => {
    expect(resolveProxyUrl('https://custom.host/dd', 'https://backend.io')).toBe('https://custom.host/dd/dd')
  })

  it('falls back to backendUrl + /dd when proxyUrl is empty', () => {
    expect(resolveProxyUrl('', 'https://backend.io')).toBe('https://backend.io/dd')
    expect(resolveProxyUrl(undefined, 'https://backend.io')).toBe('https://backend.io/dd')
  })

  it('falls back to production default when both are empty', () => {
    expect(resolveProxyUrl(undefined, undefined)).toBe('https://api.moneat.io/dd')
  })
})

describe('initDatadog', () => {
  beforeEach(() => {
    vi.clearAllMocks()
  })

  it('initializes RUM and Logs with proxy pointing to Moneat', async () => {
    const {datadogRum} = await import('@datadog/browser-rum')
    const {datadogLogs} = await import('@datadog/browser-logs')

    initDatadog({
      applicationId: 'app-123',
      clientToken: 'tok-456',
      backendUrl: 'https://api.moneat.io',
    })

    expect(datadogRum.init).toHaveBeenCalledOnce()
    expect(datadogLogs.init).toHaveBeenCalledOnce()

    const rumCall = vi.mocked(datadogRum.init).mock.calls[0][0]
    expect(rumCall.applicationId).toBe('app-123')
    expect(rumCall.clientToken).toBe('tok-456')
    expect(rumCall.service).toBe('moneat-dashboard')
    expect(rumCall.env).toBe('production')

    // Verify proxy function routes to Moneat
    const proxyFn = rumCall.proxy as ({path, parameters}: {path: string; parameters: string}) => string
    expect(proxyFn({path: '/api/v2/rum', parameters: 'ddsource=browser'}))
      .toBe('https://api.moneat.io/dd/api/v2/rum?ddsource=browser')
  })

  it('uses custom service and env when provided', async () => {
    const {datadogRum} = await import('@datadog/browser-rum')

    initDatadog({
      applicationId: 'app-123',
      clientToken: 'tok-456',
      service: 'custom-svc',
      env: 'staging',
    })

    const rumCall = vi.mocked(datadogRum.init).mock.calls[0][0]
    expect(rumCall.service).toBe('custom-svc')
    expect(rumCall.env).toBe('staging')
  })

  it('skips init when applicationId is empty', async () => {
    const {datadogRum} = await import('@datadog/browser-rum')
    const {datadogLogs} = await import('@datadog/browser-logs')

    initDatadog({
      applicationId: '',
      clientToken: 'tok-456',
    })

    expect(datadogRum.init).not.toHaveBeenCalled()
    expect(datadogLogs.init).not.toHaveBeenCalled()
  })

  it('skips init when clientToken is empty', async () => {
    const {datadogRum} = await import('@datadog/browser-rum')
    const {datadogLogs} = await import('@datadog/browser-logs')

    initDatadog({
      applicationId: 'app-123',
      clientToken: '',
    })

    expect(datadogRum.init).not.toHaveBeenCalled()
    expect(datadogLogs.init).not.toHaveBeenCalled()
  })

  it('skips init when values are unreplaced placeholders', async () => {
    const {datadogRum} = await import('@datadog/browser-rum')
    const {datadogLogs} = await import('@datadog/browser-logs')

    initDatadog({
      applicationId: '__MONEAT_DD_APPLICATION_ID__',
      clientToken: '__MONEAT_DD_CLIENT_TOKEN__',
    })

    expect(datadogRum.init).not.toHaveBeenCalled()
    expect(datadogLogs.init).not.toHaveBeenCalled()
  })

  it('uses explicit proxyUrl over backendUrl', async () => {
    const {datadogRum} = await import('@datadog/browser-rum')

    initDatadog({
      applicationId: 'app-123',
      clientToken: 'tok-456',
      proxyUrl: 'https://proxy.example.com/dd',
      backendUrl: 'https://api.moneat.io',
    })

    const rumCall = vi.mocked(datadogRum.init).mock.calls[0][0]
    const proxyFn = rumCall.proxy as ({path, parameters}: {path: string; parameters: string}) => string
    expect(proxyFn({path: '/api/v2/rum', parameters: 'ddsource=browser'}))
      .toBe('https://proxy.example.com/dd/dd/api/v2/rum?ddsource=browser')
  })
})
