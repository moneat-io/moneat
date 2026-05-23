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
      <p className="mb-4 text-7xl font-semibold text-slate-200">404</p>
      <h1 className="mb-3 text-xl font-semibold text-slate-950">Page not found</h1>
      <p className="mb-8 max-w-sm text-slate-600">
        This documentation page may have moved or doesn't exist yet.
      </p>
      <Link to="/docs" className="rounded-lg bg-slate-950 px-5 py-2.5 text-sm font-medium text-white transition-colors hover:bg-slate-800">
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
      <article className="px-4 py-12 sm:px-8 lg:px-12">
        <div className="prose prose-slate max-w-none prose-headings:text-slate-950 prose-p:text-slate-700 prose-li:text-slate-700 prose-strong:text-slate-950 prose-a:text-sky-700 hover:prose-a:text-sky-900 [&_code]:before:content-none [&_code]:after:content-none">
          <Component components={mdxComponents} />
        </div>
        <DocsFeedback slug={doc.slug} />
        <footer className="mt-16 border-t border-slate-200 pt-8">
          <Link to="/docs" className="text-sm font-medium text-sky-700 transition-colors hover:text-sky-900">
            &larr; Back to docs
          </Link>
        </footer>
      </article>
    </>
  )
}
