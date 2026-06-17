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

import type {DetectionRuleResponse} from '@/lib/api'
import {Badge} from '@/components/ui/badge'
import {Button} from '@/components/ui/button'
import {Switch} from '@/components/ui/switch'
import {cn} from '@/lib/utils'
import {colorFor, severityColors} from './securityChips'

const TYPE_LABELS: Record<string, string> = {
  threshold: 'Threshold',
  new_value: 'New value',
  rate_anomaly: 'Rate anomaly',
}

interface RuleListProps {
  rules: DetectionRuleResponse[]
  onEdit: (rule: DetectionRuleResponse) => void
  onToggle: (rule: DetectionRuleResponse, enabled: boolean) => void
  onDelete: (rule: DetectionRuleResponse) => void
  togglingId?: string
}

export function RuleList({rules, onEdit, onToggle, onDelete, togglingId}: RuleListProps) {
  return (
    <div className="overflow-x-auto">
      <table className="w-full text-xs">
        <thead>
          <tr className="border-b text-left text-muted-foreground">
            <th className="px-4 py-2 font-medium">Name</th>
            <th className="px-4 py-2 font-medium">Type</th>
            <th className="px-4 py-2 font-medium">Severity</th>
            <th className="px-4 py-2 font-medium">Enabled</th>
            <th className="px-4 py-2 font-medium">Updated</th>
            <th className="px-4 py-2 font-medium text-right">Actions</th>
          </tr>
        </thead>
        <tbody>
          {rules.map((rule) => (
            <tr key={rule.id} className="border-b last:border-0 hover:bg-muted/30">
              <td className="px-4 py-2">
                <button
                  type="button"
                  onClick={() => onEdit(rule)}
                  className="text-left font-medium hover:underline">
                  {rule.name}
                </button>
                {rule.description && (
                  <div className="text-[10px] text-muted-foreground">{rule.description}</div>
                )}
              </td>
              <td className="px-4 py-2 text-muted-foreground">{TYPE_LABELS[rule.type] ?? rule.type}</td>
              <td className="px-4 py-2">
                <Badge variant="outline" className={cn('text-[10px]', colorFor(severityColors, rule.severity))}>
                  {rule.severity}
                </Badge>
              </td>
              <td className="px-4 py-2">
                <Switch
                  checked={rule.enabled}
                  disabled={togglingId === rule.id}
                  onCheckedChange={(checked) => onToggle(rule, checked)}
                  aria-label={`Toggle ${rule.name}`}
                />
              </td>
              <td className="px-4 py-2 text-muted-foreground">{rule.updated_at}</td>
              <td className="px-4 py-2 text-right">
                <Button size="sm" variant="ghost" onClick={() => onEdit(rule)}>
                  Edit
                </Button>
                <Button
                  size="sm"
                  variant="ghost"
                  className="text-destructive hover:text-destructive"
                  onClick={() => onDelete(rule)}>
                  Delete
                </Button>
              </td>
            </tr>
          ))}
          {rules.length === 0 && (
            <tr>
              <td colSpan={6} className="py-6 text-center text-muted-foreground">
                No detection rules
              </td>
            </tr>
          )}
        </tbody>
      </table>
    </div>
  )
}
