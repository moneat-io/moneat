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

import {useState} from 'react'
import {Search} from 'lucide-react'
import {groupVendors, VENDORS} from './dataSourceCatalog'
import {DataSourceLogo} from './DataSourceLogo'

interface DataSourcePickerStepProps {
  readonly onPick: (vendorKey: string) => void
}

/** Step 1 — searchable, categorized grid of every connectable data source. */
export function DataSourcePickerStep({onPick}: DataSourcePickerStepProps) {
  const [query, setQuery] = useState('')
  const groups = groupVendors(query)

  return (
    <div>
      <div className="focus-within:border-ring focus-within:ring-ring/20 mb-4 flex h-8 items-center gap-2 rounded-md border border-input bg-background px-2.5 text-muted-foreground focus-within:ring-2">
        <Search className="h-3.5 w-3.5 shrink-0" />
        <input
          autoFocus
          value={query}
          onChange={(e) => setQuery(e.target.value)}
          onKeyDown={(e) => {
            if (e.key === 'Escape' && query) {
              e.stopPropagation()
              setQuery('')
            }
          }}
          placeholder={`Search ${VENDORS.length} data sources — Postgres, Prometheus, ClickHouse…`}
          aria-label="Search data sources"
          className="min-w-0 flex-1 border-0 bg-transparent text-sm text-foreground outline-none placeholder:text-muted-foreground/70"
        />
      </div>

      {groups.length === 0 ? (
        <div className="py-10 text-center text-sm text-muted-foreground">
          No data source matches “{query}”.
        </div>
      ) : (
        groups.map((group) => (
          <div key={group.category} className="mb-3 last:mb-0">
            <div className="mb-2 text-[0.6875rem] font-bold uppercase tracking-wider text-muted-foreground/80">
              {group.label}
            </div>
            <div className="grid grid-cols-2 gap-2 sm:grid-cols-3">
              {group.vendors.map((v) => (
                <button
                  key={v.key}
                  type="button"
                  onClick={() => onPick(v.key)}
                  className="hover:border-ring flex items-center gap-2.5 rounded-md border border-border bg-card p-2 text-left transition-colors hover:bg-accent/40"
                >
                  <DataSourceLogo type={v.key} size={26} />
                  <div className="min-w-0">
                    <div className="truncate text-sm font-semibold text-foreground">{v.label}</div>
                    <div className="truncate text-[10px] text-muted-foreground">{v.blurb}</div>
                  </div>
                </button>
              ))}
            </div>
          </div>
        ))
      )}
    </div>
  )
}
