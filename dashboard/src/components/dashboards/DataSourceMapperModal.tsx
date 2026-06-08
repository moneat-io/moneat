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
  onOpenChange?: (open: boolean) => void
  // For widget paste flow
  widget?: CreateWidgetRequest | null
  unknownSources?: string[]
  dataSources?: DataSourceInfo[]
  onConfirm?: (widget: CreateWidgetRequest) => void
  onCancel?: () => void
  // For import flow
  unmappedDataSources?: string[]
  onMapped?: (mapping: Record<string, string>) => void
  initialMappings?: Record<string, string>
}

export function DataSourceMapperModal({
  open,
  onOpenChange,
  widget,
  unknownSources = [],
  dataSources = [],
  onConfirm,
  onCancel,
  unmappedDataSources = [],
  onMapped,
  initialMappings = {},
}: DataSourceMapperModalProps) {
  // Import mode uses unmappedDataSources, widget paste mode uses unknownSources
  const isImportMode = unmappedDataSources.length > 0 && !widget
  const sourcesToMap = isImportMode ? unmappedDataSources : unknownSources
  
  const [mappings, setMappings] = useState<Record<string, string>>(() => {
    const initial: Record<string, string> = {}
    sourcesToMap.forEach((s) => {
      initial[s] = initialMappings[s] || '__skip__'
    })
    return initial
  })

  if (!open) return null
  if (!isImportMode && !widget) return null

  const handleConfirm = () => {
    if (isImportMode && onMapped) {
      // Import mode: filter out skipped mappings
      const actualMappings: Record<string, string> = {}
      for (const [source, target] of Object.entries(mappings)) {
        if (target !== '__skip__') {
          actualMappings[source] = target
        }
      }
      onMapped(actualMappings)
    } else if (widget && onConfirm) {
      // Widget paste mode: apply mappings to widget
      let dataSource = widget.query_configs[0]?.dataSource
      for (const [source, target] of Object.entries(mappings)) {
        if (target !== '__skip__' && dataSource === `__unmapped:${source}`) {
          dataSource = target
        }
      }
      const updatedFirstQuery = {...widget.query_configs[0], dataSource}
      onConfirm({
        ...widget,
        query_configs: [updatedFirstQuery, ...widget.query_configs.slice(1)],
      })
    }
  }
  
  const handleCancel = () => {
    if (onOpenChange) {
      onOpenChange(false)
    } else if (onCancel) {
      onCancel()
    }
  }

  return (
    <div className="fixed inset-0 z-50 flex items-center justify-center bg-black/50">
      <div className="bg-background w-full max-w-md rounded-lg border p-6">
        <div className="mb-4 flex items-center gap-3">
          <div className="flex h-10 w-10 items-center justify-center rounded-full bg-warning-bg">
            <AlertTriangle className="h-5 w-5 text-warning-fg" />
          </div>
          <div>
            <h3 className="font-semibold">
              {isImportMode ? 'Map Data Sources' : 'Unknown Data Source'}
            </h3>
            <p className="text-muted-foreground text-sm">
              {isImportMode
                ? 'Map external datasources to your Moneat sources, or skip to configure later.'
                : "The pasted widget uses data sources that don't exist in Moneat. Map them to an available source."}
            </p>
          </div>
        </div>

        <div className="space-y-4">
          {sourcesToMap.map((source) => {
            // Show a human-friendly label for known Grafana plugin IDs
            const pluginLabels: Record<string, string> = {
              'redis-datasource': 'Redis',
              'prometheus': 'Prometheus',
              'elasticsearch': 'Elasticsearch',
              'grafana-elasticsearch-datasource': 'Elasticsearch',
              'influxdb': 'InfluxDB',
              'graphite': 'Graphite',
              'loki': 'Loki',
              'cloudwatch': 'CloudWatch',
              'mysql': 'MySQL',
              'postgres': 'PostgreSQL',
              'grafana-postgresql-datasource': 'PostgreSQL',
              'mssql': 'MS SQL Server',
              'grafana-clickhouse-datasource': 'ClickHouse',
              'grafana-bigquery-datasource': 'BigQuery',
              'grafana-mongodb-datasource': 'MongoDB',
              '__prometheus': 'Prometheus',
            }
            const sourceLabel = pluginLabels[source] || source
            
            // Add "Skip" option to datasources for this picker
            const dataSourcesWithSkip = [
              {name: '__skip__', label: '⊘ Skip / None (configure later)', fields: []},
              ...dataSources,
            ]
            
            return (
              <div key={source}>
                <label className="text-sm font-medium">
                  <code className="bg-muted rounded px-1.5 py-0.5 text-xs">{sourceLabel}</code>
                  <span className="text-muted-foreground ml-2">→</span>
                </label>
                <div className="mt-1.5">
                  <DataSourcePicker
                    dataSources={dataSourcesWithSkip}
                    value={mappings[source] || '__skip__'}
                    onChange={(val) => setMappings((prev) => ({...prev, [source]: val}))}
                  />
                </div>
              </div>
            )
          })}
        </div>

        {widget?.title && (
          <div className="mt-4 rounded-md bg-muted/50 px-3 py-2">
            <span className="text-muted-foreground text-xs">Widget: </span>
            <span className="text-sm font-medium">{widget.title}</span>
            <span className="text-muted-foreground text-xs ml-2">({widget.widget_type})</span>
          </div>
        )}

        <div className="mt-6 flex justify-end gap-2">
          <Button variant="ghost" onClick={handleCancel}>
            Cancel
          </Button>
          <Button onClick={handleConfirm}>
            {isImportMode ? 'Continue Import' : 'Paste Widget'}
          </Button>
        </div>
      </div>
    </div>
  )
}
