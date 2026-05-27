import {describe, expect, it} from 'vitest'
import {SOURCE_REVIEW_DATE, competitorPages, getCompetitorPage} from '../competitorComparisonData'

describe('competitor comparison data', () => {
  it('uses root-level alternative URLs as canonical routes', () => {
    expect(competitorPages.map((page) => page.route)).toEqual([
      '/datadog-alternative',
      '/sentry-alternative',
      '/better-stack-alternative',
      '/signoz-alternative',
    ])
  })

  it('keeps the Better Stack comparison current on session replay', () => {
    const betterStack = getCompetitorPage('better-stack')
    const pageText = JSON.stringify(betterStack)

    expect(pageText).toContain('5,000 included session replays')
    expect(pageText).not.toMatch(/no session replays/i)
    expect(pageText).not.toMatch(/lacks session replays/i)
  })

  it('positions Datadog comparison around dashboard import without overclaiming migration parity', () => {
    const datadogText = JSON.stringify(getCompetitorPage('datadog'))

    expect(datadogText).toContain('Datadog dashboard export')
    expect(datadogText).toContain('conversion warnings')
    expect(datadogText).not.toMatch(/full Datadog account migration/i)
  })

  it('uses reviewed source links for every comparison', () => {
    for (const page of competitorPages) {
      expect(page.sources.length).toBeGreaterThanOrEqual(2)
      expect(page.sources[0].label).toBe('Moneat pricing')
      expect(page.sources.some((source) => source.href.startsWith('https://'))).toBe(true)
    }
  })

  it('includes the required decision architecture for each page', () => {
    expect(SOURCE_REVIEW_DATE).toBe('May 26, 2026')

    for (const page of competitorPages) {
      expect(page.metaDescription).toContain('2026')
      expect(page.shortVersionRows.length).toBeGreaterThanOrEqual(4)
      expect(page.chooseCompetitor.length).toBeGreaterThanOrEqual(3)
      expect(page.chooseMoneat.length).toBeGreaterThanOrEqual(3)
      expect(page.misconceptions.length).toBeGreaterThanOrEqual(3)
      expect(page.migrationSteps.length).toBeGreaterThanOrEqual(3)
    }
  })

  it('does not misstate SigNoz open-source positioning', () => {
    const signozText = JSON.stringify(getCompetitorPage('signoz'))

    expect(signozText).toMatch(/SigNoz also has open-source and self-host options/i)
  })
})
