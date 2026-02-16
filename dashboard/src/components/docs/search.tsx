// Moneat - observability platform
// Copyright (C) 2026 Moneat
//
// This program is free software: you can redistribute it and/or modify
// it under the terms of the GNU Affero General Public License as published by
// the Free Software Foundation, either version 3 of the License, or
// (at your option) any later version.
//
// This program is distributed in the hope that it will be useful,
// but WITHOUT ANY WARRANTY; without even the implied warranty of
// MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the
// GNU Affero General Public License for more details.
//
// You should have received a copy of the GNU Affero General Public License
// along with this program. If not, see <https://www.gnu.org/licenses/>.

import {useState, useEffect, useRef, useMemo} from 'react'
import {useNavigate} from '@tanstack/react-router'
import {Search, X} from 'lucide-react'
import {cn} from '@/lib/utils'

interface SearchableDoc {
  title: string
  description: string
  href: string
  icon: string
  category: string
  content?: string
}

// All searchable documentation pages
const searchableDocs: SearchableDoc[] = [
  {
    title: 'Introduction',
    description: 'Moneat is a Sentry-compatible observability platform for error monitoring, incident management, uptime tracking, and structured logging.',
    href: '/docs',
    icon: '📖',
    category: 'Overview',
  },
  {
    title: 'Getting Started',
    description: 'Quick start guide to set up Moneat and start monitoring your applications in minutes.',
    href: '/docs/getting-started',
    icon: '🚀',
    category: 'Overview',
  },
  {
    title: 'Error Monitoring',
    description: 'Capture and track errors in real-time using Sentry-compatible SDKs. Monitor exceptions, crashes, and unhandled errors.',
    href: '/docs/error-monitoring',
    icon: '🐛',
    category: 'Core Features',
  },
  {
    title: 'Issue Tracking',
    description: 'Group, triage, and resolve issues with automatic fingerprinting. Track error trends and assign issues to team members.',
    href: '/docs/issue-tracking',
    icon: '✓',
    category: 'Core Features',
  },
  {
    title: 'Structured Logging',
    description: 'Ingest, search, and tail logs via OTLP and WebSocket. Query logs with filters and real-time streaming.',
    href: '/docs/logging',
    icon: '💻',
    category: 'Core Features',
  },
  {
    title: 'Releases & Source Maps',
    description: 'Track deployments and upload source maps for readable stack traces. Link errors to specific releases.',
    href: '/docs/releases',
    icon: '📦',
    category: 'Core Features',
  },
  {
    title: 'On-Call & Incidents',
    description: 'Set up schedules, escalation policies, and manage incidents. Configure rotations and notifications.',
    href: '/docs/on-call',
    icon: '🔔',
    category: 'Reliability',
  },
  {
    title: 'Uptime Monitoring',
    description: 'Monitor your endpoints with HTTP checks and heartbeats. Track uptime, latency, and response times.',
    href: '/docs/uptime-monitoring',
    icon: '📊',
    category: 'Reliability',
  },
  {
    title: 'Status Pages',
    description: 'Create public status pages to communicate incidents to users. Show real-time status and incident history.',
    href: '/docs/status-pages',
    icon: '🌐',
    category: 'Reliability',
  },
  {
    title: 'SDK Setup',
    description: 'Install and configure Sentry-compatible SDKs for JavaScript, Python, Java, Go, Ruby, and more.',
    href: '/docs/sdk-setup',
    icon: '⚙️',
    category: 'Configuration',
  },
  {
    title: 'Integrations',
    description: 'Connect Slack, Discord, and webhooks for notifications. Set up third-party integrations.',
    href: '/docs/integrations',
    icon: '🔌',
    category: 'Configuration',
  },
  {
    title: 'SSO & Authentication',
    description: 'Set up OAuth and SSO providers. Configure Google, GitHub, and SAML authentication.',
    href: '/docs/sso-authentication',
    icon: '🛡️',
    category: 'Configuration',
  },
  {
    title: 'API Tokens',
    description: 'Create and manage API tokens. Use tokens for programmatic access to the Moneat API.',
    href: '/docs/api-tokens',
    icon: '🔑',
    category: 'Configuration',
  },
  {
    title: 'Billing & Plans',
    description: 'Plans, usage, and billing management. Understand pricing tiers and manage your subscription.',
    href: '/docs/billing',
    icon: '💳',
    category: 'Account',
  },
]

export function DocsSearch() {
  const [query, setQuery] = useState('')
  const [isOpen, setIsOpen] = useState(false)
  const [selectedIndex, setSelectedIndex] = useState(0)
  const inputRef = useRef<HTMLInputElement>(null)
  const dropdownRef = useRef<HTMLDivElement>(null)
  const navigate = useNavigate()

  // Filter and rank results
  const results = useMemo(() => {
    if (!query.trim()) return []

    const searchTerm = query.toLowerCase()
    
    return searchableDocs
      .map((doc) => {
        let score = 0
        const titleLower = doc.title.toLowerCase()
        const descLower = doc.description.toLowerCase()
        const categoryLower = doc.category.toLowerCase()

        // Exact title match (highest priority)
        if (titleLower === searchTerm) score += 100
        // Title starts with query
        else if (titleLower.startsWith(searchTerm)) score += 50
        // Title contains query
        else if (titleLower.includes(searchTerm)) score += 30

        // Description contains query
        if (descLower.includes(searchTerm)) score += 20

        // Category match
        if (categoryLower.includes(searchTerm)) score += 10

        return {doc, score}
      })
      .filter(({score}) => score > 0)
      .sort((a, b) => b.score - a.score)
      .slice(0, 8)
      .map(({doc}) => doc)
  }, [query])

  // Handle keyboard navigation
  useEffect(() => {
    const handleKeyDown = (e: KeyboardEvent) => {
      if (!isOpen) return

      if (e.key === 'ArrowDown') {
        e.preventDefault()
        setSelectedIndex((prev) => (prev + 1) % results.length)
      } else if (e.key === 'ArrowUp') {
        e.preventDefault()
        setSelectedIndex((prev) => (prev - 1 + results.length) % results.length)
      } else if (e.key === 'Enter' && results[selectedIndex]) {
        e.preventDefault()
        navigate({to: results[selectedIndex].href})
        setIsOpen(false)
        setQuery('')
      } else if (e.key === 'Escape') {
        setIsOpen(false)
        inputRef.current?.blur()
      }
    }

    document.addEventListener('keydown', handleKeyDown)
    return () => document.removeEventListener('keydown', handleKeyDown)
  }, [isOpen, results, selectedIndex, navigate])

  // Close dropdown when clicking outside
  useEffect(() => {
    const handleClickOutside = (e: MouseEvent) => {
      if (
        dropdownRef.current &&
        !dropdownRef.current.contains(e.target as Node) &&
        !inputRef.current?.contains(e.target as Node)
      ) {
        setIsOpen(false)
      }
    }

    document.addEventListener('mousedown', handleClickOutside)
    return () => document.removeEventListener('mousedown', handleClickOutside)
  }, [])

  // Reset selected index when results change
  useEffect(() => {
    setSelectedIndex(0)
  }, [results])

  return (
    <div className="relative">
      <div className="relative">
        <Search className="absolute left-3 top-1/2 -translate-y-1/2 h-4 w-4 text-muted-foreground" />
        <input
          ref={inputRef}
          type="text"
          placeholder="Search docs..."
          value={query}
          onChange={(e) => {
            setQuery(e.target.value)
            setIsOpen(true)
          }}
          onFocus={() => query && setIsOpen(true)}
          className={cn(
            'w-full md:w-64 h-9 pl-9 pr-9 text-sm rounded-md border bg-background',
            'focus:outline-none focus:ring-2 focus:ring-primary/20',
            'transition-all'
          )}
        />
        {query && (
          <button
            onClick={() => {
              setQuery('')
              setIsOpen(false)
              inputRef.current?.focus()
            }}
            className="absolute right-2 top-1/2 -translate-y-1/2 p-1 hover:bg-accent rounded"
          >
            <X className="h-3 w-3 text-muted-foreground" />
          </button>
        )}
      </div>

      {/* Results Dropdown */}
      {isOpen && results.length > 0 && (
        <div
          ref={dropdownRef}
          className="absolute top-full mt-2 w-full md:w-96 bg-background border rounded-lg shadow-lg max-h-96 overflow-y-auto z-50"
        >
          <div className="p-2">
            <div className="text-xs text-muted-foreground px-3 py-2">
              {results.length} result{results.length !== 1 ? 's' : ''}
            </div>
            {results.map((doc, index) => (
              <button
                key={doc.href}
                onClick={() => {
                  navigate({to: doc.href})
                  setIsOpen(false)
                  setQuery('')
                }}
                onMouseEnter={() => setSelectedIndex(index)}
                className={cn(
                  'w-full text-left px-3 py-2.5 rounded-md transition-colors',
                  index === selectedIndex
                    ? 'bg-accent'
                    : 'hover:bg-accent/50'
                )}
              >
                <div className="flex items-start gap-3">
                  <span className="text-lg shrink-0 mt-0.5">{doc.icon}</span>
                  <div className="flex-1 min-w-0">
                    <div className="text-sm font-medium">{doc.title}</div>
                    <div className="text-xs text-muted-foreground mt-0.5 line-clamp-2">
                      {doc.description}
                    </div>
                    <div className="text-xs text-muted-foreground/70 mt-1">
                      {doc.category}
                    </div>
                  </div>
                </div>
              </button>
            ))}
          </div>
        </div>
      )}

      {/* No Results */}
      {isOpen && query && results.length === 0 && (
        <div
          ref={dropdownRef}
          className="absolute top-full mt-2 w-full md:w-96 bg-background border rounded-lg shadow-lg p-4 z-50"
        >
          <p className="text-sm text-muted-foreground text-center">
            No results found for "{query}"
          </p>
        </div>
      )}
    </div>
  )
}
