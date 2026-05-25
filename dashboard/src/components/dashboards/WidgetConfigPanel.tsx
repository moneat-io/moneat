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
import type {DashboardWidget, QueryDsl} from '@/lib/api'
import {Button} from '@/components/ui/button'
import {QueryBuilderForm} from './QueryBuilderForm'
import {WidgetRenderer} from './WidgetRenderer'
import {AlertConfigForm} from './AlertConfigForm'
import {X, BarChart3, LineChart, PieChart, Hash, Table2, List, Grid3X3, Type, Plus, Trash2, Gauge, BarChartHorizontalBig} from 'lucide-react'
import type {ValueMapping} from './formatValue'
import type {AlertThresholdPreview} from './alertThresholds'

const WIDGET_TYPES = [
  {value: 'timeseries', label: 'Timeseries', icon: LineChart},
  {value: 'bar', label: 'Bar Chart', icon: BarChart3},
  {value: 'donut', label: 'Donut', icon: PieChart},
  {value: 'stat', label: 'Stat', icon: Hash},
  {value: 'gauge', label: 'Gauge', icon: Gauge},
  {value: 'bargauge', label: 'Bar Gauge', icon: BarChartHorizontalBig},
  {value: 'table', label: 'Table', icon: Table2},
  {value: 'toplist', label: 'Top List', icon: List},
  {value: 'heatmap', label: 'Heatmap', icon: Grid3X3},
  {value: 'text', label: 'Text', icon: Type},
]

const DEFAULT_QUERY: QueryDsl = {
  dataSource: 'events',
  metrics: [{function: 'count', alias: 'count'}],
  groupBy: [],
  filters: [],
  limit: 100,
  timeRange: {from: 'now-24h', to: 'now'},
}

function getRefId(index: number): string {
  return String.fromCharCode(65 + index)
}

interface WidgetConfigPanelProps {
  widget: DashboardWidget
  onSave: (widget: DashboardWidget) => void
  onClose: () => void
  dashboardId: number
  projectId?: number
}

export function WidgetConfigPanel({
  widget,
  onSave,
  onClose,
  dashboardId,
  projectId,
}: WidgetConfigPanelProps) {
  const [editedWidget, setEditedWidget] = useState<DashboardWidget>({...widget})
  const [activeTab, setActiveTab] = useState<'query' | 'display' | 'alerts'>('query')
  const [activeQueryIndex, setActiveQueryIndex] = useState(0)
  const [alertThresholdPreview, setAlertThresholdPreview] = useState<AlertThresholdPreview | null>(null)

  const queries = editedWidget.query_configs?.length > 0
    ? editedWidget.query_configs
    : [DEFAULT_QUERY]

  const handleQueryChange = (index: number, queryConfig: QueryDsl) => {
    const updated = [...queries]
    updated[index] = {...queryConfig, ref_id: getRefId(index)}
    setEditedWidget({...editedWidget, query_configs: updated})
  }

  const addQuery = () => {
    const newIndex = queries.length
    const newQuery: QueryDsl = {...DEFAULT_QUERY, ref_id: getRefId(newIndex)}
    setEditedWidget({...editedWidget, query_configs: [...queries, newQuery]})
    setActiveQueryIndex(newIndex)
  }

  const removeQuery = (index: number) => {
    if (queries.length <= 1) return
    const updated = queries.filter((_, i) => i !== index).map((q, i) => ({...q, ref_id: getRefId(i)}))
    setEditedWidget({...editedWidget, query_configs: updated})
    setActiveQueryIndex(Math.min(activeQueryIndex, updated.length - 1))
  }

  return (
    <div className="fixed inset-y-0 right-0 w-[880px] bg-background border-l shadow-xl z-50 flex flex-col">
      {/* Header */}
      <div className="flex items-center justify-between px-4 py-3 border-b">
        <h3 className="font-medium text-sm">Configure Widget</h3>
        <button onClick={onClose} className="p-1 rounded hover:bg-muted">
          <X className="h-4 w-4" />
        </button>
      </div>

      {/* Content */}
      <div className="flex-1 overflow-auto px-4 py-4 space-y-5">
        {/* Title */}
        <div>
          <label className="text-xs font-medium text-muted-foreground mb-1 block">Title</label>
          <input
            className="w-full rounded-md border bg-background px-3 py-1.5 text-sm"
            value={editedWidget.title || ''}
            onChange={(e) => setEditedWidget({...editedWidget, title: e.target.value})}
            placeholder="Widget title"
          />
        </div>

        {/* Widget Type Picker */}
        <div>
          <label className="text-xs font-medium text-muted-foreground mb-2 block">Widget Type</label>
          <div className="grid grid-cols-4 gap-1.5">
            {WIDGET_TYPES.map((wt) => {
              const Icon = wt.icon
              const isSelected = editedWidget.widget_type === wt.value
              return (
                <button
                  key={wt.value}
                  onClick={() => setEditedWidget({...editedWidget, widget_type: wt.value})}
                  className={`flex flex-col items-center gap-1 p-2 rounded-md border text-xs transition-colors ${
                    isSelected
                      ? 'border-primary bg-primary/5 text-primary'
                      : 'border-transparent hover:bg-muted'
                  }`}
                >
                  <Icon className="h-4 w-4" />
                  {wt.label}
                </button>
              )
            })}
          </div>
        </div>

        {/* Tabs */}
        <div className="flex border-b">
          <button
            onClick={() => setActiveTab('query')}
            className={`px-3 py-1.5 text-xs font-medium border-b-2 transition-colors ${
              activeTab === 'query'
                ? 'border-primary text-primary'
                : 'border-transparent text-muted-foreground hover:text-foreground'
            }`}
          >
            Query
          </button>
          <button
            onClick={() => setActiveTab('display')}
            className={`px-3 py-1.5 text-xs font-medium border-b-2 transition-colors ${
              activeTab === 'display'
                ? 'border-primary text-primary'
                : 'border-transparent text-muted-foreground hover:text-foreground'
            }`}
          >
            Display
          </button>
          <button
            onClick={() => setActiveTab('alerts')}
            className={`px-3 py-1.5 text-xs font-medium border-b-2 transition-colors ${
              activeTab === 'alerts'
                ? 'border-primary text-primary'
                : 'border-transparent text-muted-foreground hover:text-foreground'
            }`}
          >
            Alerts
          </button>
        </div>

        {/* Tab Content */}
        {activeTab === 'query' ? (
          <div className="space-y-3">
            {/* Query tabs (A, B, C...) */}
            <div className="flex items-center gap-1">
              {queries.map((_, i) => (
                <div key={i} className="flex items-center">
                  <button
                    onClick={() => setActiveQueryIndex(i)}
                    className={`px-2.5 py-1 text-xs font-medium rounded-t-md transition-colors ${
                      activeQueryIndex === i
                        ? 'bg-primary/10 text-primary border border-b-0 border-primary/20'
                        : 'text-muted-foreground hover:text-foreground hover:bg-muted'
                    }`}
                  >
                    {getRefId(i)}
                  </button>
                  {queries.length > 1 && activeQueryIndex === i && (
                    <button
                      onClick={() => removeQuery(i)}
                      className="p-0.5 text-muted-foreground hover:text-destructive ml-0.5"
                      title="Remove query"
                    >
                      <Trash2 className="h-3 w-3" />
                    </button>
                  )}
                </div>
              ))}
              {queries.length < 10 && (
                <button
                  onClick={addQuery}
                  className="p-1 text-muted-foreground hover:text-foreground rounded hover:bg-muted"
                  title="Add query"
                >
                  <Plus className="h-3.5 w-3.5" />
                </button>
              )}
            </div>
            <QueryBuilderForm
              value={queries[activeQueryIndex] || DEFAULT_QUERY}
              onChange={(q) => handleQueryChange(activeQueryIndex, q)}
            />
          </div>
        ) : activeTab === 'alerts' ? (
          editedWidget.id ? (
            <AlertConfigForm
              dashboardId={dashboardId}
              widgetId={editedWidget.id}
              queryConfigs={queries}
              onPreviewChange={setAlertThresholdPreview}
            />
          ) : (
            <p className="text-xs text-muted-foreground">Save the widget first to configure alerts.</p>
          )
        ) : (
          <DisplayConfigForm
            widget={editedWidget}
            onChange={(updates) => setEditedWidget({...editedWidget, ...updates})}
          />
        )}

        {/* Live Preview */}
        <div>
          <label className="text-xs font-medium text-muted-foreground mb-2 block">Preview</label>
          <div className="h-48 rounded-md border bg-card overflow-hidden">
            <WidgetRenderer
              widget={editedWidget}
              dashboardId={dashboardId}
              projectId={projectId}
              timeRange={queries[0]?.timeRange ?? {from: 'now-24h', to: 'now'}}
              autoRefresh={false}
              alertThresholdPreview={activeTab === 'alerts' ? alertThresholdPreview : null}
            />
          </div>
        </div>
      </div>

      {/* Footer */}
      <div className="flex items-center justify-end gap-2 px-4 py-3 border-t">
        <Button variant="outline" size="sm" onClick={onClose}>
          Cancel
        </Button>
        <Button size="sm" onClick={() => onSave(editedWidget)}>
          Save Widget
        </Button>
      </div>
    </div>
  )
}

function DisplayConfigForm({
  widget,
  onChange,
}: {
  widget: DashboardWidget
  onChange: (updates: Partial<DashboardWidget>) => void
}) {
  const config = widget.display_config || {}
  const wt = widget.widget_type

  const updateConfig = (key: string, value: string) => {
    onChange({display_config: {...config, [key]: value}})
  }

  const isChart = wt === 'timeseries' || wt === 'bar' || wt === 'bargauge'
  const supportsLegend = isChart || wt === 'donut'
  const supportsAxis = wt === 'timeseries' || wt === 'bar'
  const supportsStyle = wt === 'timeseries' || wt === 'bar'
  const supportsThresholds = isChart || wt === 'stat' || wt === 'gauge'
  const supportsMappings = wt === 'stat' || wt === 'gauge' || wt === 'table' || wt === 'toplist'

  // Parse thresholds from JSON string
  const thresholds: {value: number; color: string; label?: string}[] = (() => {
    try { return config.thresholds ? JSON.parse(config.thresholds) : [] }
    catch { return [] }
  })()

  const setThresholds = (t: {value: number; color: string; label?: string}[]) => {
    updateConfig('thresholds', JSON.stringify(t))
  }

  // Parse value mappings from JSON string
  const valueMappings: ValueMapping[] = (() => {
    try { return config.valueMappings ? JSON.parse(config.valueMappings) : [] }
    catch { return [] }
  })()

  const setValueMappings = (m: ValueMapping[]) => {
    updateConfig('valueMappings', JSON.stringify(m))
  }

  return (
    <div className="space-y-4">
      {/* Text content */}
      {wt === 'text' && (
        <div>
          <label className="text-xs font-medium text-muted-foreground mb-1 block">Content (Markdown)</label>
          <textarea
            className="w-full rounded-md border bg-background px-3 py-2 text-sm min-h-[120px] font-mono"
            value={config.content || ''}
            onChange={(e) => updateConfig('content', e.target.value)}
            placeholder="# Heading\n\nYour markdown content here..."
          />
        </div>
      )}

      {/* Color Scheme */}
      <div>
        <label className="text-xs font-medium text-muted-foreground mb-1 block">Color Scheme</label>
        <select
          className="w-full rounded-md border bg-background px-3 py-1.5 text-sm"
          value={config.colorScheme || 'default'}
          onChange={(e) => updateConfig('colorScheme', e.target.value)}
        >
          <option value="default">Default</option>
          <option value="warm">Warm</option>
          <option value="cool">Cool</option>
          <option value="monochrome">Monochrome</option>
        </select>
      </div>

      {/* Legend Configuration */}
      {supportsLegend && (
        <fieldset className="space-y-2">
          <legend className="text-xs font-medium text-muted-foreground">Legend</legend>
          <div>
            <label className="text-xs text-muted-foreground mb-0.5 block">Mode</label>
            <select
              className="w-full rounded-md border bg-background px-3 py-1.5 text-sm"
              value={config.legendMode || 'list'}
              onChange={(e) => updateConfig('legendMode', e.target.value)}
            >
              <option value="list">List</option>
              <option value="table">Table</option>
              <option value="hidden">Hidden</option>
            </select>
          </div>
          {config.legendMode !== 'hidden' && (
            <>
              <div>
                <label className="text-xs text-muted-foreground mb-0.5 block">Placement</label>
                <select
                  className="w-full rounded-md border bg-background px-3 py-1.5 text-sm"
                  value={config.legendPlacement || 'bottom'}
                  onChange={(e) => updateConfig('legendPlacement', e.target.value)}
                >
                  <option value="bottom">Bottom</option>
                  <option value="right">Right</option>
                </select>
              </div>
              <div>
                <label className="text-xs text-muted-foreground mb-0.5 block">Values</label>
                <div className="flex flex-wrap gap-2">
                  {['last', 'min', 'max', 'mean', 'total'].map((v) => {
                    const current = (config.legendValues || '').split(',').filter(Boolean)
                    const checked = current.includes(v)
                    return (
                      <label key={v} className="flex items-center gap-1 text-xs">
                        <input
                          type="checkbox"
                          checked={checked}
                          onChange={() => {
                            const next = checked ? current.filter((c) => c !== v) : [...current, v]
                            updateConfig('legendValues', next.join(','))
                          }}
                          className="rounded"
                        />
                        {v}
                      </label>
                    )
                  })}
                </div>
              </div>
            </>
          )}
        </fieldset>
      )}

      {/* Axis Configuration */}
      {supportsAxis && (
        <fieldset className="space-y-2">
          <legend className="text-xs font-medium text-muted-foreground">Axes</legend>
          <div className="flex items-center gap-2">
            <input
              type="checkbox"
              id="showGrid"
              checked={config.showGrid !== 'false'}
              onChange={(e) => updateConfig('showGrid', String(e.target.checked))}
              className="rounded"
            />
            <label htmlFor="showGrid" className="text-xs">Show Grid</label>
          </div>
          <div>
            <label className="text-xs text-muted-foreground mb-0.5 block">Y-Axis Label</label>
            <input
              className="w-full rounded-md border bg-background px-3 py-1.5 text-sm"
              value={config.yAxisLabel || ''}
              onChange={(e) => updateConfig('yAxisLabel', e.target.value)}
              placeholder="Label"
            />
          </div>
          <div className="grid grid-cols-2 gap-2">
            <div>
              <label className="text-xs text-muted-foreground mb-0.5 block">Y Min</label>
              <input
                className="w-full rounded-md border bg-background px-3 py-1.5 text-sm"
                value={config.yAxisMin || ''}
                onChange={(e) => updateConfig('yAxisMin', e.target.value)}
                placeholder="auto"
              />
            </div>
            <div>
              <label className="text-xs text-muted-foreground mb-0.5 block">Y Max</label>
              <input
                className="w-full rounded-md border bg-background px-3 py-1.5 text-sm"
                value={config.yAxisMax || ''}
                onChange={(e) => updateConfig('yAxisMax', e.target.value)}
                placeholder="auto"
              />
            </div>
          </div>
          <div>
            <label className="text-xs text-muted-foreground mb-0.5 block">Scale</label>
            <select
              className="w-full rounded-md border bg-background px-3 py-1.5 text-sm"
              value={config.yAxisScale || 'linear'}
              onChange={(e) => updateConfig('yAxisScale', e.target.value)}
            >
              <option value="linear">Linear</option>
              <option value="log">Logarithmic</option>
            </select>
          </div>
        </fieldset>
      )}

      {/* Unit Formatting */}
      {(supportsAxis || wt === 'stat' || wt === 'gauge') && (
        <fieldset className="space-y-2">
          <legend className="text-xs font-medium text-muted-foreground">Unit</legend>
          <div>
            <select
              className="w-full rounded-md border bg-background px-3 py-1.5 text-sm"
              value={config.unit || 'none'}
              onChange={(e) => updateConfig('unit', e.target.value)}
            >
              <option value="none">None</option>
              <option value="short">Short (K/M/B)</option>
              <option value="bytes">Bytes</option>
              <option value="bytes/s">Bytes/sec</option>
              <option value="percent">Percent (%)</option>
              <option value="ms">Milliseconds (ms)</option>
              <option value="s">Seconds (s)</option>
              <option value="reqps">Requests/sec</option>
              <option value="ops">Operations/sec</option>
              <option value="locale">Locale Number</option>
              <option value="dateTimeAsIso">DateTime (ISO)</option>
            </select>
          </div>
          <div>
            <label className="text-xs text-muted-foreground mb-0.5 block">Decimals</label>
            <select
              className="w-full rounded-md border bg-background px-3 py-1.5 text-sm"
              value={config.decimals || 'auto'}
              onChange={(e) => updateConfig('decimals', e.target.value)}
            >
              <option value="auto">Auto</option>
              <option value="0">0</option>
              <option value="1">1</option>
              <option value="2">2</option>
              <option value="3">3</option>
            </select>
          </div>
        </fieldset>
      )}

      {/* Chart Styling */}
      {supportsStyle && (
        <fieldset className="space-y-2">
          <legend className="text-xs font-medium text-muted-foreground">Style</legend>
          {wt === 'timeseries' && (
            <>
              <div>
                <label className="text-xs text-muted-foreground mb-0.5 block">Line Width</label>
                <select
                  className="w-full rounded-md border bg-background px-3 py-1.5 text-sm"
                  value={config.lineWidth || '1.5'}
                  onChange={(e) => updateConfig('lineWidth', e.target.value)}
                >
                  <option value="1">1</option>
                  <option value="1.5">1.5</option>
                  <option value="2">2</option>
                  <option value="3">3</option>
                </select>
              </div>
              <div>
                <label className="text-xs text-muted-foreground mb-0.5 block">Fill Opacity</label>
                <select
                  className="w-full rounded-md border bg-background px-3 py-1.5 text-sm"
                  value={config.fillOpacity || '0'}
                  onChange={(e) => updateConfig('fillOpacity', e.target.value)}
                >
                  <option value="0">None</option>
                  <option value="0.1">10%</option>
                  <option value="0.2">20%</option>
                  <option value="0.4">40%</option>
                  <option value="0.6">60%</option>
                </select>
              </div>
              <div>
                <label className="text-xs text-muted-foreground mb-0.5 block">Interpolation</label>
                <select
                  className="w-full rounded-md border bg-background px-3 py-1.5 text-sm"
                  value={config.lineInterpolation || 'monotone'}
                  onChange={(e) => updateConfig('lineInterpolation', e.target.value)}
                >
                  <option value="linear">Linear</option>
                  <option value="monotone">Smooth</option>
                  <option value="step">Step</option>
                </select>
              </div>
              <div>
                <label className="text-xs text-muted-foreground mb-0.5 block">Show Points</label>
                <select
                  className="w-full rounded-md border bg-background px-3 py-1.5 text-sm"
                  value={config.showPoints || 'never'}
                  onChange={(e) => updateConfig('showPoints', e.target.value)}
                >
                  <option value="never">Never</option>
                  <option value="auto">Auto</option>
                  <option value="always">Always</option>
                </select>
              </div>
            </>
          )}
          <div>
            <label className="text-xs text-muted-foreground mb-0.5 block">
              {wt === 'bar' ? 'Bar Mode' : 'Stack Mode'}
            </label>
            <select
              className="w-full rounded-md border bg-background px-3 py-1.5 text-sm"
              value={wt === 'bar' ? (config.barMode || 'grouped') : (config.stackMode || 'none')}
              onChange={(e) => updateConfig(wt === 'bar' ? 'barMode' : 'stackMode', e.target.value)}
            >
              {wt === 'bar' ? (
                <>
                  <option value="grouped">Grouped</option>
                  <option value="stacked">Stacked</option>
                </>
              ) : (
                <>
                  <option value="none">None</option>
                  <option value="normal">Normal</option>
                  <option value="percent">Percent</option>
                </>
              )}
            </select>
          </div>
        </fieldset>
      )}

      {/* Thresholds */}
      {supportsThresholds && (
        <fieldset className="space-y-2">
          <legend className="text-xs font-medium text-muted-foreground">Thresholds</legend>
          {thresholds.map((t, i) => (
            <div key={i} className="flex items-center gap-2">
              <input
                type="number"
                className="w-24 rounded-md border bg-background px-2 py-1 text-sm"
                value={t.value}
                onChange={(e) => {
                  const next = [...thresholds]
                  next[i] = {...next[i], value: parseFloat(e.target.value) || 0}
                  setThresholds(next)
                }}
                placeholder="Value"
              />
              <select
                className="rounded-md border bg-background px-2 py-1 text-sm"
                value={t.color}
                onChange={(e) => {
                  const next = [...thresholds]
                  next[i] = {...next[i], color: e.target.value}
                  setThresholds(next)
                }}
              >
                <option value="#ef4444">Red</option>
                <option value="#f59e0b">Amber</option>
                <option value="#22c55e">Green</option>
                <option value="#3b82f6">Blue</option>
                <option value="#8b5cf6">Violet</option>
              </select>
              <input
                className="flex-1 rounded-md border bg-background px-2 py-1 text-sm"
                value={t.label || ''}
                onChange={(e) => {
                  const next = [...thresholds]
                  next[i] = {...next[i], label: e.target.value}
                  setThresholds(next)
                }}
                placeholder="Label (optional)"
              />
              <button
                onClick={() => setThresholds(thresholds.filter((_, j) => j !== i))}
                className="p-1 text-muted-foreground hover:text-destructive"
              >
                <Trash2 className="h-3 w-3" />
              </button>
            </div>
          ))}
          <button
            onClick={() => setThresholds([...thresholds, {value: 0, color: '#ef4444'}])}
            className="flex items-center gap-1 text-xs text-muted-foreground hover:text-foreground"
          >
            <Plus className="h-3 w-3" /> Add threshold
          </button>
        </fieldset>
      )}

      {/* Value Mappings */}
      {supportsMappings && (
        <fieldset className="space-y-2">
          <legend className="text-xs font-medium text-muted-foreground">Value Mappings</legend>
          {valueMappings.map((m, i) => (
            <div key={i} className="flex items-center gap-2">
              <input
                className="w-20 rounded-md border bg-background px-2 py-1 text-sm"
                value={m.value}
                onChange={(e) => {
                  const next = [...valueMappings]
                  next[i] = {...next[i], value: e.target.value}
                  setValueMappings(next)
                }}
                placeholder="Value"
              />
              <input
                className="flex-1 rounded-md border bg-background px-2 py-1 text-sm"
                value={m.text}
                onChange={(e) => {
                  const next = [...valueMappings]
                  next[i] = {...next[i], text: e.target.value}
                  setValueMappings(next)
                }}
                placeholder="Display text"
              />
              <select
                className="rounded-md border bg-background px-2 py-1 text-sm"
                value={m.color || ''}
                onChange={(e) => {
                  const next = [...valueMappings]
                  next[i] = {...next[i], color: e.target.value || undefined}
                  setValueMappings(next)
                }}
              >
                <option value="">No color</option>
                <option value="#ef4444">Red</option>
                <option value="#f59e0b">Amber</option>
                <option value="#22c55e">Green</option>
                <option value="#3b82f6">Blue</option>
              </select>
              <button
                onClick={() => setValueMappings(valueMappings.filter((_, j) => j !== i))}
                className="p-1 text-muted-foreground hover:text-destructive"
              >
                <Trash2 className="h-3 w-3" />
              </button>
            </div>
          ))}
          <button
            onClick={() => setValueMappings([...valueMappings, {value: '', text: ''}])}
            className="flex items-center gap-1 text-xs text-muted-foreground hover:text-foreground"
          >
            <Plus className="h-3 w-3" /> Add mapping
          </button>
        </fieldset>
      )}

      {/* Stat Accent Color */}
      {wt === 'stat' && (
        <div>
          <label className="text-xs font-medium text-muted-foreground mb-1 block">Accent Color</label>
          <select
            className="w-full rounded-md border bg-background px-3 py-1.5 text-sm"
            value={config.accentColor || ''}
            onChange={(e) => updateConfig('accentColor', e.target.value)}
          >
            <option value="">None</option>
            <option value="blue">Blue</option>
            <option value="green">Green</option>
            <option value="amber">Amber</option>
            <option value="red">Red</option>
            <option value="violet">Violet</option>
          </select>
        </div>
      )}
    </div>
  )
}
