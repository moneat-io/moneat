import {createFileRoute, Link, notFound} from '@tanstack/react-router'
import {getDoc} from '@/docs/loader'
import {Helmet} from 'react-helmet-async'
import {mdxComponents} from '@/docs/mdx-components'
import {DocsFeedback} from '@/docs/components/DocsFeedback'
import {SITE_ORIGIN} from '@/lib/site'

export const Route = createFileRoute('/docs/$')({
  loader: ({params}) => {
    const slug = params['_splat'] ?? ''
    const doc = getDoc(slug)
    if (!doc) throw notFound()
    return doc
  },
  notFoundComponent: DocNotFound,
  component: DocPage,
})

function DocNotFound() {
  return (
    <div className="flex min-h-[60vh] flex-col items-center justify-center px-4 text-center">
      <p className="mb-4 text-7xl font-semibold text-slate-600">404</p>
      <h1 className="mb-3 text-xl font-semibold text-white">Page not found</h1>
      <p className="mb-8 max-w-sm text-slate-400">
        This documentation page may have moved or doesn't exist yet.
      </p>
      <Link to="/docs" className="rounded-lg bg-indigo-600 px-5 py-2.5 text-sm font-medium text-white transition-colors hover:bg-indigo-500">
        Back to docs
      </Link>
    </div>
  )
}

function DocPage() {
  const doc = Route.useLoaderData()
  const {Component} = doc

  return (
    <>
      <Helmet>
        <title>{doc.title} — Moneat Docs</title>
        {doc.description && <meta name="description" content={doc.description} />}
        <link rel="canonical" href={`${SITE_ORIGIN}/docs/${doc.slug}`} />
      </Helmet>
      <article className="mx-auto max-w-3xl px-6 py-12 sm:px-10 lg:px-14">
        <div className="prose prose-invert max-w-none prose-headings:text-white prose-p:text-slate-300 prose-li:text-slate-300 prose-strong:text-white prose-a:text-indigo-300 hover:prose-a:text-indigo-200 prose-code:font-brandmono prose-code:text-slate-200 prose-pre:bg-[#07080e] prose-pre:border prose-pre:border-white/10 [&_code]:before:content-none [&_code]:after:content-none">
          <Component components={mdxComponents} />
        </div>
        <DocsFeedback slug={doc.slug} />
        <footer className="mt-16 border-t border-white/10 pt-8">
          <Link to="/docs" className="font-brandmono text-sm font-medium text-indigo-300 transition-colors hover:text-indigo-200">
            &larr; Back to docs
          </Link>
        </footer>
      </article>
    </>
  )
}
