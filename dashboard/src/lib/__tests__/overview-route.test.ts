import {describe, expect, it} from 'vitest'
import {
  APP_OVERVIEW_SEARCH,
  APP_OVERVIEW_VIEW,
  isAppOverviewSearch,
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
})
