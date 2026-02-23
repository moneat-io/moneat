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
import {X, BarChart3, LineChart, PieChart, Hash, Table2, List, Grid3X3, Type} from 'lucide-react'

const WIDGET_TYPES = [
  {value: 'timeseries', label: 'Timeseries', icon: LineChart},
  {value: 'bar', label: 'Bar Chart', icon: BarChart3},
  {value: 'donut', label: 'Donut', icon: PieChart},
  {value: 'stat', label: 'Stat', icon: Hash},
  {value: 'table', label: 'Table', icon: Table2},
  {value: 'toplist', label: 'Top List', icon: List},
  {value: 'heatmap', label: 'Heatmap', icon: Grid3X3},
  {value: 'text', label: 'Text', icon: Type},
]

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
  const [activeTab, setActiveTab] = useState<'query' | 'display'>('query')

  const handleQueryChange = (queryConfig: QueryDsl) => {
    setEditedWidget({...editedWidget, query_config: queryConfig})
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
        </div>

        {/* Tab Content */}
        {activeTab === 'query' ? (
          <QueryBuilderForm value={editedWidget.query_config} onChange={handleQueryChange} />
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
              timeRange={editedWidget.query_config?.timeRange ?? {from: 'now-24h', to: 'now'}}
              autoRefresh={false}
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

  const updateConfig = (key: string, value: string) => {
    onChange({display_config: {...config, [key]: value}})
  }

  return (
    <div className="space-y-3">
      {widget.widget_type === 'text' && (
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

      {(widget.widget_type === 'timeseries' || widget.widget_type === 'bar') && (
        <>
          <div className="flex items-center gap-2">
            <input
              type="checkbox"
              id="showLegend"
              checked={config.showLegend !== 'false'}
              onChange={(e) => updateConfig('showLegend', String(e.target.checked))}
              className="rounded"
            />
            <label htmlFor="showLegend" className="text-xs">Show Legend</label>
          </div>
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
        </>
      )}

      {widget.widget_type === 'stat' && (
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
