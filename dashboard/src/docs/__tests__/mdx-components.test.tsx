import {render, screen} from '@testing-library/react'
import {describe, expect, it, vi} from 'vitest'
import {DocsLink, MdxPre} from '../mdx-components'

vi.mock('react-syntax-highlighter', () => ({
  Prism: ({children, language}: {children: string; language: string}) => (
    <pre data-testid="syntax" data-language={language}>
      {children}
    </pre>
  ),
}))

vi.mock('react-syntax-highlighter/dist/esm/styles/prism', () => ({
  oneDark: {},
}))

describe('mdx components', () => {
  it('interpolates docs tokens inside fenced code blocks', () => {
    const backendUrl = (import.meta.env.VITE_BACKEND_URL || 'https://api.moneat.io').replace(/\/$/, '')

    render(
      <MdxPre>
        <code className="language-bash">{'curl {{BACKEND_URL}}/v1/events\n'}</code>
      </MdxPre>,
    )

    expect(screen.getByTestId('syntax')).toHaveAttribute('data-language', 'bash')
    expect(screen.getByTestId('syntax')).toHaveTextContent(`curl ${backendUrl}/v1/events`)
  })

  it('keeps empty generated links accessible', () => {
    render(<DocsLink href="/docs/getting-started" />)

    expect(screen.getByRole('link', {name: '/docs/getting-started'})).toHaveAttribute(
      'href',
      '/docs/getting-started',
    )
  })
})
