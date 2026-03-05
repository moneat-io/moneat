import {createFileRoute, Link, notFound} from '@tanstack/react-router'
import {getPost} from '@/blog/loader'
import {Helmet} from 'react-helmet-async'
import {isValidElement, type ReactElement} from 'react'
import {Prism as SyntaxHighlighter} from 'react-syntax-highlighter'
import {oneDark} from 'react-syntax-highlighter/dist/esm/styles/prism'
import {BlogPostFeedback} from '@/components/BlogPostFeedback'

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
    <div className="min-h-[70vh] flex flex-col items-center justify-center px-4 text-center">
      <div className="mb-8 select-none">
        <svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 48 48" className="h-16 w-16 mx-auto mb-6" aria-hidden="true">
          <circle cx="24" cy="24" r="18" fill="none" stroke="#38bdf8" strokeWidth="2" strokeDasharray="4 3" />
          <polyline points="10,24 14,24 18,15 24,31 30,15 34,24 38,24" fill="none" stroke="#38bdf8" strokeWidth="2.5" strokeLinecap="round" strokeLinejoin="round" strokeDasharray="60 120" />
        </svg>
        <p className="text-8xl font-bold text-slate-800 leading-none tracking-tight">404</p>
      </div>
      <h1 className="text-2xl font-semibold text-white mb-3">Post not found</h1>
      <p className="text-slate-400 max-w-sm mb-10">
        This post may have moved or never existed. Head back to the blog to find what you're looking for.
      </p>
      <div className="flex items-center gap-4">
        <Link to="/blog" className="px-5 py-2.5 text-sm font-medium text-white bg-sky-500 hover:bg-sky-600 rounded-lg shadow-md shadow-sky-500/25 transition-colors">
          Back to blog
        </Link>
        <Link to="/" className="px-5 py-2.5 text-sm font-medium text-slate-300 hover:text-white border border-slate-700 hover:border-slate-500 rounded-lg transition-colors">
          moneat.io
        </Link>
      </div>
    </div>
  )
}

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

const mdxComponents = {pre: MdxPre}

function BlogPost() {
  const post = Route.useLoaderData()
  const {Component} = post

  return (
    <>
      <Helmet>
        <title>{post.title} — Moneat Blog</title>
        <meta name="description" content={post.description} />
        <meta property="og:type" content="article" />
        <meta property="og:title" content={post.title} />
        <meta property="og:description" content={post.description} />
        <meta property="article:published_time" content={post.date} />
        <meta property="article:author" content={post.author} />
      </Helmet>
      <article className="max-w-3xl mx-auto px-4 sm:px-6 lg:px-8 py-16">
        <header className="mb-10">
          <div className="flex flex-wrap gap-2 mb-4">
            {post.tags?.map((tag) => (
              <span key={tag} className="inline-block text-xs font-medium text-sky-400 bg-sky-500/10 px-2 py-0.5 rounded-full">
                {tag}
              </span>
            ))}
          </div>
          <h1 className="text-3xl sm:text-4xl font-bold text-white mb-4">{post.title}</h1>
          <div className="flex items-center gap-3 text-sm text-slate-400">
            <span>{post.author}</span>
            <span>&middot;</span>
            <time dateTime={post.date}>
              {new Date(post.date).toLocaleDateString('en-US', {year: 'numeric', month: 'long', day: 'numeric'})}
            </time>
            <span>&middot;</span>
            <span>{post.readingTime}</span>
          </div>
        </header>
        <div className="prose prose-invert prose-sky max-w-none prose-headings:text-white prose-p:text-slate-300 prose-li:text-slate-300 prose-strong:text-white prose-th:text-slate-200 prose-td:text-slate-300 prose-table:border-collapse prose-th:border prose-th:border-slate-700 prose-td:border prose-td:border-slate-700 prose-th:px-3 prose-th:py-2 prose-td:px-3 prose-td:py-2">
          <Component components={mdxComponents} />
        </div>
        <BlogPostFeedback slug={post.slug} />
        <footer className="mt-16 pt-8 border-t border-slate-800">
          <Link to="/blog" className="text-sm text-sky-400 hover:text-sky-300 transition-colors">
            &larr; Back to all posts
          </Link>
        </footer>
      </article>
    </>
  )
}
