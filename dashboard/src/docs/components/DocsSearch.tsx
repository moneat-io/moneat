import {createContext, useCallback, useContext, useEffect, useMemo, useState, type ReactNode} from 'react'
import {useNavigate} from '@tanstack/react-router'
import {
  CommandDialog,
  CommandEmpty,
  CommandGroup,
  CommandInput,
  CommandItem,
  CommandList,
} from '@/components/ui/command'
import {docsSidebar, type SidebarItem} from '../sidebar'
import {getDoc} from '../loader'

interface SearchEntry {
  slug: string
  title: string
}

interface SearchGroup {
  label: string
  entries: SearchEntry[]
}

type DocsSearchProviderProps = Readonly<{
  children: ReactNode
}>

function titleFor(slug: string): string {
  const doc = getDoc(slug)
  if (doc?.title) return doc.title
  const last = slug.split('/').pop() ?? slug
  return last.replaceAll('-', ' ').replaceAll(/\b\w/g, (c) => c.toUpperCase())
}

// Flatten the real sidebar tree into search groups so results mirror the nav.
function buildGroups(): SearchGroup[] {
  const collect = (item: SidebarItem, into: SearchEntry[]) => {
    if (typeof item === 'string') {
      into.push({slug: item, title: titleFor(item)})
      return
    }
    if (item.link) into.push({slug: item.link, title: item.label})
    item.items.forEach((child) => collect(child, into))
  }
  return docsSidebar.map((category) => {
    const entries: SearchEntry[] = []
    category.items.forEach((item) => collect(item, entries))
    return {label: category.label, entries}
  })
}

const GROUPS = buildGroups()

const DocsSearchContext = createContext<{open: () => void}>({open: () => {}})

// eslint-disable-next-line react-refresh/only-export-components -- hook + provider colocated for one search surface
export function useDocsSearch() {
  return useContext(DocsSearchContext)
}

export function DocsSearchProvider({children}: DocsSearchProviderProps) {
  const [open, setOpen] = useState(false)
  const navigate = useNavigate()

  useEffect(() => {
    const onKey = (e: KeyboardEvent) => {
      if ((e.key === 'k' || e.key === 'K') && (e.metaKey || e.ctrlKey)) {
        e.preventDefault()
        setOpen((prev) => !prev)
      }
    }
    document.addEventListener('keydown', onKey)
    return () => document.removeEventListener('keydown', onKey)
  }, [])

  const go = useCallback(
    (slug: string) => {
      setOpen(false)
      if (slug === 'intro') navigate({to: '/docs'})
      else navigate({to: '/docs/$', params: {_splat: slug}})
    },
    [navigate],
  )

  const value = useMemo(() => ({open: () => setOpen(true)}), [])

  return (
    <DocsSearchContext.Provider value={value}>
      {children}
      <CommandDialog open={open} onOpenChange={setOpen}>
        <CommandInput placeholder="Search the docs — try “send OTLP traces”…" />
        <CommandList>
          <CommandEmpty>No matching pages.</CommandEmpty>
          {GROUPS.map((group) => (
            <CommandGroup key={group.label} heading={group.label}>
              {group.entries.map((entry) => (
                <CommandItem
                  key={entry.slug}
                  value={`${entry.title} ${entry.slug}`}
                  onSelect={() => go(entry.slug)}
                >
                  <span className="truncate">{entry.title}</span>
                  <span className="ml-auto font-brandmono text-[11px] text-muted-foreground">{entry.slug}</span>
                </CommandItem>
              ))}
            </CommandGroup>
          ))}
        </CommandList>
      </CommandDialog>
    </DocsSearchContext.Provider>
  )
}
