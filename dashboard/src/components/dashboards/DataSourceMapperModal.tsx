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
import {Button} from '@/components/ui/button'
import {AlertTriangle} from 'lucide-react'
import {DataSourcePicker} from './DataSourcePicker'
import type {CreateWidgetRequest, DataSourceInfo} from '@/lib/api'

interface DataSourceMapperModalProps {
  open: boolean
  widget: CreateWidgetRequest | null
  unknownSources: string[]
  dataSources: DataSourceInfo[]
  onConfirm: (widget: CreateWidgetRequest) => void
  onCancel: () => void
}

export function DataSourceMapperModal({
  open,
  widget,
  unknownSources,
  dataSources,
  onConfirm,
  onCancel,
}: DataSourceMapperModalProps) {
  const [mappings, setMappings] = useState<Record<string, string>>(() => {
    const initial: Record<string, string> = {}
    unknownSources.forEach((s) => {
      initial[s] = dataSources[0]?.name || 'events'
    })
    return initial
  })

  if (!open || !widget) return null

  const handleConfirm = () => {
    // Apply the mappings to the widget's query config
    let dataSource = widget.query_config.dataSource
    for (const [source, target] of Object.entries(mappings)) {
      if (dataSource === `__unmapped:${source}`) {
        dataSource = target
      }
    }
    onConfirm({
      ...widget,
      query_config: {
        ...widget.query_config,
        dataSource,
      },
    })
  }

  return (
    <div className="fixed inset-0 z-50 flex items-center justify-center bg-black/50">
      <div className="bg-background w-full max-w-md rounded-lg border p-6 shadow-lg">
        <div className="mb-4 flex items-center gap-3">
          <div className="flex h-10 w-10 items-center justify-center rounded-full bg-yellow-500/10">
            <AlertTriangle className="h-5 w-5 text-yellow-500" />
          </div>
          <div>
            <h3 className="font-semibold">Unknown Data Source</h3>
            <p className="text-muted-foreground text-sm">
              The pasted widget uses data sources that don&apos;t exist in Moneat.
              Map them to an available source.
            </p>
          </div>
        </div>

        <div className="space-y-4">
          {unknownSources.map((source) => (
            <div key={source}>
              <label className="text-sm font-medium">
                <code className="bg-muted rounded px-1.5 py-0.5 text-xs">{source}</code>
                <span className="text-muted-foreground ml-2">→</span>
              </label>
              <div className="mt-1.5">
                <DataSourcePicker
                  dataSources={dataSources}
                  value={mappings[source] || 'events'}
                  onChange={(val) => setMappings((prev) => ({...prev, [source]: val}))}
                />
              </div>
            </div>
          ))}
        </div>

        {widget.title && (
          <div className="mt-4 rounded-md bg-muted/50 px-3 py-2">
            <span className="text-muted-foreground text-xs">Widget: </span>
            <span className="text-sm font-medium">{widget.title}</span>
            <span className="text-muted-foreground text-xs ml-2">({widget.widget_type})</span>
          </div>
        )}

        <div className="mt-6 flex justify-end gap-2">
          <Button variant="ghost" onClick={onCancel}>
            Cancel
          </Button>
          <Button onClick={handleConfirm}>
            Paste Widget
          </Button>
        </div>
      </div>
    </div>
  )
}
