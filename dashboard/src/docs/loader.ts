export interface DocMeta {
  slug: string
  title: string
  description?: string
}

export interface Doc extends DocMeta {
  // eslint-disable-next-line @typescript-eslint/no-explicit-any
  Component: React.ComponentType<any>
}

type RawModule = {
  default: React.ComponentType
  frontmatter?: {title?: string; description?: string}
}

const mdxModules = import.meta.glob<RawModule>('./pages/**/*.mdx', {eager: true})
const mdModules = import.meta.glob<RawModule>('./pages/**/*.md', {eager: true})
const modules: Record<string, RawModule> = {...mdxModules, ...mdModules}

function slugFromPath(path: string): string {
  return path
    .replace('./pages/', '')
    .replace(/\.mdx?$/, '')
    .replace(/\/index$/, '')
}

export const allDocs: Doc[] = Object.entries(modules).map(([path, mod]) => ({
  slug: slugFromPath(path),
  title: mod.frontmatter?.title ??
    (slugFromPath(path).split('/').pop() ?? '')
      .replace(/-/g, ' ')
      .replace(/\b\w/g, (c) => c.toUpperCase()),
  description: mod.frontmatter?.description,
  Component: mod.default,
}))

// Detect duplicate slugs before building the map
const slugCounts = new Map<string, string[]>()
for (const doc of allDocs) {
  const titles = slugCounts.get(doc.slug) ?? []
  titles.push(doc.title)
  slugCounts.set(doc.slug, titles)
}
for (const [slug, titles] of slugCounts) {
  if (titles.length > 1) {
    console.error(`[docs/loader] Duplicate slug "${slug}": ${titles.join(', ')}`)
  }
}

const docMap = new Map(allDocs.map((d) => [d.slug, d]))

export function getDoc(slug: string): Doc | undefined {
  return docMap.get(slug)
}
