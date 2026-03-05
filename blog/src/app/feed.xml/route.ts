import { allPosts } from 'contentlayer/generated'

export const dynamic = 'force-static'

export function GET() {
  const posts = allPosts
    .filter((p) => p.published !== false)
    .sort((a, b) => new Date(b.date).getTime() - new Date(a.date).getTime())

  const xml = `<?xml version="1.0" encoding="UTF-8"?>
<rss version="2.0" xmlns:atom="http://www.w3.org/2005/Atom">
  <channel>
    <title>Moneat Blog</title>
    <link>https://moneat.io/blog</link>
    <description>Engineering deep-dives, observability best practices, and product updates from the Moneat team.</description>
    <language>en-us</language>
    <atom:link href="https://moneat.io/blog/feed.xml" rel="self" type="application/rss+xml"/>
    ${posts
      .map(
        (post) => `
    <item>
      <title><![CDATA[${post.title}]]></title>
      <link>https://moneat.io${post.url}</link>
      <guid>https://moneat.io${post.url}</guid>
      <pubDate>${new Date(post.date).toUTCString()}</pubDate>
      <description><![CDATA[${post.description}]]></description>
      ${post.author ? `<author>${post.author}</author>` : ''}
    </item>`
      )
      .join('')}
  </channel>
</rss>`

  return new Response(xml, {
    headers: {
      'Content-Type': 'application/xml',
    },
  })
}
