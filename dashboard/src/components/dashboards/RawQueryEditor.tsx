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

import {Label} from '@/components/ui/label'
import {Textarea} from '@/components/ui/textarea'
import {AlertTriangle} from 'lucide-react'

interface RawQueryEditorProps {
  query: string | undefined
  onChange: (query: string) => void
  dataSource: string
}

export function RawQueryEditor({query, onChange, dataSource}: RawQueryEditorProps) {
  const isPrometheus = dataSource === '__prometheus' || dataSource.includes('prometheus')
  const isUnmapped = dataSource.startsWith('__')

  return (
    <div className="space-y-3">
      {isUnmapped && (
        <div className="rounded-md border border-warning-border bg-warning-bg p-3">
          <div className="flex items-center gap-2 text-warning-fg text-xs font-medium mb-1">
            <AlertTriangle className="h-3.5 w-3.5" />
            Unmapped Data Source
          </div>
          <p className="text-xs text-warning-fg/90">
            This widget references a data source that needs to be mapped. Select a custom data source above to execute this query.
          </p>
        </div>
      )}

      <div>
        <Label htmlFor="raw-query" className="text-xs">
          Raw Query
          {isPrometheus && <span className="text-muted-foreground ml-1">(PromQL)</span>}
        </Label>
        <Textarea
          id="raw-query"
          value={query || ''}
          onChange={(e) => onChange(e.target.value)}
          placeholder={
            isPrometheus
              ? 'rate(http_requests_total{status="200"}[5m])'
              : 'SELECT count() FROM events WHERE level = \'error\''
          }
          className="font-mono text-xs min-h-[120px] mt-1.5"
        />
        <p className="text-muted-foreground text-xs mt-1.5">
          {isPrometheus
            ? 'Enter a PromQL expression. Supports functions like rate(), sum(), avg(), and label filters.'
            : 'Enter a raw SQL query. Use the schema explorer to find available fields.'}
        </p>
      </div>
    </div>
  )
}
