import {isValidElement, type ReactElement} from 'react'
import {Link as TanStackLink} from '@tanstack/react-router'
import {Prism as SyntaxHighlighter} from 'react-syntax-highlighter'
import {oneDark} from 'react-syntax-highlighter/dist/esm/styles/prism'
import Admonition from './components/Admonition'
import StepList from './components/StepList'
import SdkSetup from './components/SdkSetup'

export function MdxPre(props: React.ComponentPropsWithoutRef<'pre'>) {
  const child = props.children
  if (isValidElement(child)) {
    const {className, children} = (child as ReactElement<{className?: string; children?: string}>).props
    const match = /language-([\w-]+)/.exec(className || '')
    if (match) {
      return (
        <SyntaxHighlighter language={match[1]} style={oneDark} customStyle={{borderRadius: '0.375rem'}}>
          {String(children).replace(/\n$/, '')}
        </SyntaxHighlighter>
      )
    }
  }
  return <pre {...props} />
}

export function DocsLink(props: React.ComponentPropsWithoutRef<'a'>) {
  return <a {...props} className={props.className ?? 'text-sky-400 hover:text-sky-300 underline'} />
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
