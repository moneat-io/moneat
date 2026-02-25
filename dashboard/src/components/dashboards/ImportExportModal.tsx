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

import {useMemo, useRef, useState} from 'react'
import {useMutation, useQuery, useQueryClient} from '@tanstack/react-query'
import {useNavigate} from '@tanstack/react-router'
import {api} from '@/lib/api'
import {Button} from '@/components/ui/button'
import {CodeEditor} from '@/components/ui/code-editor'
import {CopyBlock} from '@/components/ui/copy-block'
import {AlertTriangle, Check, Download, Upload} from 'lucide-react'
import {DataSourceMapperModal} from './DataSourceMapperModal'

interface ImportExportModalProps {
  open: boolean
  onOpenChange: (open: boolean) => void
  mode: 'import' | 'export'
  dashboardId?: number
}

export function ImportExportModal({open, onOpenChange, mode, dashboardId}: ImportExportModalProps) {
  const queryClient = useQueryClient()
  const navigate = useNavigate()
  const fileInputRef = useRef<HTMLInputElement>(null)
  const [jsonInput, setJsonInput] = useState('')
  const format = 'grafana' // Only support Grafana for now
  const [warnings, setWarnings] = useState<string[]>([])
  const [importSuccess, setImportSuccess] = useState(false)
  const [importedDashboardId, setImportedDashboardId] = useState<number | null>(null)
  const [exportData, setExportData] = useState<string>('')
  const [showDataSourceMapper, setShowDataSourceMapper] = useState(false)
  const [unmappedDataSources, setUnmappedDataSources] = useState<string[]>([])
  const [pendingImport, setPendingImport] = useState<{format: string; json: string} | null>(null)
  
  // Fetch custom data sources for mapping
  const {data: customDataSourcesData} = useQuery({
    queryKey: ['custom-data-sources'],
    queryFn: () => api.listCustomDataSources(),
    enabled: open && mode === 'import',
  })
  
  // Build complete DataSourceInfo array (built-in + custom)
  const allDataSources = useMemo(() => {
    const builtIn = [
      {name: 'events', label: 'Events', fields: []},
      {name: 'spans', label: 'Spans', fields: []},
      {name: 'logs', label: 'Logs', fields: []},
      {name: 'metrics', label: 'System Metrics', fields: []},
      {name: 'containers', label: 'Container Metrics', fields: []},
      {name: 'uptime_heartbeats', label: 'Uptime Heartbeats', fields: []},
      {name: 'llm_generations', label: 'LLM Generations', fields: []},
      {name: 'analytics_events', label: 'Analytics Events', fields: []},
    ]
    
    const custom = (customDataSourcesData || []).map(ds => ({
      name: `custom:${ds.id}`,
      label: `${ds.name} (${ds.source_type})`,
      fields: [],
    }))
    
    return [...builtIn, ...custom]
  }, [customDataSourcesData])

  const importMutation = useMutation({
    mutationFn: ({format, json}: {format: string; json: string}) => api.importDashboard(format, json),
    onSuccess: (result) => {
      setWarnings(result.warnings)
      setImportSuccess(true)
      setImportedDashboardId(result.dashboard.id)
      queryClient.invalidateQueries({queryKey: ['custom-dashboards']})
      // Navigate immediately if no warnings to review
      if (result.warnings.length === 0) {
        onOpenChange(false)
        navigate({to: '/dashboards/$dashboardId', params: {dashboardId: String(result.dashboard.id)}})
      }
    },
  })

  const handleFileUpload = (e: React.ChangeEvent<HTMLInputElement>) => {
    const file = e.target.files?.[0]
    if (!file) return

    const reader = new FileReader()
    reader.onload = (event) => {
      const content = event.target?.result as string
      setJsonInput(content)
    }
    reader.readAsText(file)
  }

  const handleImport = () => {
    // Detect unmapped datasources before import
    try {
      const parsed = JSON.parse(jsonInput)
      const foundDataSources = new Set<string>()
      
      if (format === 'grafana' && parsed.panels) {
        // Check all panels for datasources
        const checkPanel = (panel: Record<string, unknown>) => {
          if (panel.targets) {
            for (const target of panel.targets) {
              // Grafana datasources can be:
              // - {uid: "...", type: "prometheus"}
              // - "datasource-name"
              // - null (default datasource)
              if (target.datasource) {
                if (typeof target.datasource === 'string') {
                  foundDataSources.add(target.datasource)
                } else if (target.datasource.type) {
                  // Use the type as identifier (e.g., "prometheus", "postgres", "mysql")
                  foundDataSources.add(target.datasource.type)
                }
              }
            }
          }
        }
        
        for (const panel of parsed.panels) {
          checkPanel(panel)
          // Check nested panels in collapsed rows
          if (panel.panels) {
            for (const nested of panel.panels) {
              checkPanel(nested)
            }
          }
        }
      }
      
      // Check which datasources don't exist in Moneat
      // Built-in datasources that always exist
      const builtInSources = new Set(['events', 'spans', 'logs', 'metrics', 
        'containers', 'uptime_heartbeats', 'llm_generations', 'analytics_events'])
      
      console.log('Found datasources in import:', Array.from(foundDataSources))
      console.log('Available custom datasources:', customDataSourcesData?.map(d => ({name: d.name, type: d.source_type})))
      
      const unmapped: string[] = []
      for (const ds of foundDataSources) {
        // Check if it's a built-in source - these don't need mapping
        if (builtInSources.has(ds)) {
          console.log(`  ${ds} -> matched built-in, no mapping needed`)
          continue
        }
        
        // Everything else is external and needs to be mapped to a custom datasource
        // Even if we have a matching source_type, we can't assume which specific
        // custom datasource the user wants (they might have multiple Prometheus sources)
        console.log(`  ${ds} -> external datasource, needs mapping`)
        unmapped.push(ds)
      }
      
      if (unmapped.length > 0) {
        // Show mapper modal
        console.log('Unmapped datasources detected:', unmapped)
        setUnmappedDataSources(unmapped)
        setPendingImport({format, json: jsonInput})
        setShowDataSourceMapper(true)
        return
      } else {
        console.log('All datasources are built-in, proceeding with import')
      }
    } catch (err) {
      console.error('Failed to parse JSON for datasource detection:', err)
    }
    
    // No unmapped datasources, proceed with import
    importMutation.mutate({format, json: jsonInput})
  }
  
  const handleDataSourceMapped = (mapping: Record<string, string>) => {
    if (!pendingImport) return
    
    console.log('Applying mappings:', mapping)
    
    // Apply mappings to the JSON
    try {
      const parsed = JSON.parse(pendingImport.json)
      
      if (pendingImport.format === 'grafana' && parsed.panels) {
        const mapDatasource = (ds: unknown): unknown => {
          if (!ds) return ds
          
          // Handle different datasource formats
          if (typeof ds === 'string') {
            // String datasource name
            if (mapping[ds]) {
              console.log(`  Mapped string datasource: ${ds} → ${mapping[ds]}`)
              return mapping[ds]
            }
          } else if (ds.type) {
            // Object with type field - check if we have a mapping for this type
            if (mapping[ds.type]) {
              console.log(`  Mapped datasource by type: ${ds.type} → ${mapping[ds.type]}`)
              // Replace with mapped custom datasource
              return mapping[ds.type]
            }
          } else if (ds.uid) {
            // Object with UID
            if (mapping[ds.uid]) {
              console.log(`  Mapped datasource by uid: ${ds.uid} → ${mapping[ds.uid]}`)
              return mapping[ds.uid]
            }
          }
          
          return ds
        }
        
        const applyMapping = (panel: Record<string, unknown>) => {
          // Map panel-level datasource
          if (panel.datasource) {
            const originalDs = JSON.stringify(panel.datasource)
            panel.datasource = mapDatasource(panel.datasource)
            if (originalDs !== JSON.stringify(panel.datasource)) {
              console.log(`  Panel datasource mapped`)
            }
          }
          
          // Map target-level datasources
          if (panel.targets) {
            for (const target of panel.targets) {
              if (target.datasource) {
                const originalDs = JSON.stringify(target.datasource)
                target.datasource = mapDatasource(target.datasource)
                if (originalDs === JSON.stringify(target.datasource)) {
                  console.log(`  No mapping applied for datasource: ${originalDs}`)
                }
              }
            }
          }
        }
        
        for (const panel of parsed.panels) {
          applyMapping(panel)
          if (panel.panels) {
            for (const nested of panel.panels) {
              applyMapping(nested)
            }
          }
        }
      }
      
      // Import with mapped datasources
      const mappedJson = JSON.stringify(parsed)
      importMutation.mutate({format: pendingImport.format, json: mappedJson})
      
      setShowDataSourceMapper(false)
      setPendingImport(null)
      setUnmappedDataSources([])
    } catch (err) {
      console.error('Failed to apply datasource mappings:', err)
    }
  }

  const handleExport = async (exportFormat: string) => {
    if (!dashboardId) return
    try {
      const data = await api.exportDashboard(dashboardId, exportFormat)
      const jsonStr = JSON.stringify(data, null, 2)
      setExportData(jsonStr)

      // Download file
      const blob = new Blob([jsonStr], {type: 'application/json'})
      const url = URL.createObjectURL(blob)
      const a = document.createElement('a')
      a.href = url
      a.download = `dashboard-${dashboardId}-${exportFormat}.json`
      a.click()
      URL.revokeObjectURL(url)
    } catch (err) {
      console.error('Export failed', err)
    }
  }

  if (!open) return null

  return (
    <div className="fixed inset-0 z-50 flex items-center justify-center">
      <div className="fixed inset-0 bg-black/50" onClick={() => onOpenChange(false)} />
      <div className="relative bg-background border rounded-lg shadow-xl w-[520px] max-h-[80vh] flex flex-col">
        {/* Header */}
        <div className="px-5 py-4 border-b">
          <h2 className="text-lg font-semibold flex items-center gap-2">
            {mode === 'import' ? 'Import Dashboard' : 'Export Dashboard'}
            {mode === 'import' && (
              <span className="text-xs font-normal px-2 py-0.5 rounded bg-amber-100 dark:bg-amber-950 text-amber-700 dark:text-amber-300 border border-amber-200 dark:border-amber-800">
                Experimental
              </span>
            )}
          </h2>
          <p className="text-xs text-muted-foreground mt-0.5">
            {mode === 'import'
              ? 'Import a dashboard from Grafana JSON format'
              : 'Export this dashboard in various formats'}
          </p>
        </div>

        {/* Body */}
        <div className="flex-1 overflow-auto px-5 py-4 space-y-4">
          {mode === 'import' ? (
            <>
              {/* Grafana logo/info */}
              <div className="flex items-center gap-3 p-3 rounded-lg border bg-muted/30">
                <svg className="h-8 w-8" viewBox="0 0 64 64" fill="none" xmlns="http://www.w3.org/2000/svg">
                  <path d="M57.9 13.4c-.7-2.2-1.8-4.3-3.3-6.1-.8-.9-1.6-1.7-2.6-2.4-1.3-1-2.7-1.8-4.2-2.4-2.2-.9-4.5-1.4-6.9-1.4-2 0-4 .3-5.9.9-1.6.5-3.2 1.2-4.6 2.1-1.1.7-2.1 1.5-3 2.4-1.3 1.3-2.4 2.7-3.3 4.3-1 1.7-1.7 3.6-2.1 5.5-.3 1.3-.4 2.7-.3 4 .1 1.6.4 3.1.9 4.6.6 1.7 1.4 3.3 2.5 4.7.9 1.2 2 2.3 3.2 3.2 1.5 1.2 3.2 2.1 5 2.7 2.1.7 4.3 1 6.5.9 2-.1 3.9-.5 5.7-1.2 1.5-.6 2.9-1.4 4.2-2.4 1-.8 1.9-1.7 2.7-2.7 1.2-1.5 2.1-3.2 2.8-4.9.7-1.9 1.1-3.9 1.1-5.9 0-2-.3-4-.9-5.9z" fill="#F05A28"/>
                  <path d="M41.2 32c-5.5 0-10-4.5-10-10s4.5-10 10-10 10 4.5 10 10-4.5 10-10 10zm0-17c-3.9 0-7 3.1-7 7s3.1 7 7 7 7-3.1 7-7-3.1-7-7-7z" fill="#FFF"/>
                  <circle cx="41.2" cy="22" r="3" fill="#FFF"/>
                </svg>
                <div className="flex-1">
                  <div className="text-sm font-medium">Grafana Dashboard Import</div>
                  <div className="text-xs text-muted-foreground">Upload or paste your Grafana JSON export</div>
                </div>
              </div>

              {/* File upload */}
              <div>
                <input
                  ref={fileInputRef}
                  type="file"
                  accept=".json"
                  onChange={handleFileUpload}
                  className="hidden"
                />
                <Button
                  variant="outline"
                  size="sm"
                  onClick={() => fileInputRef.current?.click()}
                  className="w-full"
                >
                  <Upload className="h-4 w-4 mr-2" /> Upload Grafana JSON File
                </Button>
              </div>

              {/* JSON textarea */}
              <div>
                <label className="text-xs font-medium text-muted-foreground mb-1 block">
                  Or paste JSON
                </label>
                <CodeEditor
                  language="json"
                  rows={10}
                  value={jsonInput}
                  onChange={setJsonInput}
                  placeholder='{"title": "My Dashboard", "widgets": [...]}'
                />
              </div>

              {/* Warnings */}
              {warnings.length > 0 && (
                <div className="rounded-md border border-amber-200 bg-amber-50 dark:border-amber-800 dark:bg-amber-950 p-3">
                  <div className="flex items-center gap-2 text-amber-600 dark:text-amber-400 text-xs font-medium mb-1">
                    <AlertTriangle className="h-3.5 w-3.5" /> Import Warnings
                  </div>
                  <ul className="text-xs text-amber-700 dark:text-amber-300 space-y-0.5">
                    {warnings.map((w, i) => (
                      <li key={i}>• {w}</li>
                    ))}
                  </ul>
                </div>
              )}

              {/* Success */}
              {importSuccess && (
                <div className="rounded-md border border-green-200 bg-green-50 dark:border-green-800 dark:bg-green-950 p-3 flex items-center justify-between">
                  <div className="flex items-center gap-2">
                    <Check className="h-4 w-4 text-green-600 dark:text-green-400" />
                    <span className="text-xs text-green-700 dark:text-green-300">
                      Dashboard imported successfully!
                    </span>
                  </div>
                  {importedDashboardId && (
                    <Button
                      size="sm"
                      variant="outline"
                      className="h-7 text-xs"
                      onClick={() => {
                        onOpenChange(false)
                        navigate({to: '/dashboards/$dashboardId', params: {dashboardId: String(importedDashboardId)}})
                      }}
                    >
                      View Dashboard
                    </Button>
                  )}
                </div>
              )}
            </>
          ) : (
            /* Export mode */
            <div className="space-y-3">
              <Button variant="outline" className="w-full justify-start" onClick={() => handleExport('moneat')}>
                <Download className="h-4 w-4 mr-2" /> Export as Moneat JSON
              </Button>
              <div className="flex items-center gap-3 p-3 rounded-lg border bg-muted/30">
                <svg className="h-6 w-6 flex-shrink-0" viewBox="0 0 64 64" fill="none" xmlns="http://www.w3.org/2000/svg">
                  <path d="M57.9 13.4c-.7-2.2-1.8-4.3-3.3-6.1-.8-.9-1.6-1.7-2.6-2.4-1.3-1-2.7-1.8-4.2-2.4-2.2-.9-4.5-1.4-6.9-1.4-2 0-4 .3-5.9.9-1.6.5-3.2 1.2-4.6 2.1-1.1.7-2.1 1.5-3 2.4-1.3 1.3-2.4 2.7-3.3 4.3-1 1.7-1.7 3.6-2.1 5.5-.3 1.3-.4 2.7-.3 4 .1 1.6.4 3.1.9 4.6.6 1.7 1.4 3.3 2.5 4.7.9 1.2 2 2.3 3.2 3.2 1.5 1.2 3.2 2.1 5 2.7 2.1.7 4.3 1 6.5.9 2-.1 3.9-.5 5.7-1.2 1.5-.6 2.9-1.4 4.2-2.4 1-.8 1.9-1.7 2.7-2.7 1.2-1.5 2.1-3.2 2.8-4.9.7-1.9 1.1-3.9 1.1-5.9 0-2-.3-4-.9-5.9z" fill="#F05A28"/>
                  <path d="M41.2 32c-5.5 0-10-4.5-10-10s4.5-10 10-10 10 4.5 10 10-4.5 10-10 10zm0-17c-3.9 0-7 3.1-7 7s3.1 7 7 7 7-3.1 7-7-3.1-7-7-7z" fill="#FFF"/>
                  <circle cx="41.2" cy="22" r="3" fill="#FFF"/>
                </svg>
                <Button variant="outline" className="flex-1 justify-start" onClick={() => handleExport('grafana')}>
                  <Download className="h-4 w-4 mr-2" /> Export as Grafana JSON
                </Button>
              </div>

              {exportData && (
                <div>
                  <label className="text-xs font-medium text-muted-foreground mb-1 block">Preview</label>
                  <div className="max-h-[200px] overflow-auto rounded-md border">
                    <CopyBlock
                      code={exportData.slice(0, 2000)}
                      language="json"
                    />
                  </div>
                </div>
              )}
            </div>
          )}
        </div>

        {/* Footer */}
        <div className="flex items-center justify-end gap-2 px-5 py-3 border-t">
          <Button variant="outline" size="sm" onClick={() => onOpenChange(false)}>
            {importSuccess ? 'Close' : 'Cancel'}
          </Button>
          {mode === 'import' && !importSuccess && (
            <Button
              size="sm"
              onClick={handleImport}
              disabled={!jsonInput.trim() || importMutation.isPending}
            >
              {importMutation.isPending ? 'Importing...' : 'Import'}
            </Button>
          )}
        </div>
      </div>
      
      {/* DataSource Mapper Modal */}
      <DataSourceMapperModal
        open={showDataSourceMapper}
        onOpenChange={setShowDataSourceMapper}
        unmappedDataSources={unmappedDataSources}
        dataSources={allDataSources}
        onMapped={handleDataSourceMapped}
      />
    </div>
  )
}
