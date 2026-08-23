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

import {Plus, Trash2} from 'lucide-react'
import {Button} from '@/components/ui/button'
import {
  Select,
  SelectContent,
  SelectItem,
  SelectTrigger,
  SelectValue,
} from '@/components/ui/select'
import type {
  AlertRouteOwnershipSource,
  AlertRoutePaging,
  AlertRoutePagingMode,
  AlertRouteTarget,
  AlertRouteTargetKind,
} from '@/lib/api/types'
import {
  OWNERSHIP_SOURCE_OPTIONS,
  PAGING_MODE_OPTIONS,
  TARGET_KIND_OPTIONS,
  createDefaultTarget,
} from './alertRouteModel'

export interface PagingReference {
  id: string
  name: string
}

interface AlertRoutePagingSectionProps {
  paging: AlertRoutePaging
  escalationPolicies: PagingReference[]
  teams: PagingReference[]
  onChange: (paging: AlertRoutePaging) => void
}

// Paging turns a matched alert into a page. Mode NONE hides targets entirely; any
// other mode requires at least one resolvable target (escalation policy, team, or
// an ownership-derived responder).
export function AlertRoutePagingSection({
  paging,
  escalationPolicies,
  teams,
  onChange,
}: Readonly<AlertRoutePagingSectionProps>) {
  const setMode = (mode: AlertRoutePagingMode) =>
    onChange({mode, targets: mode === 'NONE' ? [] : paging.targets})
  const updateTarget = (index: number, next: AlertRouteTarget) =>
    onChange({...paging, targets: paging.targets.map((target, i) => (i === index ? next : target))})
  const removeTarget = (index: number) =>
    onChange({...paging, targets: paging.targets.filter((_, i) => i !== index)})
  const addTarget = () =>
    onChange({...paging, targets: [...paging.targets, createDefaultTarget('ESCALATION_POLICY')]})

  return (
    <div className="space-y-3">
      <div className="max-w-sm">
        <Select value={paging.mode} onValueChange={(value) => setMode(value as AlertRoutePagingMode)}>
          <SelectTrigger className="h-8" aria-label="Paging mode">
            <SelectValue />
          </SelectTrigger>
          <SelectContent>
            {PAGING_MODE_OPTIONS.map((option) => (
              <SelectItem key={option.value} value={option.value}>
                {option.label}
              </SelectItem>
            ))}
          </SelectContent>
        </Select>
      </div>

      {paging.mode !== 'NONE' && (
        <div className="space-y-2">
          {paging.targets.length === 0 && (
            <p className="text-xs text-muted-foreground">
              Add at least one target so matched alerts have somewhere to page.
            </p>
          )}
          {paging.targets.map((target, index) => (
            <TargetRow
              key={index}
              target={target}
              escalationPolicies={escalationPolicies}
              teams={teams}
              onChange={(next) => updateTarget(index, next)}
              onRemove={() => removeTarget(index)}
            />
          ))}
          <Button type="button" variant="outline" size="sm" className="h-8" onClick={addTarget}>
            <Plus className="mr-1.5 h-3.5 w-3.5" />
            Add target
          </Button>
        </div>
      )}
    </div>
  )
}

interface TargetRowProps {
  target: AlertRouteTarget
  escalationPolicies: PagingReference[]
  teams: PagingReference[]
  onChange: (target: AlertRouteTarget) => void
  onRemove: () => void
}

function TargetRow({target, escalationPolicies, teams, onChange, onRemove}: Readonly<TargetRowProps>) {
  return (
    <div className="flex flex-wrap items-center gap-2">
      <div className="min-w-[10rem] flex-1">
        <Select
          value={target.kind}
          onValueChange={(value) => onChange(createDefaultTarget(value as AlertRouteTargetKind))}
        >
          <SelectTrigger className="h-8" aria-label="Target kind">
            <SelectValue />
          </SelectTrigger>
          <SelectContent>
            {TARGET_KIND_OPTIONS.map((option) => (
              <SelectItem key={option.value} value={option.value}>
                {option.label}
              </SelectItem>
            ))}
          </SelectContent>
        </Select>
      </div>

      <div className="min-w-[10rem] flex-[2]">
        {target.kind === 'ESCALATION_POLICY' && (
          <ReferenceSelect
            ariaLabel="Escalation policy"
            placeholder={escalationPolicies.length === 0 ? 'No escalation policies' : 'Select a policy'}
            value={target.escalationPolicyId ?? ''}
            options={escalationPolicies}
            onChange={(id) => onChange({kind: 'ESCALATION_POLICY', escalationPolicyId: id})}
          />
        )}
        {target.kind === 'TEAM' && (
          <ReferenceSelect
            ariaLabel="Team"
            placeholder={teams.length === 0 ? 'No teams' : 'Select a team'}
            value={target.teamId ?? ''}
            options={teams}
            onChange={(id) => onChange({kind: 'TEAM', teamId: id})}
          />
        )}
        {target.kind === 'OWNERSHIP_DERIVED' && (
          <Select
            value={target.ownershipSource ?? 'SERVICE'}
            onValueChange={(value) =>
              onChange({kind: 'OWNERSHIP_DERIVED', ownershipSource: value as AlertRouteOwnershipSource})
            }
          >
            <SelectTrigger className="h-8" aria-label="Ownership source">
              <SelectValue />
            </SelectTrigger>
            <SelectContent>
              {OWNERSHIP_SOURCE_OPTIONS.map((option) => (
                <SelectItem key={option.value} value={option.value}>
                  {option.label}
                </SelectItem>
              ))}
            </SelectContent>
          </Select>
        )}
      </div>

      <Button
        type="button"
        variant="ghost"
        size="icon"
        className="h-8 w-8 shrink-0"
        onClick={onRemove}
        aria-label="Remove target"
      >
        <Trash2 className="h-3.5 w-3.5" />
      </Button>
    </div>
  )
}

interface ReferenceSelectProps {
  ariaLabel: string
  placeholder: string
  value: string
  options: PagingReference[]
  onChange: (id: string) => void
}

function ReferenceSelect({ariaLabel, placeholder, value, options, onChange}: Readonly<ReferenceSelectProps>) {
  return (
    <Select value={value} onValueChange={onChange} disabled={options.length === 0}>
      <SelectTrigger className="h-8" aria-label={ariaLabel}>
        <SelectValue placeholder={placeholder} />
      </SelectTrigger>
      <SelectContent>
        {options.map((option) => (
          <SelectItem key={option.id} value={option.id}>
            {option.name}
          </SelectItem>
        ))}
      </SelectContent>
    </Select>
  )
}
