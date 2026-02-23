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
import type {DashboardVariable, TimeRangeDef} from '@/lib/api'
import {Pencil, Plus, Save, Download, Clock, RefreshCw, ArrowLeft, Settings2} from 'lucide-react'
import {Link} from '@tanstack/react-router'

interface DashboardToolbarProps {
  title: string
  isEditing: boolean
  onToggleEdit: () => void
  onSave: () => void
  onTitleChange: (title: string) => void
  onAddWidget: () => void
  onExport: () => void
  timeRange: TimeRangeDef
  onTimeRangeChange: (range: TimeRangeDef) => void
  autoRefresh: boolean
  onAutoRefreshChange: (enabled: boolean) => void
  variables?: DashboardVariable[]
  variableValues: Record<string, string>
  onVariableChange: (name: string, value: string) => void
  onVariableSettings?: () => void
}

const TIME_RANGE_PRESETS = [
  {label: '15m', from: 'now-15m', to: 'now'},
  {label: '1h', from: 'now-1h', to: 'now'},
  {label: '4h', from: 'now-4h', to: 'now'},
  {label: '24h', from: 'now-24h', to: 'now'},
  {label: '7d', from: 'now-7d', to: 'now'},
  {label: '30d', from: 'now-30d', to: 'now'},
]

export function DashboardToolbar({
  title,
  isEditing,
  onToggleEdit,
  onSave,
  onTitleChange,
  onAddWidget,
  onExport,
  timeRange,
  onTimeRangeChange,
  autoRefresh,
  onAutoRefreshChange,
  variables,
  variableValues,
  onVariableChange,
  onVariableSettings,
}: DashboardToolbarProps) {
  const [editingTitle, setEditingTitle] = useState(false)
  const [titleValue, setTitleValue] = useState(title)

  const handleTitleSave = () => {
    if (titleValue.trim() && titleValue !== title) {
      onTitleChange(titleValue.trim())
    }
    setEditingTitle(false)
  }

  return (
    <div className="flex items-center justify-between gap-4 flex-wrap">
      <div className="flex items-center gap-3">
        <Link
          to="/dashboards"
          className="p-1.5 rounded hover:bg-muted text-muted-foreground hover:text-foreground transition-colors"
        >
          <ArrowLeft className="h-4 w-4" />
        </Link>

        {editingTitle && isEditing ? (
          <input
            className="text-lg font-semibold bg-transparent border-b border-primary outline-none px-0"
            value={titleValue}
            onChange={(e) => setTitleValue(e.target.value)}
            onBlur={handleTitleSave}
            onKeyDown={(e) => e.key === 'Enter' && handleTitleSave()}
            autoFocus
          />
        ) : (
          <h2
            className={`text-lg font-semibold ${isEditing ? 'cursor-pointer hover:text-primary' : ''}`}
            onClick={() => isEditing && setEditingTitle(true)}
          >
            {title}
          </h2>
        )}
      </div>

      {/* Variable selectors */}
      {variables && variables.length > 0 && (
        <div className="flex items-center gap-2 flex-wrap">
          {variables.map((v) => (
            <div key={v.name} className="flex items-center gap-1">
              <label className="text-xs text-muted-foreground whitespace-nowrap">
                {v.label || v.name}:
              </label>
              {v.type === 'textbox' ? (
                <input
                  className="h-7 px-2 text-xs border rounded-md bg-background w-24"
                  value={variableValues[v.name] ?? v.current ?? v.default_value ?? ''}
                  onChange={(e) => onVariableChange(v.name, e.target.value)}
                  placeholder={v.name}
                />
              ) : (
                <select
                  className="h-7 px-2 text-xs border rounded-md bg-background"
                  value={variableValues[v.name] ?? v.current ?? v.default_value ?? ''}
                  onChange={(e) => onVariableChange(v.name, e.target.value)}
                >
                  {v.options.length > 0 ? (
                    v.options.map((opt) => (
                      <option key={opt} value={opt}>{opt}</option>
                    ))
                  ) : (
                    <option value={variableValues[v.name] ?? v.current ?? ''}>
                      {variableValues[v.name] ?? v.current ?? '(none)'}
                    </option>
                  )}
                </select>
              )}
            </div>
          ))}
          {isEditing && onVariableSettings && (
            <Button variant="ghost" size="sm" className="h-7 w-7 p-0" onClick={onVariableSettings} title="Edit variables">
              <Settings2 className="h-3.5 w-3.5" />
            </Button>
          )}
        </div>
      )}

      <div className="flex items-center gap-2">
        {/* Time range selector */}
        <div className="flex items-center border rounded-md overflow-hidden">
          <div className="flex items-center gap-1 px-2 border-r bg-muted/30">
            <Clock className="h-3 w-3 text-muted-foreground" />
          </div>
          {TIME_RANGE_PRESETS.map((preset) => (
            <button
              key={preset.label}
              onClick={() => onTimeRangeChange({from: preset.from, to: preset.to})}
              className={`px-2 py-1 text-xs transition-colors ${
                timeRange.from === preset.from
                  ? 'bg-primary text-primary-foreground'
                  : 'hover:bg-muted'
              }`}
            >
              {preset.label}
            </button>
          ))}
        </div>

        {/* Auto-refresh toggle */}
        <Button
          variant={autoRefresh ? 'default' : 'outline'}
          size="sm"
          onClick={() => onAutoRefreshChange(!autoRefresh)}
          className="h-7"
        >
          <RefreshCw className={`h-3 w-3 ${autoRefresh ? 'animate-spin' : ''}`} />
        </Button>

        {/* Action buttons */}
        {isEditing ? (
          <>
            {onVariableSettings && (
              <Button variant="outline" size="sm" onClick={onVariableSettings} className="h-7">
                <Settings2 className="h-3 w-3 mr-1" /> Variables
              </Button>
            )}
            <Button variant="outline" size="sm" onClick={onAddWidget} className="h-7">
              <Plus className="h-3 w-3 mr-1" /> Widget
            </Button>
            <Button size="sm" onClick={onSave} className="h-7">
              <Save className="h-3 w-3 mr-1" /> Done
            </Button>
          </>
        ) : (
          <>
            <Button variant="outline" size="sm" onClick={onExport} className="h-7">
              <Download className="h-3 w-3 mr-1" /> Export
            </Button>
            <Button variant="outline" size="sm" onClick={onToggleEdit} className="h-7">
              <Pencil className="h-3 w-3 mr-1" /> Edit
            </Button>
          </>
        )}
      </div>
    </div>
  )
}
