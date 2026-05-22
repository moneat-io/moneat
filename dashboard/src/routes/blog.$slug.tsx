import {createFileRoute, Link, notFound} from '@tanstack/react-router'
import {getPost} from '@/blog/loader'
import {Helmet} from 'react-helmet-async'
import {MdxPre} from '@/docs/mdx-components'
import {BlogPostFeedback} from '@/components/BlogPostFeedback'
import {SITE_ORIGIN} from '@/lib/site'

export const Route = createFileRoute('/blog/$slug')({
  loader: ({params}) => {
    const post = getPost(params.slug)
    if (!post) throw notFound()
    return post
  },
  notFoundComponent: BlogNotFound,
  component: BlogPost,
})

function BlogNotFound() {
  return (
    <div className="flex min-h-[70vh] flex-col items-center justify-center px-4 text-center">
      <div className="mb-8 select-none">
        <svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 48 48" className="mx-auto mb-6 size-16" aria-hidden="true">
          <circle cx="24" cy="24" r="18" fill="none" stroke="#0369a1" strokeWidth="2" strokeDasharray="4 3" />
          <polyline points="10,24 14,24 18,15 24,31 30,15 34,24 38,24" fill="none" stroke="#0369a1" strokeWidth="2.5" strokeLinecap="round" strokeLinejoin="round" strokeDasharray="60 120" />
        </svg>
        <p className="text-8xl font-semibold leading-none text-slate-200">404</p>
      </div>
      <h1 className="mb-3 text-2xl font-semibold text-slate-950">Post not found</h1>
      <p className="mb-10 max-w-sm text-slate-600">
        This post may have moved or never existed. Head back to the blog to find what you're looking for.
      </p>
      <div className="flex items-center gap-4">
        <Link to="/blog" className="rounded-lg bg-slate-950 px-5 py-2.5 text-sm font-medium text-white transition-colors hover:bg-slate-800">
          Back to blog
        </Link>
        <Link to="/" className="rounded-lg border border-slate-300 px-5 py-2.5 text-sm font-medium text-slate-700 transition-colors hover:border-slate-500 hover:text-slate-950">
          moneat.io
        </Link>
      </div>
    </div>
  )
}

const mdxComponents = {pre: MdxPre}

function BlogPost() {
  const post = Route.useLoaderData()
  const {Component} = post

  return (
    <>
      <Helmet>
        <title>{post.title} — Moneat Blog</title>
        <meta name="description" content={post.description} />
        <link rel="canonical" href={`${SITE_ORIGIN}/blog/${post.slug}`} />
        <meta property="og:type" content="article" />
        <meta property="og:title" content={post.title} />
        <meta property="og:description" content={post.description} />
        <meta property="og:url" content={`${SITE_ORIGIN}/blog/${post.slug}`} />
        <meta property="article:published_time" content={post.date} />
        <meta property="article:author" content={post.author} />
      </Helmet>
      <article className="mx-auto max-w-3xl px-4 py-16 sm:px-6 lg:px-8">
        <header className="mb-10">
          <div className="flex flex-wrap gap-2 mb-4">
            {post.tags?.map((tag) => (
              <span key={tag} className="inline-block rounded-md bg-sky-50 px-2 py-0.5 text-xs font-medium text-sky-800">
                {tag}
              </span>
            ))}
          </div>
          <h1 className="mb-4 text-3xl font-semibold leading-tight text-slate-950 sm:text-4xl">{post.title}</h1>
          <div className="flex items-center gap-3 text-sm text-slate-500">
            <span>{post.author}</span>
            <span>&middot;</span>
            <time dateTime={post.date}>
              {new Date(post.date).toLocaleDateString('en-US', {year: 'numeric', month: 'long', day: 'numeric', timeZone: 'UTC'})}
            </time>
            <span>&middot;</span>
            <span>{post.readingTime}</span>
          </div>
        </header>
        <div className="prose prose-slate max-w-none prose-headings:text-slate-950 prose-p:text-slate-700 prose-li:text-slate-700 prose-strong:text-slate-950 prose-th:text-slate-950 prose-td:text-slate-700 prose-table:border-collapse prose-th:border prose-th:border-slate-200 prose-th:bg-slate-50 prose-th:px-3 prose-th:py-2 prose-td:border prose-td:border-slate-200 prose-td:px-3 prose-td:py-2">
          <Component components={mdxComponents} />
        </div>
        <BlogPostFeedback slug={post.slug} />
        <footer className="mt-16 border-t border-slate-200 pt-8">
          <Link to="/blog" className="text-sm font-medium text-sky-700 transition-colors hover:text-sky-900">
            &larr; Back to all posts
          </Link>
        </footer>
      </article>
    </>
  )
}
