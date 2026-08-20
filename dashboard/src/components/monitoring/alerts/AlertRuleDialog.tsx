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

// ─────────────────────────────────────────────────────────────────────────────
// One dialog for both creating and editing a threshold rule. The form is
// controlled so the live sentence preview at the bottom stays in step with the
// inputs — the preview is the thing reviewers read, not the field labels.
// ─────────────────────────────────────────────────────────────────────────────

import {useState, type FormEventHandler} from 'react'
import {BellRing} from 'lucide-react'

import type {HostAlert} from '@/lib/api'
import {Button} from '@/components/ui/button'
import {
  Dialog,
  DialogContent,
  DialogDescription,
  DialogFooter,
  DialogHeader,
  DialogTitle,
} from '@/components/ui/dialog'
import {Input} from '@/components/ui/input'
import {Label} from '@/components/ui/label'
import {
  Select,
  SelectContent,
  SelectGroup,
  SelectItem,
  SelectLabel,
  SelectTrigger,
  SelectValue,
} from '@/components/ui/select'
import {Switch} from '@/components/ui/switch'
import {
  ALERT_CONDITION_OPTIONS,
  ALERT_METRIC_GROUPS,
  ALERT_METRIC_OPTIONS,
  ALERT_PRIORITY_INHERIT,
  ALERT_PRIORITY_OPTIONS,
  describeAlertRule,
  formatAlertDuration,
} from './alertMetrics'

export type AlertRuleFormValues = Readonly<{
  metric: string
  condition: string
  threshold: number
  durationSeconds: number
  enabled: boolean
  alertPriority: string | null
}>

type AlertRuleDialogProps = Readonly<{
  open: boolean
  onOpenChange: (open: boolean) => void
  /** Absent for create. */
  rule?: HostAlert | null
  /** Where the rule will live, e.g. "shared defaults" or a hostname. */
  scopeLabel: string
  pending: boolean
  onSubmit: (values: AlertRuleFormValues) => void
}>

const DEFAULT_VALUES: AlertRuleFormValues = {
  metric: 'cpu_percent',
  condition: '>',
  threshold: 80,
  durationSeconds: 0,
  enabled: true,
  alertPriority: null,
}

export function AlertRuleDialog({
  open,
  onOpenChange,
  rule,
  scopeLabel,
  pending,
  onSubmit,
}: AlertRuleDialogProps) {
  return (
    <Dialog open={open} onOpenChange={onOpenChange}>
      <DialogContent className="sm:max-w-lg">
        {/* Keyed so each open starts from the rule under edit, or from the
            defaults, without an effect syncing state after the fact. */}
        <AlertRuleForm
          key={open ? (rule?.id ?? 'new') : 'closed'}
          rule={rule}
          scopeLabel={scopeLabel}
          pending={pending}
          onCancel={() => onOpenChange(false)}
          onSubmit={onSubmit}
        />
      </DialogContent>
    </Dialog>
  )
}

type AlertRuleFormProps = Readonly<{
  rule?: HostAlert | null
  scopeLabel: string
  pending: boolean
  onCancel: () => void
  onSubmit: (values: AlertRuleFormValues) => void
}>

function AlertRuleForm({rule, scopeLabel, pending, onCancel, onSubmit}: AlertRuleFormProps) {
  const isEdit = Boolean(rule)
  const [values, setValues] = useState<AlertRuleFormValues>(() =>
    rule
      ? {
          metric: rule.metric,
          condition: rule.condition,
          threshold: rule.threshold,
          durationSeconds: rule.durationSeconds,
          enabled: rule.enabled,
          alertPriority: rule.alertPriority ?? null,
        }
      : DEFAULT_VALUES
  )

  const durationMinutes = Math.round(values.durationSeconds / 60)

  let submitLabel = 'Create rule'
  if (pending) submitLabel = 'Saving…'
  else if (isEdit) submitLabel = 'Save changes'

  const handleSubmit: FormEventHandler<HTMLFormElement> = (event) => {
    event.preventDefault()
    onSubmit(values)
  }

  return (
    <>
      <DialogHeader>
        <DialogTitle>{isEdit ? 'Edit rule' : 'New rule'}</DialogTitle>
        <DialogDescription>
          {isEdit
            ? `Changes apply to ${scopeLabel}.`
            : `This rule will be added to ${scopeLabel}.`}
        </DialogDescription>
      </DialogHeader>

      <form onSubmit={handleSubmit}>
        <div className="space-y-3.5">
          <div className="space-y-1.5">
            <Label htmlFor="alert-metric">Metric</Label>
            <Select
              value={values.metric}
              onValueChange={(metric) => setValues((current) => ({...current, metric}))}
            >
              <SelectTrigger id="alert-metric">
                <SelectValue />
              </SelectTrigger>
              <SelectContent>
                {ALERT_METRIC_GROUPS.map((group) => (
                  <SelectGroup key={group}>
                    <SelectLabel>{group}</SelectLabel>
                    {ALERT_METRIC_OPTIONS.filter((option) => option.group === group).map((option) => (
                      <SelectItem key={option.value} value={option.value}>
                        {option.label}
                      </SelectItem>
                    ))}
                  </SelectGroup>
                ))}
              </SelectContent>
            </Select>
          </div>

          <div className="grid grid-cols-[1fr_7rem] gap-2">
            <div className="space-y-1.5">
              <Label htmlFor="alert-condition">Condition</Label>
              <Select
                value={values.condition}
                onValueChange={(condition) => setValues((current) => ({...current, condition}))}
              >
                <SelectTrigger id="alert-condition">
                  <SelectValue />
                </SelectTrigger>
                <SelectContent>
                  {ALERT_CONDITION_OPTIONS.map((option) => (
                    <SelectItem key={option.value} value={option.value}>
                      {option.label}
                    </SelectItem>
                  ))}
                </SelectContent>
              </Select>
            </div>
            <div className="space-y-1.5">
              <Label htmlFor="alert-threshold">Threshold</Label>
              <Input
                id="alert-threshold"
                type="number"
                step="0.1"
                required
                value={values.threshold}
                onChange={(event) =>
                  setValues((current) => ({
                    ...current,
                    threshold: event.target.valueAsNumber,
                  }))
                }
              />
            </div>
          </div>

          <div className="space-y-1.5">
            <Label htmlFor="alert-duration">Sustained for (minutes)</Label>
            <Input
              id="alert-duration"
              type="number"
              min="0"
              value={durationMinutes}
              onChange={(event) =>
                setValues((current) => ({
                  ...current,
                  durationSeconds: Math.max(0, Math.round(event.target.valueAsNumber || 0)) * 60,
                }))
              }
            />
            <p className="text-xs text-muted-foreground">
              How long the condition must hold before the rule fires. 0 fires on the first sample.
            </p>
          </div>

          <div className="space-y-1.5">
            <Label htmlFor="alert-priority">Priority</Label>
            <Select
              value={values.alertPriority ?? ALERT_PRIORITY_INHERIT}
              onValueChange={(priority) =>
                setValues((current) => ({
                  ...current,
                  alertPriority: priority === ALERT_PRIORITY_INHERIT ? null : priority,
                }))
              }
            >
              <SelectTrigger id="alert-priority">
                <SelectValue />
              </SelectTrigger>
              <SelectContent>
                <SelectItem value={ALERT_PRIORITY_INHERIT}>Use routing rule default</SelectItem>
                {ALERT_PRIORITY_OPTIONS.map((priority) => (
                  <SelectItem key={priority} value={priority}>
                    {priority}
                  </SelectItem>
                ))}
              </SelectContent>
            </Select>
            <p className="text-xs text-muted-foreground">
              P0–P2 page on-call by default; P3–P5 notify only.
            </p>
          </div>

          <div className="flex items-center justify-between gap-3 rounded-md border px-3 py-2.5">
            <div className="min-w-0">
              <p className="text-sm font-medium">Enabled</p>
              <p className="text-xs text-muted-foreground">
                Turn off to keep the rule without evaluating it.
              </p>
            </div>
            <Switch
              checked={values.enabled}
              onCheckedChange={(enabled) => setValues((current) => ({...current, enabled}))}
            />
          </div>

          <div className="flex items-start gap-2 rounded-md border border-info-border bg-info-bg px-3 py-2.5">
            <BellRing className="mt-0.5 h-3.5 w-3.5 shrink-0 text-info-fg" />
            <p className="text-xs text-info-fg">
              Alerts when{' '}
              <span className="font-semibold">
                {describeAlertRule(values.metric, values.condition, values.threshold || 0)}
              </span>{' '}
              {formatAlertDuration(values.durationSeconds).toLowerCase()}.
            </p>
          </div>
        </div>

      <DialogFooter className="mt-5">
        <Button type="button" variant="outline" onClick={onCancel}>
          Cancel
        </Button>
        <Button type="submit" disabled={pending}>
          {submitLabel}
        </Button>
      </DialogFooter>
      </form>
    </>
  )
}
