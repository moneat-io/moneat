// Moneat - observability platform
// Copyright (C) 2026 Moneat
//
// This program is free software: you can redistribute it and/or modify
// it under the terms of the GNU Affero General Public License as published by
// the Free Software Foundation, either version 3 of the License, or
// (at your option) any later version.
//
// This program is distributed in the hope that it will be useful,
// but WITHOUT ANY WARRANTY; without even the implied warranty of
// MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the
// GNU Affero General Public License for more details.
//
// You should have received a copy of the GNU Affero General Public License
// along with this program. If not, see <https://www.gnu.org/licenses/>.

import { describe, it, expect, beforeEach, vi } from 'vitest'

const TEST_CONFIG = {
  domain: 'test.example.com',
  apiHost: 'https://analytics.example.com',
  key: 'test-key-123',
}

let sendBeaconMock: ReturnType<typeof vi.fn>
let fetchMock: ReturnType<typeof vi.fn>
let origPushState: typeof history.pushState

// ──── Helpers ────

function loadModule() {
  return import('../analytics')
}

// ──── Setup ────

beforeEach(() => {
  vi.resetModules()

  sendBeaconMock = vi.fn().mockReturnValue(true)
  fetchMock = vi.fn().mockResolvedValue(new Response())

  Object.defineProperty(navigator, 'sendBeacon', {
    value: sendBeaconMock,
    writable: true,
    configurable: true,
  })

  vi.stubGlobal('fetch', fetchMock)

  Object.defineProperty(navigator, 'webdriver', {
    value: false,
    writable: true,
    configurable: true,
  })

  Object.defineProperty(navigator, 'doNotTrack', {
    value: '0',
    writable: true,
    configurable: true,
  })

  Object.defineProperty(document, 'visibilityState', {
    value: 'visible',
    writable: true,
    configurable: true,
  })

  origPushState = history.pushState
})

// ──── initAnalytics ────

describe('initAnalytics', () => {
  it('sends a pageview on init via sendBeacon', async () => {
    const { initAnalytics } = await loadModule()
    initAnalytics(TEST_CONFIG)

    expect(sendBeaconMock).toHaveBeenCalledTimes(1)
    const [url, body] = sendBeaconMock.mock.calls[0]
    expect(url).toContain(TEST_CONFIG.apiHost)
    expect(url).toContain(TEST_CONFIG.domain)
    expect(url).toContain(TEST_CONFIG.key)

    const payload = JSON.parse(body as string)
    expect(payload.n).toBe('pageview')
    expect(payload.d).toBe(TEST_CONFIG.domain)
  })

  it('falls back to fetch when sendBeacon fails', async () => {
    sendBeaconMock.mockReturnValue(false)

    const { initAnalytics } = await loadModule()
    initAnalytics(TEST_CONFIG)

    expect(fetchMock).toHaveBeenCalledTimes(1)
    const [url, init] = fetchMock.mock.calls[0]
    expect(url).toContain(TEST_CONFIG.apiHost)
    expect(init.method).toBe('POST')
    expect(init.keepalive).toBe(true)
  })

  it('defers pageview when document is in prerender state', async () => {
    Object.defineProperty(document, 'visibilityState', {
      value: 'prerender',
      writable: true,
      configurable: true,
    })

    const { initAnalytics } = await loadModule()
    initAnalytics(TEST_CONFIG)

    expect(sendBeaconMock).not.toHaveBeenCalled()

    Object.defineProperty(document, 'visibilityState', {
      value: 'visible',
      writable: true,
      configurable: true,
    })
    document.dispatchEvent(new Event('visibilitychange'))

    expect(sendBeaconMock).toHaveBeenCalledTimes(1)
  })
})

// ──── trackEvent ────

describe('trackEvent', () => {
  it('sends a custom event with correct payload', async () => {
    const { initAnalytics, trackEvent } = await loadModule()
    initAnalytics(TEST_CONFIG)

    sendBeaconMock.mockClear()
    trackEvent('button_click', { label: 'signup' })

    expect(sendBeaconMock).toHaveBeenCalledTimes(1)
    const payload = JSON.parse(sendBeaconMock.mock.calls[0][1] as string)
    expect(payload.n).toBe('button_click')
    expect(payload.p).toEqual({ label: 'signup' })
    expect(payload.d).toBe(TEST_CONFIG.domain)
  })

  it('does not send events before initAnalytics is called', async () => {
    const { trackEvent } = await loadModule()
    trackEvent('test_event')

    expect(sendBeaconMock).not.toHaveBeenCalled()
    expect(fetchMock).not.toHaveBeenCalled()
  })
})

// ──── Bot detection ────

describe('bot detection', () => {
  it('suppresses events when navigator.webdriver is true', async () => {
    Object.defineProperty(navigator, 'webdriver', {
      value: true,
      writable: true,
      configurable: true,
    })

    const { initAnalytics, trackEvent } = await loadModule()
    initAnalytics(TEST_CONFIG)
    trackEvent('test')

    expect(sendBeaconMock).not.toHaveBeenCalled()
    expect(fetchMock).not.toHaveBeenCalled()
  })
})

// ──── DNT detection ────

describe('DNT detection', () => {
  it('suppresses events when doNotTrack is 1', async () => {
    Object.defineProperty(navigator, 'doNotTrack', {
      value: '1',
      writable: true,
      configurable: true,
    })

    const { initAnalytics, trackEvent } = await loadModule()
    initAnalytics(TEST_CONFIG)
    trackEvent('test')

    expect(sendBeaconMock).not.toHaveBeenCalled()
    expect(fetchMock).not.toHaveBeenCalled()
  })
})

// ──── SPA navigation ────

describe('SPA navigation tracking', () => {
  it('tracks a pageview on history.pushState', async () => {
    const { initAnalytics } = await loadModule()
    initAnalytics(TEST_CONFIG)

    sendBeaconMock.mockClear()
    history.pushState({}, '', '/new-page')

    expect(sendBeaconMock).toHaveBeenCalled()
    const lastCall = sendBeaconMock.mock.calls[sendBeaconMock.mock.calls.length - 1]
    const payload = JSON.parse(lastCall[1] as string)
    expect(payload.n).toBe('pageview')

    // Restore original pushState so other tests aren't affected
    history.pushState = origPushState
  })
})
