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
import {X} from 'lucide-react'
import {Button} from '@/components/ui/button'
import {Input} from '@/components/ui/input'
import {Label} from '@/components/ui/label'
import {
  Select,
  SelectContent,
  SelectItem,
  SelectTrigger,
  SelectValue,
} from '@/components/ui/select'
import type {
  AlertRouteGrouping,
  AlertRouteGroupingBehavior,
  AlertRouteGroupingWindowKind,
} from '@/lib/api/types'
import {
  GROUPING_BEHAVIOR_OPTIONS,
  MAX_GROUPING_KEYS,
  WINDOW_KIND_OPTIONS,
} from './alertRouteModel'
import {DurationField} from './DurationField'

interface AlertRouteGroupingSectionProps {
  grouping: AlertRouteGrouping
  onChange: (grouping: AlertRouteGrouping) => void
}

// Grouping folds related alerts into one group within a time window. With no keys
// each alert is its own singleton group; keys collapse alerts that share those
// attribute values.
export function AlertRouteGroupingSection({grouping, onChange}: Readonly<AlertRouteGroupingSectionProps>) {
  return (
    <div className="space-y-4">
      <div className="space-y-1.5">
        <Label>Grouping keys</Label>
        <GroupingKeysEditor
          keys={grouping.keys}
          onChange={(keys) => onChange({...grouping, keys})}
        />
        <p className="text-xs text-muted-foreground">
          Alerts that share these attribute values join the same group. Leave empty to keep every
          alert separate. Use scoped keys such as <span className="font-mono">ownership.service</span>,{' '}
          <span className="font-mono">metadata.environment</span>, or{' '}
          <span className="font-mono">alert.deduplication_key</span>. Up to {MAX_GROUPING_KEYS} keys.
        </p>
      </div>

      <div className="grid grid-cols-1 gap-3 sm:grid-cols-2">
        <div className="space-y-1.5">
          <Label htmlFor="grouping-behavior">Behavior</Label>
          <Select
            value={grouping.behavior}
            onValueChange={(value) =>
              onChange({...grouping, behavior: value as AlertRouteGroupingBehavior})
            }
          >
            <SelectTrigger id="grouping-behavior" className="h-8">
              <SelectValue />
            </SelectTrigger>
            <SelectContent>
              {GROUPING_BEHAVIOR_OPTIONS.map((option) => (
                <SelectItem key={option.value} value={option.value}>
                  {option.label}
                </SelectItem>
              ))}
            </SelectContent>
          </Select>
        </div>

        <div className="space-y-1.5">
          <Label htmlFor="grouping-window-kind">Window</Label>
          <Select
            value={grouping.windowKind}
            onValueChange={(value) =>
              onChange({...grouping, windowKind: value as AlertRouteGroupingWindowKind})
            }
          >
            <SelectTrigger id="grouping-window-kind" className="h-8">
              <SelectValue />
            </SelectTrigger>
            <SelectContent>
              {WINDOW_KIND_OPTIONS.map((option) => (
                <SelectItem key={option.value} value={option.value}>
                  {option.label}
                </SelectItem>
              ))}
            </SelectContent>
          </Select>
        </div>
      </div>

      <div className="space-y-1.5">
        <Label htmlFor="grouping-window-seconds">Window length</Label>
        <DurationField
          id="grouping-window-seconds"
          ariaLabel="Grouping window"
          seconds={grouping.windowSeconds}
          minSeconds={1}
          onChange={(windowSeconds) => onChange({...grouping, windowSeconds})}
        />
      </div>
    </div>
  )
}

interface GroupingKeysEditorProps {
  keys: string[]
  onChange: (keys: string[]) => void
}

function GroupingKeysEditor({keys, onChange}: Readonly<GroupingKeysEditorProps>) {
  const [draft, setDraft] = useState('')
  const atLimit = keys.length >= MAX_GROUPING_KEYS

  const addKey = () => {
    const next = draft.trim().toLowerCase()
    if (!next || keys.includes(next) || atLimit) {
      setDraft('')
      return
    }
    onChange([...keys, next])
    setDraft('')
  }

  const removeKey = (key: string) => onChange(keys.filter((existing) => existing !== key))

  return (
    <div className="space-y-2">
      {keys.length > 0 && (
        <div className="flex flex-wrap gap-1.5">
          {keys.map((key) => (
            <span
              key={key}
              className="inline-flex items-center gap-1 rounded-md border bg-muted px-1.5 py-0.5 text-xs font-mono"
            >
              {key}
              <button
                type="button"
                onClick={() => removeKey(key)}
                aria-label={`Remove grouping key ${key}`}
                className="text-muted-foreground hover:text-foreground"
              >
                <X className="h-3 w-3" />
              </button>
            </span>
          ))}
        </div>
      )}
      <div className="flex items-center gap-2">
        <Input
          className="h-8 max-w-xs font-mono"
          aria-label="Add grouping key"
          placeholder={atLimit ? 'Key limit reached' : 'e.g. ownership.service'}
          value={draft}
          disabled={atLimit}
          onChange={(event) => setDraft(event.target.value)}
          onKeyDown={(event) => {
            if (event.key === 'Enter' || event.key === ',') {
              event.preventDefault()
              addKey()
            }
          }}
        />
        <Button type="button" variant="outline" size="sm" className="h-8" onClick={addKey} disabled={atLimit || draft.trim() === ''}>
          Add
        </Button>
      </div>
    </div>
  )
}
