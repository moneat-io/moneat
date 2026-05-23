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
      <div className="px-4 py-16 text-center text-slate-500">
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
      <article className="px-4 py-12 sm:px-8 lg:px-12">
        <div className="prose prose-slate max-w-none prose-headings:text-slate-950 prose-p:text-slate-700 prose-li:text-slate-700 prose-strong:text-slate-950 prose-a:text-sky-700 hover:prose-a:text-sky-900 [&_code]:before:content-none [&_code]:after:content-none">
          <Component components={mdxComponents} />
        </div>
        <DocsFeedback slug="intro" />
      </article>
    </>
  )
}
