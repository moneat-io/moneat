import {createFileRoute} from '@tanstack/react-router'
import {getDoc} from '@/docs/loader'
import {Helmet} from 'react-helmet-async'
import {isValidElement, type ReactElement} from 'react'
import {Prism as SyntaxHighlighter} from 'react-syntax-highlighter'
import {oneDark} from 'react-syntax-highlighter/dist/esm/styles/prism'
import Admonition from '@/docs/components/Admonition'
import StepList from '@/docs/components/StepList'
import SdkSetup from '@/docs/components/SdkSetup'

import {DocsFeedback} from '@/docs/components/DocsFeedback'

export const Route = createFileRoute('/docs/')({
  component: DocsIndex,
})

function MdxPre(props: React.ComponentPropsWithoutRef<'pre'>) {
  const child = props.children
  if (isValidElement(child)) {
    const {className, children} = (child as ReactElement<{className?: string; children?: string}>).props
    const match = /language-(\w+)/.exec(className || '')
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

function DocsLink(props: React.ComponentPropsWithoutRef<'a'>) {
  return <a {...props} className={props.className ?? 'text-sky-400 hover:text-sky-300 underline'} />
}

const mdxComponents = {pre: MdxPre, Admonition, StepList, SdkSetup, a: DocsLink}

function DocsIndex() {
  const doc = getDoc('intro')

  if (!doc) {
    return (
      <div className="py-16 px-4 text-center text-slate-400">
        <p>Documentation landing page not found.</p>
      </div>
    )
  }

  const {Component} = doc

  return (
    <>
      <Helmet>
        <title>Documentation — Moneat</title>
        <meta name="description" content="Moneat documentation — error monitoring, incident management, uptime tracking, and structured logging." />
      </Helmet>
      <article className="py-12 px-4 sm:px-8 lg:px-12">
        <div className="prose prose-invert prose-sky max-w-none prose-headings:text-white prose-p:text-slate-300 prose-li:text-slate-300 prose-strong:text-white prose-a:text-sky-400 hover:prose-a:text-sky-300 [&_code]:before:content-none [&_code]:after:content-none">
          <Component components={mdxComponents} />
        </div>
        <DocsFeedback slug="intro" />
      </article>
    </>
  )
}
