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

import {useState, useRef} from 'react'
import {useMutation, useQueryClient} from '@tanstack/react-query'
import {useNavigate} from '@tanstack/react-router'
import {api} from '@/lib/api'
import {Button} from '@/components/ui/button'
import {Upload, Download, AlertTriangle, Check} from 'lucide-react'

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
  const [format, setFormat] = useState<string>('datadog')
  const [warnings, setWarnings] = useState<string[]>([])
  const [importSuccess, setImportSuccess] = useState(false)
  const [importedDashboardId, setImportedDashboardId] = useState<number | null>(null)
  const [exportData, setExportData] = useState<string>('')

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

      // Auto-detect format
      try {
        const parsed = JSON.parse(content)
        if (parsed.widgets && parsed.layout_type) {
          setFormat('datadog')
        } else if (parsed.panels) {
          setFormat('grafana')
        }
      } catch {
        // Keep current format
      }
    }
    reader.readAsText(file)
  }

  const handleImport = () => {
    importMutation.mutate({format, json: jsonInput})
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
          <h2 className="text-lg font-semibold">
            {mode === 'import' ? 'Import Dashboard' : 'Export Dashboard'}
          </h2>
          <p className="text-xs text-muted-foreground mt-0.5">
            {mode === 'import'
              ? 'Import a dashboard from DataDog or Grafana JSON format'
              : 'Export this dashboard in various formats'}
          </p>
        </div>

        {/* Body */}
        <div className="flex-1 overflow-auto px-5 py-4 space-y-4">
          {mode === 'import' ? (
            <>
              {/* Format selector */}
              <div>
                <label className="text-xs font-medium text-muted-foreground mb-1 block">Format</label>
                <div className="flex gap-2">
                  <button
                    onClick={() => setFormat('datadog')}
                    className={`flex-1 px-3 py-2 rounded-md border text-sm ${
                      format === 'datadog' ? 'border-primary bg-primary/5' : 'hover:bg-muted'
                    }`}
                  >
                    DataDog
                  </button>
                  <button
                    onClick={() => setFormat('grafana')}
                    className={`flex-1 px-3 py-2 rounded-md border text-sm ${
                      format === 'grafana' ? 'border-primary bg-primary/5' : 'hover:bg-muted'
                    }`}
                  >
                    Grafana
                  </button>
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
                  <Upload className="h-4 w-4 mr-2" /> Upload JSON File
                </Button>
              </div>

              {/* JSON textarea */}
              <div>
                <label className="text-xs font-medium text-muted-foreground mb-1 block">
                  Or paste JSON
                </label>
                <textarea
                  className="w-full rounded-md border bg-background px-3 py-2 text-xs font-mono min-h-[200px]"
                  value={jsonInput}
                  onChange={(e) => setJsonInput(e.target.value)}
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
              <Button variant="outline" className="w-full justify-start" onClick={() => handleExport('datadog')}>
                <Download className="h-4 w-4 mr-2" /> Export as DataDog JSON
              </Button>
              <Button variant="outline" className="w-full justify-start" onClick={() => handleExport('grafana')}>
                <Download className="h-4 w-4 mr-2" /> Export as Grafana JSON
              </Button>

              {exportData && (
                <div>
                  <label className="text-xs font-medium text-muted-foreground mb-1 block">Preview</label>
                  <pre className="rounded-md border bg-muted/30 p-3 text-xs font-mono max-h-[200px] overflow-auto">
                    {exportData.slice(0, 1000)}{exportData.length > 1000 ? '...' : ''}
                  </pre>
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
    </div>
  )
}
