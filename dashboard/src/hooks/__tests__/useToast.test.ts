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

import { describe, it, expect, beforeEach } from 'vitest'
import { renderHook, act, waitFor } from '@testing-library/react'
import { reducer, useToast, toast } from '../useToast'

type ToastItem = Parameters<typeof reducer>[0]['toasts'][number]

// ──── Reducer ────

describe('use-toast reducer', () => {
  const emptyState = { toasts: [] }

  describe('ADD_TOAST', () => {
    it('adds a toast to empty state', () => {
      const t = { id: '1', title: 'Test', open: true } as ToastItem
      const result = reducer(emptyState, { type: 'ADD_TOAST', toast: t })
      expect(result.toasts).toHaveLength(1)
      expect(result.toasts[0].id).toBe('1')
    })

    it('prepends new toast', () => {
      const state = { toasts: [{ id: '1', title: 'First', open: true } as ToastItem] }
      const t = { id: '2', title: 'Second', open: true } as ToastItem
      const result = reducer(state, { type: 'ADD_TOAST', toast: t })
      expect(result.toasts[0].id).toBe('2')
    })

    it('limits toast count to TOAST_LIMIT', () => {
      const state = { toasts: [{ id: '1', title: 'First', open: true } as ToastItem] }
      const t = { id: '2', title: 'Second', open: true } as ToastItem
      const result = reducer(state, { type: 'ADD_TOAST', toast: t })
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

// ──── toast() function ────

describe('toast()', () => {
  beforeEach(() => {
    // Clear any toasts from previous tests by dismissing/removing all
    const { result } = renderHook(() => useToast())
    act(() => {
      result.current.toasts.forEach((t) => {
        result.current.dismiss(t.id)
      })
    })
  })

  it('returns an object with id, dismiss, and update', () => {
    let result: ReturnType<typeof toast>
    act(() => {
      result = toast({ title: 'Hello' })
    })
    expect(result!.id).toBeDefined()
    expect(typeof result!.dismiss).toBe('function')
    expect(typeof result!.update).toBe('function')
  })

  it('creates a toast that appears in useToast state', async () => {
    const { result: hookResult } = renderHook(() => useToast())

    act(() => {
      toast({ title: 'New toast' })
    })

    await waitFor(() => {
      expect(hookResult.current.toasts.length).toBeGreaterThanOrEqual(1)
    })
    const found = hookResult.current.toasts.find((t) => t.title === 'New toast')
    expect(found).toBeDefined()
    expect(found!.open).toBe(true)
  })

  it('dismiss() sets the toast to open: false', async () => {
    const { result: hookResult } = renderHook(() => useToast())

    let handle: ReturnType<typeof toast>
    act(() => {
      handle = toast({ title: 'Dismissable' })
    })

    act(() => {
      handle!.dismiss()
    })

    await waitFor(() => {
      const found = hookResult.current.toasts.find((t) => t.id === handle!.id)
      expect(found === undefined || found.open === false).toBe(true)
    })
  })

  it('update() modifies the existing toast', async () => {
    const { result: hookResult } = renderHook(() => useToast())

    let handle: ReturnType<typeof toast>
    act(() => {
      handle = toast({ title: 'Original' })
    })

    act(() => {
      handle!.update({ id: handle!.id, title: 'Updated' })
    })

    await waitFor(() => {
      const found = hookResult.current.toasts.find((t) => t.id === handle!.id)
      expect(found?.title).toBe('Updated')
    })
  })

  it('onOpenChange(false) triggers dismiss', async () => {
    const { result: hookResult } = renderHook(() => useToast())

    let handle: ReturnType<typeof toast>
    act(() => {
      handle = toast({ title: 'Auto-dismiss' })
    })

    await waitFor(() => {
      const found = hookResult.current.toasts.find((t) => t.id === handle!.id)
      expect(found).toBeDefined()
    })

    act(() => {
      const found = hookResult.current.toasts.find((t) => t.id === handle!.id)
      found?.onOpenChange?.(false)
    })

    await waitFor(() => {
      const found = hookResult.current.toasts.find((t) => t.id === handle!.id)
      expect(found === undefined || found.open === false).toBe(true)
    })
  })
})

// ──── useToast hook ────

describe('useToast()', () => {
  it('returns toasts array, toast function, and dismiss function', () => {
    const { result } = renderHook(() => useToast())
    expect(Array.isArray(result.current.toasts)).toBe(true)
    expect(typeof result.current.toast).toBe('function')
    expect(typeof result.current.dismiss).toBe('function')
  })

  it('dismiss() without id dismisses all toasts', async () => {
    const { result } = renderHook(() => useToast())

    act(() => {
      toast({ title: 'Toast A' })
    })

    act(() => {
      result.current.dismiss()
    })

    await waitFor(() => {
      expect(result.current.toasts.every((t) => t.open === false)).toBe(true)
    })
  })

  it('dismiss() with id dismisses only that toast', async () => {
    const { result } = renderHook(() => useToast())

    let handle: ReturnType<typeof toast>
    act(() => {
      handle = toast({ title: 'Target' })
    })

    act(() => {
      result.current.dismiss(handle!.id)
    })

    await waitFor(() => {
      const found = result.current.toasts.find((t) => t.id === handle!.id)
      expect(found === undefined || found.open === false).toBe(true)
    })
  })

  it('listener cleanup removes setState on unmount', () => {
    const { unmount } = renderHook(() => useToast())
    // Should not throw after unmount when dispatching
    unmount()
    act(() => {
      toast({ title: 'After unmount' })
    })
  })
})
