import {useState} from 'react'
import {Link, useRouterState} from '@tanstack/react-router'
import {ChevronRight, Search} from 'lucide-react'
import {docsSidebar, type SidebarCategory, type SidebarItem} from '../sidebar'
import {getDoc} from '../loader'
import {useDocsSearch} from './DocsSearch'

function getTitle(slug: string): string {
  const doc = getDoc(slug)
  if (doc?.title) return doc.title
  const last = slug.split('/').pop() ?? slug
  return last
    .replace(/-/g, ' ')
    .replace(/\b\w/g, (c) => c.toUpperCase())
}

function isActiveOrAncestor(currentSlug: string, item: SidebarItem): boolean {
  if (typeof item === 'string') return item === currentSlug
  if (item.link === currentSlug) return true
  return item.items.some((child) => isActiveOrAncestor(currentSlug, child))
}

function LeafLink({slug, currentSlug, depth}: {slug: string; currentSlug: string; depth: number}) {
  const isActive = currentSlug === slug
  const paddingLeft = `${12 + depth * 12}px`
  const linkProps =
    slug === 'intro'
      ? ({to: '/docs'} as const)
      : ({to: '/docs/$', params: {_splat: slug}} as const)

  if (isActive) {
    return (
      <Link
        {...linkProps}
        className="relative flex items-center rounded-md py-1.5 pr-3 text-[13px] font-medium"
        style={{paddingLeft}}
      >
        <span className="absolute bottom-1.5 left-2 top-1.5 w-0.5 rounded-full bg-gradient-to-b from-indigo-500 to-cyan-400" />
        <span className="bg-gradient-to-r from-indigo-300 via-indigo-400 to-cyan-400 bg-clip-text text-transparent">
          {getTitle(slug)}
        </span>
      </Link>
    )
  }

  return (
    <Link
      {...linkProps}
      className="flex items-center rounded-md py-1.5 pr-3 text-[13px] text-slate-400 transition-colors hover:bg-white/[0.04] hover:text-slate-100"
      style={{paddingLeft}}
    >
      {getTitle(slug)}
    </Link>
  )
}

function NestedCategory({category, currentSlug, depth}: {category: SidebarCategory; currentSlug: string; depth: number}) {
  const hasActive = isActiveOrAncestor(currentSlug, category)
  const [userOpen, setUserOpen] = useState(!category.collapsed)
  const open = hasActive || userOpen
  const panelId = `docs-panel-${category.label.toLowerCase().replace(/[^a-z0-9]+/g, '-')}`
  const paddingLeft = `${12 + depth * 12}px`
  const linkActive = currentSlug === category.link || currentSlug.startsWith(`${category.link}/`)

  return (
    <div>
      <div
        className="flex items-center rounded-md text-[13px] text-slate-400 transition-colors hover:text-slate-200"
        style={{paddingLeft}}
      >
        <button
          type="button"
          onClick={() => setUserOpen((v) => !v)}
          className="flex shrink-0 items-center justify-center rounded p-1 text-slate-500 hover:bg-white/[0.04] hover:text-slate-300"
          aria-label={open ? 'Collapse section' : 'Expand section'}
          aria-expanded={open}
          aria-controls={panelId}
        >
          <ChevronRight className={`size-3.5 transition-transform duration-200 ${open ? 'rotate-90' : ''}`} />
        </button>
        {category.link ? (
          <Link
            to="/docs/$"
            params={{_splat: category.link}}
            className={`flex-1 py-1.5 pr-3 text-left ${linkActive ? 'text-indigo-300' : ''}`}
          >
            {category.label}
          </Link>
        ) : (
          <button type="button" onClick={() => setUserOpen((v) => !v)} className="flex-1 py-1.5 pr-3 text-left">
            {category.label}
          </button>
        )}
      </div>
      {open && (
        <div id={panelId} className="mt-0.5">
          {category.items.map((child, i) => (
            <SidebarItemView key={i} item={child} currentSlug={currentSlug} depth={depth + 1} />
          ))}
        </div>
      )}
    </div>
  )
}

function SidebarItemView({item, currentSlug, depth}: {item: SidebarItem; currentSlug: string; depth: number}) {
  if (typeof item === 'string') return <LeafLink slug={item} currentSlug={currentSlug} depth={depth} />
  return <NestedCategory category={item} currentSlug={currentSlug} depth={depth} />
}

// Top-level sections render as mono uppercase headers with their items below.
function TopCategory({category, currentSlug}: {category: SidebarCategory; currentSlug: string}) {
  const hasActive = isActiveOrAncestor(currentSlug, category)
  const [userOpen, setUserOpen] = useState(!category.collapsed)
  const open = hasActive || userOpen
  const panelId = `docs-group-${category.label.toLowerCase().replace(/[^a-z0-9]+/g, '-')}`

  return (
    <div className="mt-3 first:mt-0">
      <button
        type="button"
        onClick={() => setUserOpen((v) => !v)}
        aria-expanded={open}
        aria-controls={panelId}
        className="flex w-full items-center gap-1.5 rounded-md px-3 pb-1 pt-3 font-brandmono text-[10px] font-medium uppercase tracking-[0.16em] text-slate-500 transition-colors hover:text-slate-300"
      >
        <ChevronRight className={`size-3 text-slate-600 transition-transform duration-200 ${open ? 'rotate-90' : ''}`} />
        {category.label}
      </button>
      {open && (
        <div id={panelId} className="mt-0.5">
          {category.items.map((item, i) => (
            <SidebarItemView key={i} item={item} currentSlug={currentSlug} depth={1} />
          ))}
        </div>
      )}
    </div>
  )
}

export default function DocsSidebar() {
  const pathname = useRouterState({select: (s) => s.location.pathname})
  const currentSlug = pathname.replace(/^\/docs\/?/, '').replace(/\/$/, '') || 'intro'
  const {open} = useDocsSearch()

  return (
    <aside className="sticky top-16 hidden h-[calc(100vh-4rem)] w-[16.5rem] shrink-0 flex-col border-r border-white/[0.06] md:flex">
      <div className="px-3.5 pb-3 pt-5">
        <div className="mb-3 px-1 font-brandmono text-[10px] uppercase tracking-[0.18em] text-slate-500">
          Documentation
        </div>
        <button
          type="button"
          onClick={open}
          className="group flex w-full items-center gap-2 rounded-lg border border-white/[0.08] bg-white/[0.02] px-3 py-2 text-left text-[13px] text-slate-500 transition-colors hover:border-white/15 hover:bg-white/[0.04]"
        >
          <Search className="size-4 text-slate-500" />
          <span className="flex-1">Search docs…</span>
          <kbd>⌘K</kbd>
        </button>
      </div>
      <nav className="docs-scroll min-h-0 flex-1 overflow-y-auto px-2.5 pb-10 text-[13px]">
        {docsSidebar.map((category, i) => (
          <TopCategory key={i} category={category} currentSlug={currentSlug} />
        ))}
      </nav>
    </aside>
  )
}
