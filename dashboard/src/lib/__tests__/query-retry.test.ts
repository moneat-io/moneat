import {describe, it, expect} from 'vitest'
import {shouldRetryQuery, MAX_QUERY_RETRIES} from '@/lib/query-retry'

describe('shouldRetryQuery', () => {
  it('does not retry 404 errors', () => {
    expect(shouldRetryQuery(0, {status: 404})).toBe(false)
  })

  it('does not retry any 4xx client error', () => {
    for (const status of [400, 401, 403, 404, 409, 422, 429]) {
      expect(shouldRetryQuery(0, {status})).toBe(false)
    }
  })

  it('retries 5xx server errors up to the limit', () => {
    expect(shouldRetryQuery(0, {status: 500})).toBe(true)
    expect(shouldRetryQuery(MAX_QUERY_RETRIES - 1, {status: 503})).toBe(true)
    expect(shouldRetryQuery(MAX_QUERY_RETRIES, {status: 500})).toBe(false)
  })

  it('retries errors without a status (e.g. network failures) up to the limit', () => {
    const networkError = new Error('NETWORK_ERROR')
    expect(shouldRetryQuery(0, networkError)).toBe(true)
    expect(shouldRetryQuery(MAX_QUERY_RETRIES, networkError)).toBe(false)
  })

  it('handles null and undefined errors', () => {
    expect(shouldRetryQuery(0, null)).toBe(true)
    expect(shouldRetryQuery(0, undefined)).toBe(true)
    expect(shouldRetryQuery(MAX_QUERY_RETRIES, null)).toBe(false)
  })
})
