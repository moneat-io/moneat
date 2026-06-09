import {render, screen} from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import {beforeEach, describe, expect, it, vi} from 'vitest'
import {DocsSearchProvider, useDocsSearch} from '../DocsSearch'

const mockNavigate = vi.fn()

vi.mock('@tanstack/react-router', () => ({
  useNavigate: () => mockNavigate,
}))

vi.mock('../../sidebar', () => ({
  docsSidebar: [
    {
      label: 'Guides',
      items: ['intro', 'billing'],
    },
  ],
}))

vi.mock('../../loader', () => ({
  getDoc: (slug: string) => ({
    title: slug === 'intro' ? 'Intro' : 'Billing',
  }),
}))

function SearchLauncher() {
  const {open} = useDocsSearch()
  return (
    <button type="button" onClick={open}>
      Open docs search
    </button>
  )
}

function renderDocsSearch() {
  return render(
    <DocsSearchProvider>
      <SearchLauncher />
    </DocsSearchProvider>
  )
}

class MockResizeObserver {
  observe = vi.fn()
  unobserve = vi.fn()
  disconnect = vi.fn()
}

describe('DocsSearchProvider', () => {
  beforeEach(() => {
    mockNavigate.mockClear()
    vi.stubGlobal('ResizeObserver', MockResizeObserver)
    Element.prototype.scrollIntoView = vi.fn()
  })

  it('opens search from context and navigates to the selected page', async () => {
    const user = userEvent.setup()
    renderDocsSearch()

    await user.click(screen.getByRole('button', {name: 'Open docs search'}))
    const input = screen.getByPlaceholderText(/search the docs/i)
    await user.type(input, 'Billing')
    await user.click(await screen.findByRole('option', {name: /billing/i}))

    expect(mockNavigate).toHaveBeenCalledWith({to: '/docs/$', params: {_splat: 'billing'}})
  })

  it('toggles search with the command shortcut', async () => {
    const user = userEvent.setup()
    renderDocsSearch()

    await user.keyboard('{Meta>}k{/Meta}')

    expect(screen.getByPlaceholderText(/search the docs/i)).toBeInTheDocument()
  })
})
