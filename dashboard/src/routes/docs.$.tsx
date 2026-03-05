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
    <div className="min-h-[60vh] flex flex-col items-center justify-center px-4 text-center">
      <p className="text-7xl font-bold text-slate-800 mb-4">404</p>
      <h1 className="text-xl font-semibold text-white mb-3">Page not found</h1>
      <p className="text-slate-400 max-w-sm mb-8">
        This documentation page may have moved or doesn't exist yet.
      </p>
      <Link to="/docs" className="px-5 py-2.5 text-sm font-medium text-white bg-sky-500 hover:bg-sky-600 rounded-lg shadow-md shadow-sky-500/25 transition-colors">
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
      <article className="py-12 px-4 sm:px-8 lg:px-12">
        <div className="prose prose-invert prose-sky max-w-none prose-headings:text-white prose-p:text-slate-300 prose-li:text-slate-300 prose-strong:text-white prose-a:text-sky-400 hover:prose-a:text-sky-300 [&_code]:before:content-none [&_code]:after:content-none">
          <Component components={mdxComponents} />
        </div>
        <DocsFeedback slug={doc.slug} />
        <footer className="mt-16 pt-8 border-t border-slate-800">
          <Link to="/docs" className="text-sm text-sky-400 hover:text-sky-300 transition-colors">
            &larr; Back to docs
          </Link>
        </footer>
      </article>
    </>
  )
}
