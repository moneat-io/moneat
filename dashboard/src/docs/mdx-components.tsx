import {isValidElement, type ComponentPropsWithoutRef, type ReactElement} from 'react'
import {Link as TanStackLink} from '@tanstack/react-router'
import {Prism as SyntaxHighlighter} from 'react-syntax-highlighter'
import {oneDark} from 'react-syntax-highlighter/dist/esm/styles/prism'
import Admonition from './components/Admonition'
import StepList from './components/StepList'
import SdkSetup from './components/SdkSetup'

const BACKEND_URL = (import.meta.env.VITE_BACKEND_URL || 'https://api.moneat.io').replace(/\/$/, '')

function datadogLogsEndpointAddress(backendUrl: string): string {
  try {
    const parsed = new URL(backendUrl)
    const port = parsed.port || (parsed.protocol === 'http:' ? '80' : '443')
    return `${parsed.hostname}:${port}`
  } catch {
    return backendUrl
  }
}

function datadogForwarderEndpoint(backendUrl: string): string {
  try {
    const parsed = new URL(backendUrl)
    const port = parsed.port ? `:${parsed.port}` : ''
    return parsed.protocol === 'https:' ? `${parsed.hostname}${port}` : backendUrl
  } catch {
    return backendUrl
  }
}

const DOC_TOKENS: Record<string, string> = {
  '{{BACKEND_URL}}': BACKEND_URL,
  '{{BACKEND_HOST}}': datadogForwarderEndpoint(BACKEND_URL),
  '{{INGEST_URL}}': BACKEND_URL + '/dd',
  '{{LOGS_HOST_PORT}}': datadogLogsEndpointAddress(BACKEND_URL),
}

function interpolateTokens(text: string): string {
  let result = text
  for (const [token, value] of Object.entries(DOC_TOKENS)) {
    result = result.replaceAll(token, value)
  }
  return result
}

export function MdxPre(props: ComponentPropsWithoutRef<'pre'>) {
  const child = props.children
  if (isValidElement(child)) {
    const {className, children} = (child as ReactElement<{className?: string; children?: string}>).props
    const match = /language-([\w-]+)/.exec(className || '')
    if (match) {
      return (
        <SyntaxHighlighter language={match[1]} style={oneDark} customStyle={{borderRadius: '0.375rem'}}>
          {interpolateTokens(String(children).replace(/\n$/, ''))}
        </SyntaxHighlighter>
      )
    }
  }
  return <pre {...props} />
}

export function DocsLink(props: ComponentPropsWithoutRef<'a'>) {
  return <a {...props} className={props.className ?? 'text-sky-700 underline hover:text-sky-900'} />
}

// eslint-disable-next-line react-refresh/only-export-components -- intentional shared utility module
export const mdxComponents = {
  pre: MdxPre,
  Admonition,
  StepList,
  SdkSetup,
  a: DocsLink,
  Link: TanStackLink,
}
