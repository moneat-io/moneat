import { allPosts } from 'contentlayer/generated'
import { PostCard } from '@/components/post-card'
import { generateBlogListJsonLd } from '@/lib/seo'

export default function BlogIndex() {
  const posts = allPosts
    .filter((p) => p.published !== false)
    .sort((a, b) => new Date(b.date).getTime() - new Date(a.date).getTime())

  const jsonLd = generateBlogListJsonLd()

  // Collect all unique tags
  const allTags = Array.from(
    new Set(posts.flatMap((p) => p.tags ?? []))
  ).sort()

  return (
    <>
      <script
        type="application/ld+json"
        dangerouslySetInnerHTML={{ __html: JSON.stringify(jsonLd) }}
      />
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
          {posts.map((post) => (
            <PostCard key={post.slug} post={post} />
          ))}
        </div>

        {posts.length === 0 && (
          <p className="text-slate-500 text-center py-20">
            No posts yet. Check back soon.
          </p>
        )}
      </div>
    </>
  )
}
