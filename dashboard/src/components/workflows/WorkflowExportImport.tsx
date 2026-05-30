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
import {Check, Copy, Download, Loader2, Upload} from 'lucide-react'
import type {WorkflowExportResponse, WorkflowImportRequest} from '@/lib/api'
import {Button} from '@/components/ui/button'
import {Textarea} from '@/components/ui/textarea'
import {cn} from '@/lib/utils'
import {jsonExport, parseImportJson} from './exportImportUtils'

type ExportFormat = 'terraform' | 'json'

interface WorkflowExportImportProps {
  exportData?: WorkflowExportResponse | null
  exporting?: boolean
  exportFailed?: boolean
  onExport: () => void
  importing?: boolean
  onImport: (request: WorkflowImportRequest) => void
}

export function WorkflowExportImport({
  exportData = null,
  exporting = false,
  exportFailed = false,
  onExport,
  importing = false,
  onImport,
}: WorkflowExportImportProps) {
  return (
    <div className="space-y-3">
      <ExportSection
        exportData={exportData}
        exporting={exporting}
        exportFailed={exportFailed}
        onExport={onExport}
      />
      <ImportSection importing={importing} onImport={onImport} />
    </div>
  )
}

function ExportSection({
  exportData,
  exporting,
  exportFailed,
  onExport,
}: {
  exportData: WorkflowExportResponse | null
  exporting: boolean
  exportFailed: boolean
  onExport: () => void
}) {
  const [format, setFormat] = useState<ExportFormat>('terraform')
  const [copied, setCopied] = useState(false)

  const content = exportData
    ? format === 'terraform'
      ? exportData.terraform
      : jsonExport(exportData)
    : ''

  const handleCopy = async () => {
    if (!content) return
    try {
      await navigator.clipboard.writeText(content)
      setCopied(true)
      setTimeout(() => setCopied(false), 2000)
    } catch {
      // Ignore clipboard errors
    }
  }

  return (
    <div className="rounded-md border bg-background p-3">
      <div className="flex flex-wrap items-center justify-between gap-2">
        <h3 className="text-sm font-semibold">Export</h3>
        <Button size="sm" variant="outline" disabled={exporting} onClick={onExport} className="gap-1.5">
          {exporting ? <Loader2 className="h-3.5 w-3.5 animate-spin" /> : <Download className="h-3.5 w-3.5" />}
          Export
        </Button>
      </div>
      {exportData ? (
        <div className="mt-3 space-y-2">
          <div className="flex items-center justify-between gap-2">
            <div className="inline-flex rounded-md border p-0.5">
              <FormatButton
                label="Terraform"
                active={format === 'terraform'}
                onClick={() => setFormat('terraform')}
              />
              <FormatButton
                label="JSON"
                active={format === 'json'}
                onClick={() => setFormat('json')}
              />
            </div>
            <Button
              size="sm"
              variant="ghost"
              onClick={handleCopy}
              className="gap-1.5"
              aria-label="Copy export"
            >
              {copied ? <Check className="h-3.5 w-3.5 text-emerald-500" /> : <Copy className="h-3.5 w-3.5" />}
              {copied ? 'Copied' : 'Copy'}
            </Button>
          </div>
          <pre className="max-h-72 overflow-auto rounded-md border bg-muted/30 p-3 text-[11px]">
            {content}
          </pre>
        </div>
      ) : (
        <EmptyExportState exportFailed={exportFailed} />
      )}
    </div>
  )
}

function EmptyExportState({exportFailed}: {exportFailed: boolean}) {
  if (exportFailed) {
    return (
      <p className="mt-3 text-sm text-destructive">
        Couldn&apos;t load the export. Please try again.
      </p>
    )
  }
  return (
    <p className="mt-3 text-sm text-muted-foreground">
      Export this workflow as Terraform or portable JSON.
    </p>
  )
}

function FormatButton({
  label,
  active,
  onClick,
}: {
  label: string
  active: boolean
  onClick: () => void
}) {
  return (
    <button
      type="button"
      onClick={onClick}
      className={cn(
        'rounded px-2.5 py-1 text-xs font-medium transition-colors',
        active ? 'bg-primary text-primary-foreground' : 'text-muted-foreground hover:text-foreground'
      )}
    >
      {label}
    </button>
  )
}

function ImportSection({
  importing,
  onImport,
}: {
  importing: boolean
  onImport: (request: WorkflowImportRequest) => void
}) {
  const [text, setText] = useState('')
  const [error, setError] = useState<string | null>(null)

  const handleImport = () => {
    const result = parseImportJson(text)
    if (typeof result === 'string') {
      setError(result)
      return
    }
    setError(null)
    onImport(result)
  }

  return (
    <div className="rounded-md border bg-background p-3">
      <h3 className="text-sm font-semibold">Import</h3>
      <p className="mt-1 text-xs text-muted-foreground">
        Paste an exported resource or JSON envelope to create a workflow.
      </p>
      <Textarea
        className="mt-2 min-h-28 font-mono text-[11px]"
        value={text}
        placeholder='{"name": "...", "trigger_name": "...", "graph": {"nodes": [], "edges": []}}'
        onChange={(event) => {
          setText(event.target.value)
          if (error) setError(null)
        }}
        aria-label="Workflow JSON"
      />
      {error && <p className="mt-1.5 text-xs text-destructive">{error}</p>}
      <Button
        size="sm"
        className="mt-2 gap-1.5"
        disabled={importing || text.trim().length === 0}
        onClick={handleImport}
      >
        {importing ? <Loader2 className="h-3.5 w-3.5 animate-spin" /> : <Upload className="h-3.5 w-3.5" />}
        Import
      </Button>
    </div>
  )
}
