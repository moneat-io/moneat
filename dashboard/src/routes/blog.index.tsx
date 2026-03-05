import {createFileRoute, Link} from '@tanstack/react-router'
import {allPosts} from '@/blog/loader'
import {Helmet} from 'react-helmet-async'

export const Route = createFileRoute('/blog/')({
  component: BlogIndex,
})

function BlogIndex() {
  const allTags = Array.from(new Set(allPosts.flatMap((p) => p.tags ?? []))).sort()

  return (
    <>
      <Helmet>
        <title>Blog — Moneat</title>
        <meta name="description" content="Engineering deep-dives, observability best practices, and product updates." />
      </Helmet>
      <div className="max-w-4xl mx-auto px-4 sm:px-6 lg:px-8 py-16">
        <header className="mb-12">
          <h1 className="text-4xl font-bold text-white mb-4">Blog</h1>
          <p className="text-lg text-slate-400">
            Engineering deep-dives, observability best practices, and product updates.
          </p>
        </header>

        {allTags.length > 0 && (
          <div className="flex flex-wrap gap-2 mb-10">
            {allTags.map((tag) => (
              <span
                key={tag}
                className="inline-block text-xs font-medium text-slate-400 bg-slate-800 px-3 py-1 rounded-full"
              >
                {tag}
              </span>
            ))}
          </div>
        )}

        <div className="grid gap-6">
          {allPosts.map((post) => (
            <Link key={post.slug} to="/blog/$slug" params={{slug: post.slug}} className="block group">
              <article className="rounded-xl border border-slate-800 bg-slate-900/50 p-6 transition-colors hover:border-sky-500/50 hover:bg-slate-900">
                <div className="flex flex-wrap gap-2 mb-3">
                  {post.tags?.map((tag) => (
                    <span key={tag} className="inline-block text-xs font-medium text-sky-400 bg-sky-500/10 px-2 py-0.5 rounded-full">
                      {tag}
                    </span>
                  ))}
                </div>
                <h2 className="text-xl font-semibold text-white group-hover:text-sky-400 transition-colors mb-2">
                  {post.title}
                </h2>
                <p className="text-sm text-slate-400 line-clamp-2 mb-4">{post.description}</p>
                <div className="flex items-center gap-3 text-xs text-slate-500">
                  <span>{post.author}</span>
                  <span>&middot;</span>
                  <time dateTime={post.date}>
                    {new Date(post.date).toLocaleDateString('en-US', {year: 'numeric', month: 'long', day: 'numeric'})}
                  </time>
                  <span>&middot;</span>
                  <span>{post.readingTime}</span>
                </div>
              </article>
            </Link>
          ))}
          {allPosts.length === 0 && (
            <p className="text-slate-500 text-center py-20">No posts yet. Check back soon.</p>
          )}
        </div>
      </div>
    </>
  )
}
