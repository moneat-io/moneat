declare module '*.mdx' {
  import type {ComponentType} from 'react'

  interface Frontmatter {
    title: string
    description: string
    date: string
    author: string
    tags?: string[]
    image?: string
    published?: boolean
    readingTime: string
  }

  export const frontmatter: Frontmatter
  const MDXComponent: ComponentType
  export default MDXComponent
}
