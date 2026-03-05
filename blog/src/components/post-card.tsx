import Link from 'next/link'
import type { Post } from 'contentlayer/generated'

export function PostCard({ post }: { post: Post }) {
  return (
    <article className="group rounded-xl border border-slate-800 bg-slate-900/50 p-6 transition-colors hover:border-sky-500/50 hover:bg-slate-900">
      <Link href={post.url} className="block">
        <div className="flex flex-wrap gap-2 mb-3">
          {post.tags?.map((tag) => (
            <span
              key={tag}
              className="inline-block text-xs font-medium text-sky-400 bg-sky-500/10 px-2 py-0.5 rounded-full"
            >
              {tag}
            </span>
          ))}
        </div>
        <h2 className="text-xl font-semibold text-white group-hover:text-sky-400 transition-colors mb-2">
          {post.title}
        </h2>
        <p className="text-sm text-slate-400 line-clamp-2 mb-4">
          {post.description}
        </p>
        <div className="flex items-center gap-3 text-xs text-slate-500">
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
      </Link>
    </article>
  )
}
