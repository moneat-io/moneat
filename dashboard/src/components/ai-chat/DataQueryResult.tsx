// Moneat - Mobile-First Error Monitoring Platform
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

import {useState, useEffect} from 'react'
import type {AiDataQuery} from '@/lib/api'
import {Database, Loader2} from 'lucide-react'

interface DataQueryResultProps {
  query: AiDataQuery
}

export function DataQueryResult({query}: DataQueryResultProps) {
  const [loading, setLoading] = useState(true)
  const [error, setError] = useState<string | null>(null)
  const [result, setResult] = useState<any>(null)

  useEffect(() => {
    const executeQuery = async () => {
      try {
        setLoading(true)
        setError(null)
        
        // Build the full URL with query params
        const url = new URL(query.endpoint, window.location.origin)
        if (query.params) {
          Object.entries(query.params).forEach(([key, value]) => {
            url.searchParams.append(key, value)
          })
        }
        
        // Execute the query using credentials (httpOnly cookie auth)
        const response = await fetch(url.pathname + url.search, {
          headers: {
            'Content-Type': 'application/json',
          },
          credentials: 'include',
        })
        
        if (!response.ok) {
          throw new Error(`Failed to fetch data: ${response.statusText}`)
        }
        
        const data = await response.json()
        setResult(data)
      } catch (err) {
        setError(err instanceof Error ? err.message : 'Failed to execute query')
      } finally {
        setLoading(false)
      }
    }
    
    executeQuery()
  }, [query])

  return (
    <div className="ml-9 mt-2 border border-border rounded-lg p-3 bg-card text-sm">
      <div className="flex items-center gap-1.5 text-xs font-medium mb-2">
        <Database className="h-3 w-3" />
        <span>{query.description}</span>
      </div>
      
      {loading && (
        <div className="flex items-center gap-2 text-xs text-muted-foreground">
          <Loader2 className="h-3 w-3 animate-spin" />
          <span>Loading...</span>
        </div>
      )}
      
      {error && (
        <div className="text-xs text-red-600">
          {error}
        </div>
      )}
      
      {!loading && !error && result && (
        <div className="text-xs">
          <pre className="bg-muted/50 p-2 rounded overflow-auto max-h-48">
            {JSON.stringify(result, null, 2)}
          </pre>
        </div>
      )}
    </div>
  )
}
