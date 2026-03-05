export interface PostMeta {
  slug: string
  title: string
  description: string
  date: string
  author: string
  tags?: string[]
  image?: string
  published?: boolean
  readingTime: string
}

export interface Post extends PostMeta {
  // eslint-disable-next-line @typescript-eslint/no-explicit-any
  Component: React.ComponentType<any>
}

type RawModule = {
  default: React.ComponentType
  frontmatter: Omit<PostMeta, 'slug'>
}

const modules = import.meta.glob<RawModule>('./posts/*.mdx', {eager: true})

function slugFromPath(path: string): string {
  return path.replace('./posts/', '').replace('.mdx', '')
}

export const allPosts: Post[] = Object.entries(modules)
  .map(([path, mod]) => ({
    slug: slugFromPath(path),
    ...mod.frontmatter,
    Component: mod.default,
  }))
  .filter((p) => p.published !== false)
  .sort((a, b) => new Date(b.date).getTime() - new Date(a.date).getTime())

export function getPost(slug: string): Post | undefined {
  return allPosts.find((p) => p.slug === slug)
}
