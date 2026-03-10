import { describe, it, expect, beforeEach, vi } from 'vitest'
import { renderHook, act } from '@testing-library/react'
import { ProjectProvider, useProject } from '../ProjectContext'

describe('ProjectProvider', () => {
  beforeEach(() => {
    localStorage.clear()
  })

  it('initializes with null when no saved project', () => {
    const { result } = renderHook(() => useProject(), {
      wrapper: ProjectProvider,
    })
    expect(result.current.selectedProjectId).toBeNull()
  })

  it('initializes from localStorage when saved project exists', () => {
    localStorage.setItem('selectedProjectId', '42')
    const { result } = renderHook(() => useProject(), {
      wrapper: ProjectProvider,
    })
    expect(result.current.selectedProjectId).toBe(42)
  })

  it('updates selectedProjectId', () => {
    const { result } = renderHook(() => useProject(), {
      wrapper: ProjectProvider,
    })

    act(() => {
      result.current.setSelectedProjectId(5)
    })

    expect(result.current.selectedProjectId).toBe(5)
  })

  it('persists to localStorage when project selected', () => {
    const { result } = renderHook(() => useProject(), {
      wrapper: ProjectProvider,
    })

    act(() => {
      result.current.setSelectedProjectId(10)
    })

    expect(localStorage.getItem('selectedProjectId')).toBe('10')
  })

  it('removes from localStorage when set to null', () => {
    localStorage.setItem('selectedProjectId', '7')
    const { result } = renderHook(() => useProject(), {
      wrapper: ProjectProvider,
    })

    act(() => {
      result.current.setSelectedProjectId(null)
    })

    expect(localStorage.getItem('selectedProjectId')).toBeNull()
  })
})

describe('useProject', () => {
  it('throws when used outside ProjectProvider', () => {
    const spy = vi.spyOn(console, 'error').mockImplementation(() => {})
    
    expect(() => {
      renderHook(() => useProject())
    }).toThrow('useProject must be used within a ProjectProvider')
    
    spy.mockRestore()
  })
})
