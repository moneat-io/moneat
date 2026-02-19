import { describe, it, expect } from 'vitest'
import {
  isSidebarItemVisible,
  getAllSidebarItemKeys,
  CONFIGURABLE_SIDEBAR_ITEMS,
  ALWAYS_VISIBLE_ITEMS,
} from '@/lib/sidebar-config'

describe('sidebar-config', () => {
  describe('CONFIGURABLE_SIDEBAR_ITEMS', () => {
    it('contains expected items', () => {
      const keys = CONFIGURABLE_SIDEBAR_ITEMS.map(i => i.key)
      expect(keys).toContain('issues')
      expect(keys).toContain('logs')
      expect(keys).toContain('monitoring')
      expect(keys).toContain('replays')
    })

    it('each item has key, label, and icon', () => {
      for (const item of CONFIGURABLE_SIDEBAR_ITEMS) {
        expect(item.key).toBeTruthy()
        expect(item.label).toBeTruthy()
        expect(item.icon).toBeDefined()
      }
    })

    it('does not include always-visible items', () => {
      const keys = CONFIGURABLE_SIDEBAR_ITEMS.map(i => i.key)
      for (const alwaysKey of ALWAYS_VISIBLE_ITEMS) {
        expect(keys).not.toContain(alwaysKey)
      }
    })
  })

  describe('ALWAYS_VISIBLE_ITEMS', () => {
    it('includes dashboard, admin, and settings', () => {
      expect(ALWAYS_VISIBLE_ITEMS).toContain('dashboard')
      expect(ALWAYS_VISIBLE_ITEMS).toContain('admin')
      expect(ALWAYS_VISIBLE_ITEMS).toContain('settings')
    })
  })

  describe('isSidebarItemVisible', () => {
    it('always shows dashboard regardless of hidden items', () => {
      expect(isSidebarItemVisible('dashboard', ['dashboard'])).toBe(true)
    })

    it('always shows admin regardless of hidden items', () => {
      expect(isSidebarItemVisible('admin', ['admin'])).toBe(true)
    })

    it('always shows settings regardless of hidden items', () => {
      expect(isSidebarItemVisible('settings', ['settings'])).toBe(true)
    })

    it('shows configurable item when not hidden', () => {
      expect(isSidebarItemVisible('issues', [])).toBe(true)
    })

    it('hides configurable item when in hidden list', () => {
      expect(isSidebarItemVisible('issues', ['issues'])).toBe(false)
    })

    it('shows item not in hidden list among other hidden items', () => {
      expect(isSidebarItemVisible('logs', ['issues', 'replays'])).toBe(true)
    })

    it('hides multiple items independently', () => {
      const hidden = ['issues', 'logs', 'replays']
      expect(isSidebarItemVisible('issues', hidden)).toBe(false)
      expect(isSidebarItemVisible('logs', hidden)).toBe(false)
      expect(isSidebarItemVisible('monitoring', hidden)).toBe(true)
    })
  })

  describe('getAllSidebarItemKeys', () => {
    it('returns all configurable keys', () => {
      const keys = getAllSidebarItemKeys()
      expect(keys.length).toBe(CONFIGURABLE_SIDEBAR_ITEMS.length)
      for (const item of CONFIGURABLE_SIDEBAR_ITEMS) {
        expect(keys).toContain(item.key)
      }
    })

    it('does not include always-visible items', () => {
      const keys = getAllSidebarItemKeys()
      expect(keys).not.toContain('dashboard')
      expect(keys).not.toContain('admin')
      expect(keys).not.toContain('settings')
    })
  })
})
