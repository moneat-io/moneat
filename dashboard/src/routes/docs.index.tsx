import {createFileRoute} from '@tanstack/react-router'
import {getDoc} from '@/docs/loader'
import {mdxComponents} from '@/docs/mdx-components'
import {DocsFeedback} from '@/docs/components/DocsFeedback'
import {SeoHead} from '@/components/SeoHead'
import {docsIndexSeo} from '@/lib/seo/routes'

export const Route = createFileRoute('/docs/')({
  component: DocsIndex,
})

function DocsIndex() {
  const doc = getDoc('intro')

  if (!doc) {
    return (
      <div className="px-4 py-16 text-center text-slate-600">
        <p>Documentation landing page not found.</p>
      </div>
    )
  }

  const {Component} = doc

  return (
    <>
      <SeoHead seo={docsIndexSeo} />
      <article className="px-4 py-12 sm:px-8 lg:px-12">
        <div className="prose prose-invert max-w-none prose-headings:text-white prose-p:text-slate-300 prose-li:text-slate-300 prose-strong:text-white prose-a:text-indigo-300 hover:prose-a:text-indigo-200 prose-code:font-brandmono prose-code:text-slate-200 prose-pre:bg-[#07080e] prose-pre:border prose-pre:border-white/10 [&_code]:before:content-none [&_code]:after:content-none">
          <Component components={mdxComponents} />
        </div>
        <DocsFeedback slug="intro" />
      </article>
    </>
  )
}
