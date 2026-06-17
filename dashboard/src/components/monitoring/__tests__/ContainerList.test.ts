import {describe, expect, it, vi} from 'vitest'

vi.mock('@/lib/api', () => ({
  api: {
    getContainers: vi.fn(),
    isAuthenticated: vi.fn(() => false),
  },
}))

import {containerIconClassName, stateFilterCount} from '../ContainerList.helpers'

describe('ContainerList helper coverage', () => {
  it('counts state filters and resolves icon classes', () => {
    expect(stateFilterCount('all', 5, 2, 3)).toBe(5)
    expect(stateFilterCount('running', 5, 2, 3)).toBe(2)
    expect(stateFilterCount('stopped', 5, 2, 3)).toBe(3)
    expect(containerIconClassName('running')).toBe('bg-success-bg text-success-fg')
    expect(containerIconClassName('exited')).toBe('bg-danger-bg text-danger-fg')
    expect(containerIconClassName('dead')).toBe('bg-danger-bg text-danger-fg')
    expect(containerIconClassName('paused')).toBe('bg-warning-bg text-warning-fg')
  })
})
