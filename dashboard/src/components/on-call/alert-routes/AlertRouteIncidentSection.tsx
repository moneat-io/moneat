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

import {Input} from '@/components/ui/input'
import {Label} from '@/components/ui/label'
import {Switch} from '@/components/ui/switch'
import {Textarea} from '@/components/ui/textarea'
import {
  Select,
  SelectContent,
  SelectItem,
  SelectTrigger,
  SelectValue,
} from '@/components/ui/select'
import type {
  AlertRouteIncidentConfig,
  AlertRouteIncidentMode,
  AlertRouteRecovery,
} from '@/lib/api/types'
import {ALERT_PRIORITY_SEVERITY, INCIDENT_MODE_OPTIONS, SEVERITY_OPTIONS} from './alertRouteModel'
import {DurationField} from './DurationField'

const NONE_VALUE = '__none__'

export interface IncidentTypeOption {
  value: string
  label: string
}

interface AlertRouteIncidentSectionProps {
  incident: AlertRouteIncidentConfig
  recovery: AlertRouteRecovery
  incidentTypeOptions: IncidentTypeOption[]
  onIncidentChange: (incident: AlertRouteIncidentConfig) => void
  onRecoveryChange: (recovery: AlertRouteRecovery) => void
}

// Incident behavior and recovery automation. When incident creation is off the
// template fields collapse; recovery still applies to the route's grouped alerts.
export function AlertRouteIncidentSection({
  incident,
  recovery,
  incidentTypeOptions,
  onIncidentChange,
  onRecoveryChange,
}: Readonly<AlertRouteIncidentSectionProps>) {
  const activeRequiresSeverity = incident.mode === 'ACTIVE'
  // Keep an unknown stored type visible so editing an existing route never
  // silently drops a type that is no longer in the configured list.
  const typeOptions =
    incident.incidentType && !incidentTypeOptions.some((option) => option.value === incident.incidentType)
      ? [...incidentTypeOptions, {value: incident.incidentType, label: incident.incidentType}]
      : incidentTypeOptions

  return (
    <div className="space-y-4">
      <label className="flex items-center justify-between gap-4 rounded-lg border p-3">
        <span className="min-w-0">
          <span className="block text-sm font-medium">Create an incident for matched alerts</span>
          <span className="mt-0.5 block text-xs text-muted-foreground">
            Enable this on an enabled, matching route to open incidents automatically.
          </span>
        </span>
        <Switch
          checked={incident.create}
          onCheckedChange={(create) => onIncidentChange({...incident, create})}
          aria-label="Create an incident for matched alerts"
        />
      </label>

      {!incident.create && (
        <p
          className="rounded-md border border-warning-border bg-warning-bg px-3 py-2 text-xs text-warning-fg"
          role="status"
        >
          This route will not create incidents while this option is off. Matching alerts may still
          be grouped or paged.
        </p>
      )}

      {incident.create && (
        <div className="space-y-4 border-l-2 border-muted pl-4">
          <div className="space-y-1.5">
            <Label htmlFor="incident-mode">Open as</Label>
            <Select
              value={incident.mode}
              onValueChange={(value) =>
                onIncidentChange({...incident, mode: value as AlertRouteIncidentMode})
              }
            >
              <SelectTrigger id="incident-mode" className="h-8">
                <SelectValue />
              </SelectTrigger>
              <SelectContent>
                {INCIDENT_MODE_OPTIONS.map((option) => (
                  <SelectItem key={option.value} value={option.value}>
                    {option.label}
                  </SelectItem>
                ))}
              </SelectContent>
            </Select>
          </div>

          <div className="grid grid-cols-1 gap-3 sm:grid-cols-2">
            <div className="space-y-1.5">
              <Label htmlFor="incident-severity">
                Severity{activeRequiresSeverity ? '' : ' (optional)'}
              </Label>
              <Select
                value={incident.severity ?? NONE_VALUE}
                onValueChange={(value) =>
                  onIncidentChange({...incident, severity: value === NONE_VALUE ? null : value})
                }
              >
                <SelectTrigger id="incident-severity" className="h-8">
                  <SelectValue placeholder="Select a severity" />
                </SelectTrigger>
                <SelectContent>
                  {!activeRequiresSeverity && <SelectItem value={NONE_VALUE}>Leave unclassified</SelectItem>}
                  {SEVERITY_OPTIONS.map((option) => (
                    <SelectItem key={option.value} value={option.value}>
                      {option.label}
                    </SelectItem>
                  ))}
                </SelectContent>
              </Select>
              {activeRequiresSeverity && (
                <p className="text-xs text-muted-foreground">Active incidents require a severity.</p>
              )}
              {incident.severity === ALERT_PRIORITY_SEVERITY && (
                <p className="text-xs text-muted-foreground">
                  P0, P1, and P2 alerts become SEV-0, SEV-1, and SEV-2 triage incidents. Other priorities stay unclassified.
                </p>
              )}
            </div>

            <div className="space-y-1.5">
              <Label htmlFor="incident-type">Incident type (optional)</Label>
              <Select
                value={incident.incidentType ?? NONE_VALUE}
                onValueChange={(value) =>
                  onIncidentChange({...incident, incidentType: value === NONE_VALUE ? null : value})
                }
              >
                <SelectTrigger id="incident-type" className="h-8">
                  <SelectValue placeholder="No type" />
                </SelectTrigger>
                <SelectContent>
                  <SelectItem value={NONE_VALUE}>No type</SelectItem>
                  {typeOptions.map((option) => (
                    <SelectItem key={option.value} value={option.value}>
                      {option.label}
                    </SelectItem>
                  ))}
                </SelectContent>
              </Select>
            </div>
          </div>

          <div className="space-y-1.5">
            <Label htmlFor="incident-title-template">Title template (optional)</Label>
            <Input
              id="incident-title-template"
              className="h-8"
              value={incident.titleTemplate ?? ''}
              placeholder="{{alert.title}}"
              onChange={(event) =>
                onIncidentChange({...incident, titleTemplate: event.target.value})
              }
            />
          </div>

          <div className="space-y-1.5">
            <Label htmlFor="incident-summary-template">Summary template (optional)</Label>
            <Textarea
              id="incident-summary-template"
              rows={2}
              value={incident.summaryTemplate ?? ''}
              placeholder="{{alert.description}}"
              onChange={(event) =>
                onIncidentChange({...incident, summaryTemplate: event.target.value})
              }
            />
            <p className="text-xs text-muted-foreground">
              Templates may reference approved alert attributes. Unknown references are surfaced in
              the preview.
            </p>
          </div>
        </div>
      )}

      <RecoveryEditor recovery={recovery} onChange={onRecoveryChange} />
    </div>
  )
}

interface RecoveryEditorProps {
  recovery: AlertRouteRecovery
  onChange: (recovery: AlertRouteRecovery) => void
}

function RecoveryEditor({recovery, onChange}: Readonly<RecoveryEditorProps>) {
  return (
    <div className="space-y-3 rounded-lg border p-3">
      <p className="text-sm font-medium">Recovery</p>
      <label className="flex items-center justify-between gap-4">
        <span className="text-sm">Cancel active escalations when the group recovers</span>
        <Switch
          checked={recovery.cancelEscalations}
          onCheckedChange={(cancelEscalations) => onChange({...recovery, cancelEscalations})}
          aria-label="Cancel active escalations when the group recovers"
        />
      </label>
      <label className="flex items-center justify-between gap-4">
        <span className="text-sm">Decline triage incidents that were never accepted</span>
        <Switch
          checked={recovery.declineUnacceptedTriage}
          onCheckedChange={(declineUnacceptedTriage) => onChange({...recovery, declineUnacceptedTriage})}
          aria-label="Decline triage incidents that were never accepted"
        />
      </label>
      <div className="space-y-1.5">
        <Label htmlFor="recovery-grace">Grace period before recovery</Label>
        <DurationField
          id="recovery-grace"
          ariaLabel="Recovery grace"
          seconds={recovery.graceSeconds}
          minSeconds={0}
          onChange={(graceSeconds) => onChange({...recovery, graceSeconds})}
        />
        <p className="text-xs text-muted-foreground">
          Wait this long after the last alert resolves before running recovery.
        </p>
      </div>
    </div>
  )
}
