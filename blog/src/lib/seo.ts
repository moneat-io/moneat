import type { Post } from 'contentlayer/generated'

export function generatePostJsonLd(post: Post) {
  return {
    '@context': 'https://schema.org',
    '@type': 'BlogPosting',
    headline: post.title,
    description: post.description,
    datePublished: post.date,
    author: {
      '@type': 'Person',
      name: post.author,
    },
    publisher: {
      '@type': 'Organization',
      name: 'Moneat',
      url: 'https://moneat.io',
    },
    url: `https://moneat.io${post.url}`,
    ...(post.image && { image: `https://moneat.io${post.image}` }),
  }
}

export function generateBlogListJsonLd() {
  return {
    '@context': 'https://schema.org',
    '@type': 'Blog',
    name: 'Moneat Blog',
    description:
      'Engineering deep-dives, observability best practices, and product updates from the Moneat team.',
    url: 'https://moneat.io/blog',
    publisher: {
      '@type': 'Organization',
      name: 'Moneat',
      url: 'https://moneat.io',
    },
  }
}
