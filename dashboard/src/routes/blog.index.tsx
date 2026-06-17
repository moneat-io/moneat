import {createFileRoute, Link} from '@tanstack/react-router'
import {allPosts} from '@/blog/loader'
import {SeoHead} from '@/components/SeoHead'
import {blogIndexSeo} from '@/lib/seo/routes'

export const Route = createFileRoute('/blog/')({
  component: BlogIndex,
})

function BlogIndex() {
  const allTags = Array.from(new Set(allPosts.flatMap((p) => p.tags ?? []))).sort()

  return (
    <>
      <SeoHead seo={blogIndexSeo} />
      <div className="mx-auto max-w-5xl px-4 py-16 sm:px-6 lg:px-8">
        <header className="mb-12 max-w-2xl">
          <h1 className="mb-4 text-4xl font-semibold leading-tight text-white sm:text-5xl">Blog</h1>
          <p className="text-lg leading-8 text-slate-400">
            Engineering deep-dives, observability best practices, and product updates.
          </p>
        </header>

        {allTags.length > 0 && (
          <div className="flex flex-wrap gap-2 mb-10">
            {allTags.map((tag) => (
              <span
                key={tag}
                className="inline-block rounded-md border border-white/10 bg-white/[0.04] px-3 py-1 font-brandmono text-xs text-slate-400"
              >
                {tag}
              </span>
            ))}
          </div>
        )}

        <div className="grid gap-4">
          {allPosts.map((post) => (
            <Link key={post.slug} to="/blog/$slug" params={{slug: post.slug}} className="block group">
              <article className="rounded-lg border border-white/[0.06] bg-[#0c0e16] p-6 transition-colors hover:border-white/10">
                <div className="flex flex-wrap gap-2 mb-3">
                  {post.tags?.map((tag) => (
                    <span key={tag} className="inline-block rounded-md border border-white/10 bg-white/[0.04] px-2 py-0.5 font-brandmono text-xs text-indigo-300">
                      {tag}
                    </span>
                  ))}
                </div>
                <h2 className="mb-2 text-xl font-semibold text-white transition-colors group-hover:text-indigo-200">
                  {post.title}
                </h2>
                <p className="mb-4 line-clamp-2 text-sm leading-6 text-slate-400">{post.description}</p>
                <div className="flex items-center gap-3 font-brandmono text-xs text-slate-500">
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
            <p className="py-20 text-center text-slate-500">No posts yet. Check back soon.</p>
          )}
        </div>
      </div>
    </>
  )
}
