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
import {AlertTriangle, CheckCircle2, CircleDot, Play, XCircle} from 'lucide-react'
import {Button} from '@/components/ui/button'
import {Input} from '@/components/ui/input'
import {Label} from '@/components/ui/label'
import {Badge} from '@/components/ui/badge'
import {
  Select,
  SelectContent,
  SelectItem,
  SelectTrigger,
  SelectValue,
} from '@/components/ui/select'
import {cn} from '@/lib/utils'
import {isUuidResourceId} from '@/lib/api/utils'
import type {
  AlertRouteEvaluation,
  AlertRoutePreviewRequest,
  AlertRoutePreviewResponse,
  AlertRouteSample,
} from '@/lib/api/types'
import {
  ALERT_SOURCE_OPTIONS,
  ALERT_STATUS_OPTIONS,
  APPROVED_METADATA_KEYS,
  PRIORITY_OPTIONS,
  createEditorKey,
  describeEvaluationReason,
  formatWindow,
  overlappingEarlierRoutes,
} from './alertRouteModel'

interface MetadataRow {
  clientKey: string
  key: string
  value: string
}

type PreviewMode = 'sample' | 'episode'

interface AlertRoutePreviewPanelProps {
  onRun: (request: AlertRoutePreviewRequest) => void
  result?: AlertRoutePreviewResponse
  isLoading: boolean
  error?: string
  /** Highlights the route currently being edited within the evaluation list. */
  editingRouteId?: string
}

function createSample(): AlertRouteSample {
  return {
    source: 'HOST_ALERT',
    status: 'FIRING',
    priority: 'P2',
    title: '',
    description: '',
    deduplicationKey: '',
    url: '',
    metadata: {},
  }
}

export function AlertRoutePreviewPanel({
  onRun,
  result,
  isLoading,
  error,
  editingRouteId,
}: Readonly<AlertRoutePreviewPanelProps>) {
  const [mode, setMode] = useState<PreviewMode>('sample')
  const [sample, setSample] = useState<AlertRouteSample>(createSample)
  const [metadataRows, setMetadataRows] = useState<MetadataRow[]>([])
  const [episodeId, setEpisodeId] = useState('')

  const episodeIdValid = isUuidResourceId(episodeId.trim())
  const canRun = mode === 'sample' ? sample.source.trim().length > 0 : episodeIdValid

  const run = () => {
    if (mode === 'episode') {
      onRun({episodeId: episodeId.trim()})
      return
    }
    const metadata: Record<string, string> = {}
    metadataRows.forEach((row) => {
      const key = row.key.trim()
      if (key) metadata[key] = row.value
    })
    onRun({sample: {...sample, metadata}})
  }

  const updateSample = (patch: Partial<AlertRouteSample>) =>
    setSample((prev) => ({...prev, ...patch}))

  return (
    <div className="space-y-4">
      <div className="flex items-center gap-1.5">
        <ModeTab active={mode === 'sample'} onClick={() => setMode('sample')}>
          Sample alert
        </ModeTab>
        <ModeTab active={mode === 'episode'} onClick={() => setMode('episode')}>
          Saved alert episode
        </ModeTab>
      </div>

      {mode === 'sample' ? (
        <div className="space-y-3">
          <div className="grid grid-cols-1 gap-3 sm:grid-cols-3">
            <div className="space-y-1.5">
              <Label htmlFor="preview-source">Source</Label>
              <Select value={sample.source} onValueChange={(value) => updateSample({source: value})}>
                <SelectTrigger id="preview-source" className="h-8">
                  <SelectValue />
                </SelectTrigger>
                <SelectContent>
                  {ALERT_SOURCE_OPTIONS.map((option) => (
                    <SelectItem key={option} value={option}>
                      {option}
                    </SelectItem>
                  ))}
                </SelectContent>
              </Select>
            </div>
            <div className="space-y-1.5">
              <Label htmlFor="preview-status">Status</Label>
              <Select value={sample.status} onValueChange={(value) => updateSample({status: value})}>
                <SelectTrigger id="preview-status" className="h-8">
                  <SelectValue />
                </SelectTrigger>
                <SelectContent>
                  {ALERT_STATUS_OPTIONS.map((option) => (
                    <SelectItem key={option} value={option}>
                      {option}
                    </SelectItem>
                  ))}
                </SelectContent>
              </Select>
            </div>
            <div className="space-y-1.5">
              <Label htmlFor="preview-priority">Priority</Label>
              <Select value={sample.priority} onValueChange={(value) => updateSample({priority: value})}>
                <SelectTrigger id="preview-priority" className="h-8">
                  <SelectValue />
                </SelectTrigger>
                <SelectContent>
                  {PRIORITY_OPTIONS.map((option) => (
                    <SelectItem key={option} value={option}>
                      {option}
                    </SelectItem>
                  ))}
                </SelectContent>
              </Select>
            </div>
          </div>
          <div className="space-y-1.5">
            <Label htmlFor="preview-title">Title</Label>
            <Input
              id="preview-title"
              value={sample.title}
              onChange={(event) => updateSample({title: event.target.value})}
              placeholder="High CPU on web-1"
              className="h-8"
            />
          </div>
          <MetadataEditor rows={metadataRows} onChange={setMetadataRows} />
        </div>
      ) : (
        <div className="space-y-1.5">
          <Label htmlFor="preview-episode">Alert episode ID</Label>
          <Input
            id="preview-episode"
            value={episodeId}
            onChange={(event) => setEpisodeId(event.target.value)}
            placeholder="00000000-0000-0000-0000-000000000000"
            className="h-8 font-mono text-xs"
          />
          {episodeId.trim() !== '' && !episodeIdValid && (
            <p className="text-xs text-danger-fg">Enter a valid alert episode ID.</p>
          )}
        </div>
      )}

      <Button size="sm" onClick={run} disabled={!canRun || isLoading} className="gap-1.5">
        <Play className="h-3.5 w-3.5" />
        {isLoading ? 'Running preview…' : 'Run preview'}
      </Button>

      {error && (
        <div className="flex items-start gap-2 rounded-lg border border-danger-border bg-danger-bg p-2.5 text-xs text-danger-fg">
          <AlertTriangle className="mt-0.5 h-3.5 w-3.5 shrink-0" />
          <span>{error}</span>
        </div>
      )}

      {result && <PreviewResult result={result} editingRouteId={editingRouteId} />}
    </div>
  )
}

function ModeTab({
  active,
  onClick,
  children,
}: Readonly<{active: boolean; onClick: () => void; children: React.ReactNode}>) {
  return (
    <button
      type="button"
      onClick={onClick}
      className={cn(
        'rounded-md border px-2.5 py-1 text-xs font-medium transition-colors',
        active ? 'border-primary bg-accent/50 text-foreground' : 'border-transparent text-muted-foreground hover:bg-muted/60'
      )}
    >
      {children}
    </button>
  )
}

function MetadataEditor({
  rows,
  onChange,
}: Readonly<{rows: MetadataRow[]; onChange: (rows: MetadataRow[]) => void}>) {
  const addRow = () => onChange([...rows, {
    clientKey: createEditorKey('metadata'),
    key: APPROVED_METADATA_KEYS[0],
    value: '',
  }])
  const updateRow = (index: number, patch: Partial<MetadataRow>) =>
    onChange(rows.map((row, i) => (i === index ? {...row, ...patch} : row)))
  const removeRow = (index: number) => onChange(rows.filter((_, i) => i !== index))

  return (
    <div className="space-y-2">
      <div className="flex items-center justify-between">
        <Label>Attributes</Label>
        <Button type="button" variant="outline" size="sm" className="h-7" onClick={addRow}>
          Add attribute
        </Button>
      </div>
      {rows.length === 0 && (
        <p className="text-xs text-muted-foreground">
          Add attributes such as environment or region to preview metadata conditions.
        </p>
      )}
      {rows.map((row, index) => (
        <div key={row.clientKey} className="flex items-center gap-2">
          <Select value={row.key} onValueChange={(value) => updateRow(index, {key: value})}>
            <SelectTrigger className="h-8 w-44">
              <SelectValue />
            </SelectTrigger>
            <SelectContent>
              {APPROVED_METADATA_KEYS.map((key) => (
                <SelectItem key={key} value={key}>
                  {key}
                </SelectItem>
              ))}
            </SelectContent>
          </Select>
          <Input
            value={row.value}
            onChange={(event) => updateRow(index, {value: event.target.value})}
            placeholder="value"
            className="h-8 flex-1"
          />
          <Button
            type="button"
            variant="ghost"
            size="icon"
            className="h-8 w-8 shrink-0"
            onClick={() => removeRow(index)}
            aria-label="Remove attribute"
          >
            <XCircle className="h-4 w-4" />
          </Button>
        </div>
      ))}
    </div>
  )
}

function PreviewResult({
  result,
  editingRouteId,
}: Readonly<{result: AlertRoutePreviewResponse; editingRouteId?: string}>) {
  const overlaps = overlappingEarlierRoutes(result)
  const selected = result.evaluations.find((evaluation) => evaluation.selected)
  const incidentAction = describeIncidentResolution(result)

  return (
    <div className="space-y-3 border-t pt-3">
      {selected ? (
        <div className="flex items-start gap-2 rounded-lg border border-success-border bg-success-bg p-2.5 text-xs">
          <CheckCircle2 className="mt-0.5 h-3.5 w-3.5 shrink-0 text-success-fg" />
          <span>
            <span className="font-medium">{selected.name}</span> handles this alert.
          </span>
        </div>
      ) : (
        <div className="flex items-start gap-2 rounded-lg border bg-muted/40 p-2.5 text-xs text-muted-foreground">
          <CircleDot className="mt-0.5 h-3.5 w-3.5 shrink-0" />
          <span>No route matches this alert. Existing alert delivery remains unchanged.</span>
        </div>
      )}

      {overlaps.length > 0 && (
        <div className="flex items-start gap-2 rounded-lg border border-warning-border bg-warning-bg p-2.5 text-xs text-warning-fg">
          <AlertTriangle className="mt-0.5 h-3.5 w-3.5 shrink-0" />
          <span>
            {overlaps.length === 1 ? 'One other route also matches' : `${overlaps.length} other routes also match`},
            but {overlaps.length === 1 ? 'it will not run' : 'they will not run'} because{' '}
            <span className="font-medium">{selected?.name}</span> is evaluated first:{' '}
            {overlaps.map((route) => route.name).join(', ')}.
          </span>
        </div>
      )}

      {result.decision && (
        <div className="rounded-lg border p-2.5 text-xs">
          <p className="mb-1.5 font-medium">Resulting actions</p>
          <ul className="space-y-1 text-muted-foreground">
            <li>Paging: {result.decision.paging.mode.replaceAll('_', ' ').toLowerCase()}</li>
            <li>
              Grouping: {result.decision.grouping.behavior.toLowerCase()} ·{' '}
              {result.decision.grouping.windowKind.toLowerCase()} window of{' '}
              {formatWindow(result.decision.grouping.windowSeconds)}
              {result.decision.grouping.groupKey ? ` · key ${result.decision.grouping.groupKey}` : ''}
            </li>
            <li>
              Incident: {incidentAction}
            </li>
          </ul>
          {result.decision.grouping.unresolvedKeys.length > 0 && (
            <p className="mt-1.5 text-warning-fg">
              Unresolved grouping keys: {result.decision.grouping.unresolvedKeys.join(', ')}
            </p>
          )}
          {result.decision.incident.unresolvedReferences.length > 0 && (
            <p className="mt-1 text-warning-fg">
              Unresolved template references: {result.decision.incident.unresolvedReferences.join(', ')}
            </p>
          )}
        </div>
      )}

      <div className="space-y-1.5">
        <p className="text-xs font-medium">Route evaluation</p>
        {result.evaluations.map((evaluation) => (
          <EvaluationRow
            key={evaluation.routeId}
            evaluation={evaluation}
            highlighted={evaluation.routeId === editingRouteId}
          />
        ))}
      </div>

      {result.droppedMetadataKeys.length > 0 && (
        <p className="text-xs text-muted-foreground">
          Dropped unapproved attributes: {result.droppedMetadataKeys.join(', ')}
        </p>
      )}
    </div>
  )
}

function describeIncidentResolution(result: AlertRoutePreviewResponse): string {
  const incident = result.decision?.incident
  if (!incident?.create) return 'do not create'
  const severity = incident.severity ? ` · ${incident.severity}` : ''
  return `create in ${incident.mode.toLowerCase()}${severity}`
}

function EvaluationRow({
  evaluation,
  highlighted,
}: Readonly<{evaluation: AlertRouteEvaluation; highlighted: boolean}>) {
  let variant: 'success' | 'neutral' | 'warning' = 'neutral'
  if (evaluation.selected) variant = 'success'
  else if (evaluation.matched) variant = 'warning'

  return (
    <div
      className={cn(
        'rounded-lg border p-2 text-xs',
        highlighted && 'border-primary bg-accent/40',
        !evaluation.enabled && 'opacity-60'
      )}
    >
      <div className="flex items-center justify-between gap-2">
        <span className="flex items-center gap-1.5 font-medium">
          <span className="text-muted-foreground">#{evaluation.position + 1}</span>
          {evaluation.name}
          {highlighted && (
            <Badge variant="accent" size="sm">
              Editing
            </Badge>
          )}
        </span>
        <Badge variant={variant} size="sm">
          {describeEvaluationReason(evaluation.reason)}
        </Badge>
      </div>
    </div>
  )
}
