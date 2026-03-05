import {createFileRoute} from '@tanstack/react-router'
import {getDoc} from '@/docs/loader'
import {Helmet} from 'react-helmet-async'
import {mdxComponents} from '@/docs/mdx-components'
import {DocsFeedback} from '@/docs/components/DocsFeedback'

export const Route = createFileRoute('/docs/')({
  component: DocsIndex,
})

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
