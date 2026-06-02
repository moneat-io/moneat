/* eslint-disable react-refresh/only-export-components -- build-time prerender module, not HMR-relevant */
//
// Build-time prerender entry. Bundled for Node by scripts/prerender.mjs (vite SSR build, no
// browser required) and never shipped to the client. It produces:
//   - static <head> SEO markup for key marketing routes + blog posts
//   - prerendered article HTML for each blog post (so non-JS crawlers and social/AI scrapers
//     can read the content, not just an empty SPA shell)
//   - the sitemap.xml document
//
// This file imports MDX (via the blog/docs loaders) so it can only run through Vite; it is
// excluded from vitest coverage. The pure logic it composes lives in src/lib/seo (fully tested).
import {renderToStaticMarkup} from 'react-dom/server'
import {allPosts, type Post} from '@/blog/loader'
import {competitorPages} from '@/components/landing/competitorComparisonData'
import {renderHeadTags} from '@/lib/seo/headTags'
import {buildSitemapXml} from '@/lib/seo/sitemap'
import {
  blogPostSeo,
  buildSitemapEntries,
  competitorPageSeo,
  FEATURE_PAGE_SEO_INPUTS,
  featurePageSeo,
  STATIC_SEO_PAGES,
} from '@/lib/seo/routes'

export interface PrerenderedRoute {
  /** Site-root-relative path, e.g. '/blog/my-post'. */
  path: string
  /** SEO <head> markup to inject before </head>. */
  head: string
  /** Optional prerendered body HTML to inject into the empty #root container. */
  bodyHtml?: string
}

/** Format an ISO date as e.g. "February 15, 2026" (UTC), matching the blog route's display. */
function formatDate(date: string): string {
  return new Date(date).toLocaleDateString('en-US', {
    year: 'numeric',
    month: 'long',
    day: 'numeric',
    timeZone: 'UTC',
  })
}

/** Mirrors the visible <article> in src/routes/blog.$slug.tsx so prerendered HTML matches the SPA. */
function BlogArticleBody({post}: {readonly post: Post}) {
  const {Component} = post
  return (
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
          <time dateTime={post.date}>{formatDate(post.date)}</time>
          <span>&middot;</span>
          <span>{post.readingTime}</span>
        </div>
      </header>
      <div className="prose prose-invert max-w-none prose-headings:text-white prose-p:text-slate-300 prose-li:text-slate-300 prose-strong:text-white prose-a:text-indigo-300 hover:prose-a:text-indigo-200 prose-th:text-white prose-td:text-slate-300 prose-table:border-collapse prose-th:border prose-th:border-white/10 prose-th:bg-white/[0.04] prose-th:px-3 prose-th:py-2 prose-td:border prose-td:border-white/[0.06] prose-td:px-3 prose-td:py-2 prose-code:font-brandmono prose-code:text-slate-200 prose-pre:bg-[#07080e] prose-pre:border prose-pre:border-white/10">
        <Component />
      </div>
    </article>
  )
}

/** Routes that get static SEO <head> (and, for blog posts, prerendered body HTML). */
export function getPrerenderRoutes(): PrerenderedRoute[] {
  const routes: PrerenderedRoute[] = STATIC_SEO_PAGES.map((seo) => ({
    path: seo.path,
    head: renderHeadTags(seo),
  }))
  for (const page of FEATURE_PAGE_SEO_INPUTS) {
    routes.push({path: `/${page.slug}`, head: renderHeadTags(featurePageSeo(page))})
  }
  for (const page of competitorPages) {
    routes.push({path: page.route, head: renderHeadTags(competitorPageSeo(page))})
  }
  for (const post of allPosts) {
    routes.push({
      path: `/blog/${post.slug}`,
      head: renderHeadTags(blogPostSeo(post)),
      bodyHtml: renderToStaticMarkup(<BlogArticleBody post={post} />),
    })
  }
  return routes
}

/** Build the sitemap.xml document covering all indexable marketing/content routes. */
export function getSitemapXml(buildDate: string = new Date().toISOString().slice(0, 10)): string {
  return buildSitemapXml(
    buildSitemapEntries({
      posts: allPosts.map((post) => ({slug: post.slug, date: post.date})),
      competitors: competitorPages.map((page) => ({route: page.route})),
      buildDate,
    }),
  )
}
