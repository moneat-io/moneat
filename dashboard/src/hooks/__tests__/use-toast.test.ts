import { describe, it, expect } from 'vitest'
import { reducer } from '../use-toast'

type ToastItem = Parameters<typeof reducer>[0]['toasts'][number]

describe('use-toast reducer', () => {
  const emptyState = { toasts: [] }

  describe('ADD_TOAST', () => {
    it('adds a toast to empty state', () => {
      const toast = { id: '1', title: 'Test', open: true } as ToastItem
      const result = reducer(emptyState, { type: 'ADD_TOAST', toast })
      expect(result.toasts).toHaveLength(1)
      expect(result.toasts[0].id).toBe('1')
    })

    it('prepends new toast', () => {
      const state = { toasts: [{ id: '1', title: 'First', open: true } as ToastItem] }
      const toast = { id: '2', title: 'Second', open: true } as ToastItem
      const result = reducer(state, { type: 'ADD_TOAST', toast })
      expect(result.toasts[0].id).toBe('2')
    })

    it('limits toast count to TOAST_LIMIT', () => {
      const state = { toasts: [{ id: '1', title: 'First', open: true } as ToastItem] }
      const toast = { id: '2', title: 'Second', open: true } as ToastItem
      const result = reducer(state, { type: 'ADD_TOAST', toast })
      // TOAST_LIMIT is 1, so only latest toast survives
      expect(result.toasts).toHaveLength(1)
      expect(result.toasts[0].id).toBe('2')
    })
  })

  describe('UPDATE_TOAST', () => {
    it('updates matching toast', () => {
      const state = { toasts: [{ id: '1', title: 'Original', open: true } as ToastItem] }
      const result = reducer(state, { type: 'UPDATE_TOAST', toast: { id: '1', title: 'Updated' } })
      expect(result.toasts[0].title).toBe('Updated')
    })

    it('does not modify non-matching toasts', () => {
      const state = { toasts: [{ id: '1', title: 'Keep', open: true } as ToastItem] }
      const result = reducer(state, { type: 'UPDATE_TOAST', toast: { id: '2', title: 'Other' } })
      expect(result.toasts[0].title).toBe('Keep')
    })
  })

  describe('DISMISS_TOAST', () => {
    it('sets open to false for specific toast', () => {
      const state = { toasts: [{ id: '1', title: 'Test', open: true } as ToastItem] }
      const result = reducer(state, { type: 'DISMISS_TOAST', toastId: '1' })
      expect(result.toasts[0].open).toBe(false)
    })

    it('dismisses all toasts when no toastId', () => {
      const state = { toasts: [{ id: '1', open: true } as ToastItem] }
      const result = reducer(state, { type: 'DISMISS_TOAST' })
      expect(result.toasts.every(t => t.open === false)).toBe(true)
    })
  })

  describe('REMOVE_TOAST', () => {
    it('removes specific toast', () => {
      const state = { toasts: [{ id: '1', title: 'Remove Me', open: true } as ToastItem] }
      const result = reducer(state, { type: 'REMOVE_TOAST', toastId: '1' })
      expect(result.toasts).toHaveLength(0)
    })

    it('removes all toasts when no toastId', () => {
      const state = { toasts: [{ id: '1', open: true } as ToastItem] }
      const result = reducer(state, { type: 'REMOVE_TOAST' })
      expect(result.toasts).toHaveLength(0)
    })

    it('does not remove non-matching toasts', () => {
      const state = { toasts: [{ id: '1', open: true } as ToastItem] }
      const result = reducer(state, { type: 'REMOVE_TOAST', toastId: '99' })
      expect(result.toasts).toHaveLength(1)
    })
  })
})
