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
import {Input} from '@/components/ui/input'
import {Separator} from '@/components/ui/separator'
import {Tooltip, TooltipContent, TooltipProvider, TooltipTrigger} from '@/components/ui/tooltip'
import {Select, SelectContent, SelectItem, SelectTrigger, SelectValue} from '@/components/ui/select'
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
    <TooltipProvider delayDuration={300}>
      <div className="flex items-center gap-3 rounded-lg border bg-card/50 backdrop-blur-sm px-4 py-2.5 shadow-sm">
        {/* Navigation + Title */}
        <div className="flex items-center gap-2.5 min-w-0">
          <Tooltip>
            <TooltipTrigger asChild>
              <Link
                to="/dashboards"
                className="inline-flex items-center justify-center h-8 w-8 rounded-md text-muted-foreground hover:text-foreground hover:bg-muted transition-colors shrink-0"
              >
                <ArrowLeft className="h-4 w-4" />
              </Link>
            </TooltipTrigger>
            <TooltipContent>Back to dashboards</TooltipContent>
          </Tooltip>

          {editingTitle && isEditing ? (
            <input
              className="text-base font-semibold bg-transparent border-b-2 border-primary outline-none min-w-0"
              value={titleValue}
              onChange={(e) => setTitleValue(e.target.value)}
              onBlur={handleTitleSave}
              onKeyDown={(e) => e.key === 'Enter' && handleTitleSave()}
              autoFocus
            />
          ) : (
            <h2
              className={`text-base font-semibold truncate ${isEditing ? 'cursor-pointer hover:text-primary transition-colors' : ''}`}
              onClick={() => isEditing && setEditingTitle(true)}
            >
              {title}
            </h2>
          )}
        </div>

        {/* Variable selectors */}
        {variables && variables.length > 0 && (
          <>
            <Separator orientation="vertical" className="h-6" />
            <div className="flex items-center gap-3 flex-wrap">
              {variables.map((v) => (
                <div key={v.name} className="flex items-center gap-1.5">
                  <label className="text-xs font-medium text-muted-foreground whitespace-nowrap">
                    {v.label || v.name}
                  </label>
                  {v.type === 'textbox' ? (
                    <Input
                      className="h-7 w-28 text-xs"
                      value={variableValues[v.name] ?? v.current ?? v.default_value ?? ''}
                      onChange={(e) => onVariableChange(v.name, e.target.value)}
                      placeholder={v.name}
                    />
                  ) : (
                    <Select
                      value={variableValues[v.name] ?? v.current ?? v.default_value ?? ''}
                      onValueChange={(val) => onVariableChange(v.name, val)}
                    >
                      <SelectTrigger className="h-7 text-xs min-w-[6rem]">
                        <SelectValue placeholder="(none)" />
                      </SelectTrigger>
                      <SelectContent>
                        {v.options.length > 0 ? (
                          <>
                            {(v.current === '$__all' || v.default_value === '$__all') && (
                              <SelectItem value="$__all">All</SelectItem>
                            )}
                            {v.options.map((opt) => (
                              <SelectItem key={opt} value={opt}>{opt}</SelectItem>
                            ))}
                          </>
                        ) : (
                          <SelectItem value={variableValues[v.name] ?? v.current ?? '__none__'}>
                            {variableValues[v.name] ?? v.current ?? '(none)'}
                          </SelectItem>
                        )}
                      </SelectContent>
                    </Select>
                  )}
                </div>
              ))}
              {isEditing && onVariableSettings && (
                <Tooltip>
                  <TooltipTrigger asChild>
                    <Button variant="ghost" size="icon" className="h-7 w-7" onClick={onVariableSettings}>
                      <Settings2 className="h-3.5 w-3.5" />
                    </Button>
                  </TooltipTrigger>
                  <TooltipContent>Edit variables</TooltipContent>
                </Tooltip>
              )}
            </div>
          </>
        )}

        <div className="flex-1" />

        {/* Time range selector */}
        <div className="flex items-center h-8 rounded-md border bg-muted/40 overflow-hidden">
          <div className="flex items-center px-2 h-full border-r border-border/50">
            <Clock className="h-3.5 w-3.5 text-muted-foreground" />
          </div>
          {TIME_RANGE_PRESETS.map((preset) => {
            const isActive = timeRange.from === preset.from
            return (
              <button
                key={preset.label}
                onClick={() => onTimeRangeChange({from: preset.from, to: preset.to})}
                className={`px-2.5 h-full text-xs font-medium transition-all ${
                  isActive
                    ? 'bg-primary text-primary-foreground shadow-sm'
                    : 'text-muted-foreground hover:text-foreground hover:bg-muted'
                }`}
              >
                {preset.label}
              </button>
            )
          })}
        </div>

        {/* Auto-refresh toggle */}
        <Tooltip>
          <TooltipTrigger asChild>
            <Button
              variant={autoRefresh ? 'default' : 'outline'}
              size="icon"
              onClick={() => onAutoRefreshChange(!autoRefresh)}
              className="h-8 w-8"
            >
              <RefreshCw className={`h-3.5 w-3.5 ${autoRefresh ? 'animate-spin' : ''}`} />
            </Button>
          </TooltipTrigger>
          <TooltipContent>{autoRefresh ? 'Disable auto-refresh' : 'Enable auto-refresh'}</TooltipContent>
        </Tooltip>

        <Separator orientation="vertical" className="h-6" />

        {/* Action buttons */}
        {isEditing ? (
          <div className="flex items-center gap-1.5">
            {onVariableSettings && (
              <Button variant="outline" size="sm" onClick={onVariableSettings} className="h-8 text-xs">
                <Settings2 className="h-3.5 w-3.5 mr-1.5" />
                Variables
              </Button>
            )}
            <Button variant="outline" size="sm" onClick={onAddWidget} className="h-8 text-xs">
              <Plus className="h-3.5 w-3.5 mr-1.5" />
              Widget
            </Button>
            <Button size="sm" onClick={onSave} className="h-8 text-xs">
              <Save className="h-3.5 w-3.5 mr-1.5" />
              Done
            </Button>
          </div>
        ) : (
          <div className="flex items-center gap-1.5">
            <Tooltip>
              <TooltipTrigger asChild>
                <Button variant="ghost" size="icon" onClick={onExport} className="h-8 w-8">
                  <Download className="h-3.5 w-3.5" />
                </Button>
              </TooltipTrigger>
              <TooltipContent>Export dashboard</TooltipContent>
            </Tooltip>
            <Button variant="outline" size="sm" onClick={onToggleEdit} className="h-8 text-xs">
              <Pencil className="h-3.5 w-3.5 mr-1.5" />
              Edit
            </Button>
          </div>
        )}
      </div>
    </TooltipProvider>
  )
}
