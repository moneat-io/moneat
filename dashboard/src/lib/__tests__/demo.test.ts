import { describe, it, expect, beforeEach } from 'vitest'
import {
  setDemoEpoch,
  syncDemoEpochFromUser,
  isDemo,
  getNow,
  getNowDate,
  getDemoEpochMs,
} from '@/lib/demo'

describe('demo', () => {
  beforeEach(() => {
    setDemoEpoch(null)
    sessionStorage.clear()
  })

  describe('setDemoEpoch', () => {
    it('stores epoch in sessionStorage', () => {
      setDemoEpoch(1700000000000)
      expect(sessionStorage.getItem('demoEpochMs')).toBe('1700000000000')
    })

    it('removes from sessionStorage when set to null', () => {
      setDemoEpoch(1700000000000)
      setDemoEpoch(null)
      expect(sessionStorage.getItem('demoEpochMs')).toBeNull()
    })
  })

  describe('isDemo', () => {
    it('returns false when not in demo mode', () => {
      expect(isDemo()).toBe(false)
    })

    it('returns true after setting demo epoch', () => {
      setDemoEpoch(1700000000000)
      expect(isDemo()).toBe(true)
    })

    it('recovers from sessionStorage', () => {
      sessionStorage.setItem('demoEpochMs', '1700000000000')
      // Module state is null but sessionStorage has value
      expect(isDemo()).toBe(true)
    })
  })

  describe('syncDemoEpochFromUser', () => {
    it('clears demo mode when the user is not a demo user', () => {
      setDemoEpoch(1700000000000)
      syncDemoEpochFromUser({demoEpochMs: null})
      expect(isDemo()).toBe(false)
      expect(sessionStorage.getItem('demoEpochMs')).toBeNull()
    })

    it('sets demo mode when the user is a demo user', () => {
      syncDemoEpochFromUser({demoEpochMs: 1700000000000})
      expect(isDemo()).toBe(true)
      expect(getDemoEpochMs()).toBe(1700000000000)
    })
  })

  describe('getNow', () => {
    it('returns demo epoch when set', () => {
      setDemoEpoch(1700000000000)
      expect(getNow()).toBe(1700000000000)
    })

    it('returns current time when not in demo mode', () => {
      const before = Date.now()
      const now = getNow()
      const after = Date.now()
      expect(now).toBeGreaterThanOrEqual(before)
      expect(now).toBeLessThanOrEqual(after)
    })

    it('recovers from sessionStorage', () => {
      sessionStorage.setItem('demoEpochMs', '1700000000000')
      expect(getNow()).toBe(1700000000000)
    })
  })

  describe('getNowDate', () => {
    it('returns Date at demo epoch', () => {
      setDemoEpoch(1700000000000)
      const date = getNowDate()
      expect(date.getTime()).toBe(1700000000000)
    })

    it('returns current Date when not in demo mode', () => {
      const before = Date.now()
      const date = getNowDate()
      expect(date.getTime()).toBeGreaterThanOrEqual(before)
    })
  })

  describe('getDemoEpochMs', () => {
    it('returns null when not set', () => {
      expect(getDemoEpochMs()).toBeNull()
    })

    it('returns epoch when set', () => {
      setDemoEpoch(1700000000000)
      expect(getDemoEpochMs()).toBe(1700000000000)
    })
  })
})
