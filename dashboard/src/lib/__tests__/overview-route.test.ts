import {describe, expect, it} from 'vitest'
import {
  APP_OVERVIEW_SEARCH,
  APP_OVERVIEW_VIEW,
  isAppOverviewSearch,
  isPublicLandingRoute,
  normalizeAppOverviewSearch,
} from '@/lib/overview-route'

describe('overview-route', () => {
  it('normalizes the explicit overview search', () => {
    expect(normalizeAppOverviewSearch({view: APP_OVERVIEW_VIEW})).toEqual(APP_OVERVIEW_SEARCH)
  })

  it('drops unknown search values', () => {
    expect(normalizeAppOverviewSearch({view: 'landing'})).toEqual({})
    expect(normalizeAppOverviewSearch({})).toEqual({})
  })

  it('detects explicit overview search objects', () => {
    expect(isAppOverviewSearch(APP_OVERVIEW_SEARCH)).toBe(true)
    expect(isAppOverviewSearch({view: 'landing'})).toBe(false)
    expect(isAppOverviewSearch(null)).toBe(false)
  })

  it('detects plain root as the public landing route', () => {
    expect(isPublicLandingRoute('/', {})).toBe(true)
    expect(isPublicLandingRoute('/', {view: 'landing'})).toBe(true)
    expect(isPublicLandingRoute('//', {})).toBe(true)
  })

  it('keeps the explicit app overview route separate from landing', () => {
    expect(isPublicLandingRoute('/', APP_OVERVIEW_SEARCH)).toBe(false)
    expect(isPublicLandingRoute('/issues', {})).toBe(false)
  })
})
