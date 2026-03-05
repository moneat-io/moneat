import { allPosts } from 'contentlayer/generated'
import { notFound } from 'next/navigation'
import { useMDXComponent } from 'next-contentlayer2/hooks'
import { useMDXComponents } from '@/components/mdx-components'
import { generatePostJsonLd } from '@/lib/seo'
import type { Metadata } from 'next'

type Params = Promise<{ slug: string }>

export async function generateStaticParams() {
  return allPosts
    .filter((p) => p.published !== false)
    .map((post) => ({ slug: post.slug }))
}

export async function generateMetadata({
  params,
}: {
  params: Params
}): Promise<Metadata> {
  const { slug } = await params
  const post = allPosts.find((p) => p.slug === slug)
  if (!post) return {}

  return {
    title: post.title,
    description: post.description,
    openGraph: {
      type: 'article',
      title: post.title,
      description: post.description,
      url: `https://moneat.io${post.url}`,
      publishedTime: post.date,
      authors: [post.author],
      ...(post.image && { images: [{ url: post.image }] }),
    },
    twitter: {
      card: 'summary_large_image',
      title: post.title,
      description: post.description,
      ...(post.image && { images: [post.image] }),
    },
    alternates: {
      canonical: `https://moneat.io${post.url}`,
    },
  }
}

function PostContent({ code }: { code: string }) {
  const MDXContent = useMDXComponent(code)
  const components = useMDXComponents({})
  return <MDXContent components={components} />
}

export default async function PostPage({
  params,
}: {
  params: Params
}) {
  const { slug } = await params
  const post = allPosts.find((p) => p.slug === slug)
  if (!post) notFound()

  const jsonLd = generatePostJsonLd(post)

  return (
    <>
      <script
        type="application/ld+json"
        dangerouslySetInnerHTML={{ __html: JSON.stringify(jsonLd) }}
      />
      <article className="max-w-3xl mx-auto px-4 sm:px-6 lg:px-8 py-16">
        <header className="mb-10">
          <div className="flex flex-wrap gap-2 mb-4">
            {post.tags?.map((tag) => (
              <span
                key={tag}
                className="inline-block text-xs font-medium text-sky-400 bg-sky-500/10 px-2 py-0.5 rounded-full"
              >
                {tag}
              </span>
            ))}
          </div>
          <h1 className="text-3xl sm:text-4xl font-bold text-white mb-4">
            {post.title}
          </h1>
          <div className="flex items-center gap-3 text-sm text-slate-400">
            <span>{post.author}</span>
            <span>&middot;</span>
            <time dateTime={post.date}>
              {new Date(post.date).toLocaleDateString('en-US', {
                year: 'numeric',
                month: 'long',
                day: 'numeric',
              })}
            </time>
            <span>&middot;</span>
            <span>{post.readingTime}</span>
          </div>
        </header>
        <div className="prose prose-invert prose-sky max-w-none prose-headings:text-white prose-p:text-slate-300 prose-li:text-slate-300 prose-strong:text-white prose-th:text-slate-200 prose-td:text-slate-300">
          <PostContent code={post.body.code} />
        </div>
        <footer className="mt-16 pt-8 border-t border-slate-800">
          <a
            href="/blog"
            className="text-sm text-sky-400 hover:text-sky-300 transition-colors"
          >
            &larr; Back to all posts
          </a>
        </footer>
      </article>
    </>
  )
}
