import React from 'react'
import {fireEvent, render, screen} from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import {beforeEach, describe, expect, it, vi} from 'vitest'

const mockOpenDocsSearch = vi.fn()
const mockClipboardWriteText = vi.fn()

vi.mock('@tanstack/react-router', async () => {
  const ReactImport = await import('react')
  return {
    createFileRoute: () => (options: Record<string, unknown>) => ({...options, options}),
    Link: ({
      to,
      params,
      className,
      children,
    }: {
      to: string
      params?: Record<string, string>
      className?: string
      children: React.ReactNode
    }) =>
      ReactImport.createElement(
        'a',
        {href: params?._splat ? `/docs/${params._splat}` : to, className},
        children,
      ),
  }
})

vi.mock('@/components/SeoHead', () => ({
  SeoHead: () => null,
}))

vi.mock('@/docs/components/DocsFeedback', () => ({
  DocsFeedback: ({slug}: {slug: string}) => <div data-testid="docs-feedback">{slug}</div>,
}))

vi.mock('@/docs/components/DocsSearch', () => ({
  useDocsSearch: () => ({open: mockOpenDocsSearch}),
}))

import {Route as DocsIndexRoute} from '../docs.index'

type RouteLike = {component: React.ComponentType}

class MockIntersectionObserver {
  observe = vi.fn()
  disconnect = vi.fn()
}

function renderDocsIndex() {
  const Component = (DocsIndexRoute as unknown as RouteLike).component
  return render(<Component />)
}

describe('docs index route', () => {
  beforeEach(() => {
    mockOpenDocsSearch.mockClear()
    mockClipboardWriteText.mockClear()
    vi.stubGlobal('IntersectionObserver', MockIntersectionObserver)
    vi.stubGlobal('navigator', {
      ...globalThis.navigator,
      clipboard: {writeText: mockClipboardWriteText},
    })
  })

  it('renders primary docs entry points and capability links', () => {
    renderDocsIndex()

    expect(screen.getByRole('heading', {name: /instrument once/i})).toBeInTheDocument()
    expect(screen.getByRole('link', {name: /get started/i})).toHaveAttribute('href', '/docs/getting-started')
    expect(screen.getByRole('link', {name: /error monitoring/i})).toHaveAttribute('href', '/docs/error-monitoring')
    expect(screen.getByRole('link', {name: /datadog agent setup/i})).toHaveAttribute('href', '/docs/datadog-agent')
    expect(screen.getByTestId('docs-feedback')).toHaveTextContent('intro')
  })

  it('opens docs search from the command bar', async () => {
    const user = userEvent.setup()
    renderDocsIndex()

    await user.click(screen.getByRole('button', {name: /search the docs/i}))

    expect(mockOpenDocsSearch).toHaveBeenCalledTimes(1)
  })

  it('switches and copies telemetry snippets', async () => {
    const user = userEvent.setup()
    renderDocsIndex()

    await user.click(screen.getByRole('button', {name: 'Datadog'}))
    expect(screen.getByText('datadog.yaml')).toBeInTheDocument()
    fireEvent.click(screen.getByRole('button', {name: 'Copy snippet'}))

    expect(screen.getByRole('button', {name: 'Snippet copied'})).toBeInTheDocument()
  })
})
