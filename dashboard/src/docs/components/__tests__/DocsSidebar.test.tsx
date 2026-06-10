import React from 'react'
import {render, screen} from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import {beforeEach, describe, expect, it, vi} from 'vitest'
import DocsSidebar from '../DocsSidebar'

const mockOpenDocsSearch = vi.fn()
let mockPathname = '/docs/billing'

vi.mock('@tanstack/react-router', async () => {
  const ReactImport = await import('react')
  return {
    Link: ({
      to,
      params,
      className,
      children,
      style,
    }: {
      to: string
      params?: Record<string, string>
      className?: string
      children: React.ReactNode
      style?: React.CSSProperties
    }) =>
      ReactImport.createElement(
        'a',
        {href: params?._splat ? `/docs/${params._splat}` : to, className, style},
        children,
      ),
    useRouterState: ({select}: {select: (state: {location: {pathname: string}}) => string}) =>
      select({location: {pathname: mockPathname}}),
  }
})

vi.mock('../../sidebar', () => ({
  docsSidebar: [
    {
      label: 'Guides',
      items: [
        'intro',
        'billing',
        {
          label: 'Datadog Agent',
          link: 'datadog-agent',
          collapsed: true,
          items: ['datadog-agent/log-collection'],
        },
      ],
    },
  ],
}))

vi.mock('../../loader', () => ({
  getDoc: (slug: string) => {
    const titles: Record<string, string> = {
      intro: 'Intro',
      billing: 'Billing',
      'datadog-agent/log-collection': 'Log collection',
    }
    return titles[slug] ? {title: titles[slug]} : undefined
  },
}))

vi.mock('../DocsSearch', () => ({
  useDocsSearch: () => ({open: mockOpenDocsSearch}),
}))

describe('DocsSidebar', () => {
  beforeEach(() => {
    mockOpenDocsSearch.mockClear()
    mockPathname = '/docs/billing'
  })

  it('marks the current docs page and opens search from the sidebar', async () => {
    const user = userEvent.setup()

    render(<DocsSidebar />)

    expect(screen.getByRole('link', {name: 'Billing'})).toHaveClass('font-medium')

    await user.click(screen.getByRole('button', {name: /search docs/i}))

    expect(mockOpenDocsSearch).toHaveBeenCalledTimes(1)
  })

  it('opens a collapsed nested category when a descendant is active', () => {
    mockPathname = '/docs/datadog-agent/log-collection'

    render(<DocsSidebar />)

    expect(screen.getByRole('link', {name: 'Log collection'})).toHaveAttribute(
      'href',
      '/docs/datadog-agent/log-collection',
    )
  })
})
