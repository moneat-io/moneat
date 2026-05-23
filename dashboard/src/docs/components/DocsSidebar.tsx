import {useState} from 'react'
import {Link, useRouterState} from '@tanstack/react-router'
import {ChevronRight} from 'lucide-react'
import {docsSidebar, type SidebarCategory, type SidebarItem} from '../sidebar'
import {getDoc} from '../loader'

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

function SidebarItemView({item, currentSlug, depth = 0}: {item: SidebarItem; currentSlug: string; depth?: number}) {
  if (typeof item === 'string') {
    const isActive = currentSlug === item
    return (
      <Link
        to={item === 'intro' ? '/docs' : '/docs/$'}
        params={item === 'intro' ? {} : {_splat: item}}
        className={`block rounded-md px-3 py-1.5 text-sm transition-colors ${
          isActive
            ? 'bg-slate-950 text-white font-medium'
            : 'text-slate-600 hover:bg-slate-100 hover:text-slate-950'
        }`}
        style={{paddingLeft: `${12 + depth * 12}px`}}
      >
        {getTitle(item)}
      </Link>
    )
  }

  return <CategoryView category={item} currentSlug={currentSlug} depth={depth} />
}

function CategoryView({category, currentSlug, depth = 0}: {category: SidebarCategory; currentSlug: string; depth?: number}) {
  const hasActive = isActiveOrAncestor(currentSlug, category)
  // Track user-toggled state separately; hasActive always forces open
  const [userOpen, setUserOpen] = useState(!category.collapsed)
  const open = hasActive || userOpen
  const panelId = `docs-panel-${category.label.toLowerCase().replace(/[^a-z0-9]+/g, '-')}`

  return (
    <div>
      <div
        className="flex items-center rounded-md text-sm font-medium text-slate-700 transition-colors hover:text-slate-950"
        style={{paddingLeft: `${12 + depth * 12}px`}}
      >
        <button
          type="button"
          onClick={() => setUserOpen((v) => !v)}
          className="flex shrink-0 items-center justify-center rounded p-1.5 hover:bg-slate-100"
          aria-label={open ? 'Collapse section' : 'Expand section'}
          aria-expanded={open}
          aria-controls={panelId}
        >
          <ChevronRight
            className={`size-3.5 transition-transform duration-200 ${open ? 'rotate-90' : ''}`}
          />
        </button>
        {category.link ? (
          <Link
            to="/docs/$"
            params={{_splat: category.link}}
            className={`flex-1 py-1.5 pr-3 text-left ${
              currentSlug === category.link || currentSlug.startsWith(`${category.link}/`)
                ? 'text-slate-950'
                : ''
            }`}
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

export default function DocsSidebar() {
  const pathname = useRouterState({select: (s) => s.location.pathname})
  const currentSlug = pathname.replace(/^\/docs\/?/, '').replace(/\/$/, '') || 'intro'

  return (
    <nav className="sticky top-16 hidden h-[calc(100vh-4rem)] w-64 shrink-0 overflow-y-auto border-r border-slate-200 py-6 pr-3 [scrollbar-color:#cbd5e1_transparent] [scrollbar-width:thin] lg:block [&::-webkit-scrollbar]:w-1.5 [&::-webkit-scrollbar-thumb]:rounded-full [&::-webkit-scrollbar-thumb]:bg-slate-300 [&::-webkit-scrollbar-track]:bg-transparent">
      <div className="space-y-1">
        {docsSidebar.map((category, i) => (
          <div key={i} className="mb-3">
            <CategoryView category={category} currentSlug={currentSlug} />
          </div>
        ))}
      </div>
    </nav>
  )
}
