import {createFileRoute, Link, notFound} from '@tanstack/react-router'
import {getPost} from '@/blog/loader'
import {MdxPre} from '@/docs/mdx-components'
import {BlogPostFeedback} from '@/components/BlogPostFeedback'
import {SeoHead} from '@/components/SeoHead'
import {blogPostSeo} from '@/lib/seo/routes'

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
          <circle cx="24" cy="24" r="18" fill="none" stroke="#818cf8" strokeWidth="2" strokeDasharray="4 3" />
          <polyline points="10,24 14,24 18,15 24,31 30,15 34,24 38,24" fill="none" stroke="#818cf8" strokeWidth="2.5" strokeLinecap="round" strokeLinejoin="round" strokeDasharray="60 120" />
        </svg>
        <p className="text-8xl font-semibold leading-none text-slate-600">404</p>
      </div>
      <h1 className="mb-3 text-2xl font-semibold text-white">Post not found</h1>
      <p className="mb-10 max-w-sm text-slate-400">
        This post may have moved or never existed. Head back to the blog to find what you're looking for.
      </p>
      <div className="flex items-center gap-4">
        <Link to="/blog" className="rounded-lg bg-indigo-600 px-5 py-2.5 text-sm font-medium text-white transition-colors hover:bg-indigo-500">
          Back to blog
        </Link>
        <Link to="/" className="rounded-lg border border-white/10 px-5 py-2.5 text-sm font-medium text-slate-300 transition-colors hover:border-white/20 hover:text-white">
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
      <SeoHead seo={blogPostSeo(post)} />
      <article className="mx-auto max-w-3xl px-4 py-16 sm:px-6 lg:px-8">
        <header className="mb-10">
          <div className="flex flex-wrap gap-2 mb-4">
            {post.tags?.map((tag) => (
              <span key={tag} className="inline-block rounded-md border border-white/10 bg-white/[0.04] px-2 py-0.5 font-brandmono text-xs text-indigo-300">
                {tag}
              </span>
            ))}
          </div>
          <h1 className="mb-4 text-3xl font-semibold leading-tight text-white sm:text-4xl">{post.title}</h1>
          <div className="flex items-center gap-3 font-brandmono text-sm text-slate-500">
            <span>{post.author}</span>
            <span>&middot;</span>
            <time dateTime={post.date}>
              {new Date(post.date).toLocaleDateString('en-US', {year: 'numeric', month: 'long', day: 'numeric', timeZone: 'UTC'})}
            </time>
            <span>&middot;</span>
            <span>{post.readingTime}</span>
          </div>
        </header>
        <div className="prose prose-invert max-w-none prose-headings:text-white prose-p:text-slate-300 prose-li:text-slate-300 prose-strong:text-white prose-a:text-indigo-300 hover:prose-a:text-indigo-200 prose-th:text-white prose-td:text-slate-300 prose-table:border-collapse prose-th:border prose-th:border-white/10 prose-th:bg-white/[0.04] prose-th:px-3 prose-th:py-2 prose-td:border prose-td:border-white/[0.06] prose-td:px-3 prose-td:py-2 prose-code:font-brandmono prose-code:text-slate-200 prose-pre:bg-[#07080e] prose-pre:border prose-pre:border-white/10">
          <Component components={mdxComponents} />
        </div>
        <BlogPostFeedback slug={post.slug} />
        <footer className="mt-16 border-t border-white/10 pt-8">
          <Link to="/blog" className="font-brandmono text-sm font-medium text-indigo-300 transition-colors hover:text-indigo-200">
            &larr; Back to all posts
          </Link>
        </footer>
      </article>
    </>
  )
}
