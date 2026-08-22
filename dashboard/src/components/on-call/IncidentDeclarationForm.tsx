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

import {useMemo, useState} from 'react'
import {useQuery} from '@tanstack/react-query'
import {AlertTriangle} from 'lucide-react'

import {api} from '@/lib/api'
import type {
  DeclareIncidentInput,
  IncidentFieldValue,
  IncidentFormDefinition,
  IncidentFormFieldDefinition,
  OnCallIncidentMode,
  OnCallIncidentVisibility,
} from '@/lib/api/types'
import {Button} from '@/components/ui/button'
import {Input} from '@/components/ui/input'
import {Textarea} from '@/components/ui/textarea'
import {Label} from '@/components/ui/label'
import {Checkbox} from '@/components/ui/checkbox'
import {
  Select,
  SelectContent,
  SelectItem,
  SelectTrigger,
  SelectValue,
} from '@/components/ui/select'
import {
  evaluateFieldCondition,
  INCIDENT_MODES,
  INCIDENT_MODE_META,
  INCIDENT_VISIBILITIES,
  INCIDENT_VISIBILITY_META,
  INITIAL_INCIDENT_STATUSES,
} from './incident-modeling'

const SEVERITIES = ['SEV-0', 'SEV-1', 'SEV-2', 'SEV-3', 'SEV-4']
const NO_TYPE = '__none__'

export interface IncidentDeclarationDefaults {
  title?: string
  description?: string
  summary?: string
  severity?: string
  mode?: OnCallIncidentMode
  visibility?: OnCallIncidentVisibility
  initialStatus?: string
}

interface IncidentDeclarationFormProps {
  /** Called with a fully-built payload once client validation passes. */
  onSubmit: (input: DeclareIncidentInput) => void
  isSubmitting: boolean
  submitLabel?: string
  onCancel?: () => void
  defaults?: IncidentDeclarationDefaults
  /**
   * When declaring from an alert the alert path is prefilled; the title stays
   * editable but we surface that the incident originates from the alert.
   */
  originHint?: string
}

function defaultsForForm(form: IncidentFormDefinition | null): Record<string, IncidentFieldValue> {
  const seed: Record<string, IncidentFieldValue> = {}
  form?.fields.forEach((formField) => {
    if (formField.defaultValue !== undefined && formField.defaultValue !== null) {
      seed[formField.field.key] = formField.defaultValue
    }
  })
  return seed
}

function isEmptyValue(value: IncidentFieldValue | undefined): boolean {
  if (value === undefined || value === null || value === '') return true
  return Array.isArray(value) && value.length === 0
}

export function IncidentDeclarationForm({
  onSubmit,
  isSubmitting,
  submitLabel = 'Declare incident',
  onCancel,
  defaults,
  originHint,
}: Readonly<IncidentDeclarationFormProps>) {
  const [title, setTitle] = useState(defaults?.title ?? '')
  const [description, setDescription] = useState(defaults?.description ?? '')
  const [summary, setSummary] = useState(defaults?.summary ?? '')
  const [severity, setSeverity] = useState(defaults?.severity ?? 'SEV-2')
  const [mode, setMode] = useState<OnCallIncidentMode>(defaults?.mode ?? 'LIVE')
  const [visibility, setVisibility] = useState<OnCallIncidentVisibility>(
    defaults?.visibility ?? 'ORGANIZATION'
  )
  const [initialStatus, setInitialStatus] = useState(defaults?.initialStatus ?? 'ACTIVE')
  const [incidentTypeId, setIncidentTypeId] = useState<string>(NO_TYPE)
  const [fieldValues, setFieldValues] = useState<Record<string, IncidentFieldValue>>({})
  const [seededFormId, setSeededFormId] = useState<string | null>(null)
  const [showErrors, setShowErrors] = useState(false)

  const {data: types} = useQuery({
    queryKey: ['incident-types'],
    queryFn: () => api.getIncidentTypes(),
  })
  const {data: forms, isLoading: formsLoading} = useQuery({
    queryKey: ['incident-forms', 'DECLARATION'],
    queryFn: () => api.getIncidentForms('DECLARATION'),
  })

  const activeForm = useMemo<IncidentFormDefinition | null>(() => {
    if (!forms) return null
    if (incidentTypeId !== NO_TYPE) {
      const typed = forms.find((form) => form.incidentTypeId === incidentTypeId)
      if (typed) return typed
    }
    return forms.find((form) => !form.incidentTypeId) ?? null
  }, [forms, incidentTypeId])

  // Re-seed configured field inputs to the active form's defaults whenever the
  // resolved form changes (selecting a different incident type, or the forms
  // query resolving, surfaces a different form). This is React's endorsed
  // "adjust state while rendering" pattern: the update is guarded by an id
  // comparison so it runs once per change and converges (after it, seededFormId
  // === activeFormId), and React discards this in-progress render and re-renders
  // with the fresh values before committing — so children never flash stale
  // fields, and submit (a post-commit event) never sends a previous type's values.
  const activeFormId = activeForm?.id ?? null
  if (activeFormId !== seededFormId) {
    setSeededFormId(activeFormId)
    setFieldValues(defaultsForForm(activeForm))
  }

  const visibleFields = (activeForm?.fields ?? [])
    .filter((formField) => formField.visible)
    .filter((formField) => evaluateFieldCondition(formField.condition, fieldValues))

  const missingRequired = visibleFields.filter(
    (formField) => formField.required && isEmptyValue(fieldValues[formField.field.key])
  )

  const setFieldValue = (key: string, value: IncidentFieldValue | undefined) => {
    setFieldValues((prev) => {
      const next = {...prev}
      if (value === undefined) delete next[key]
      else next[key] = value
      return next
    })
  }

  const canSubmit = title.trim().length > 0 && severity.length > 0 && missingRequired.length === 0

  const handleSubmit = () => {
    setShowErrors(true)
    if (!canSubmit) return
    const fields: Record<string, IncidentFieldValue> = {}
    visibleFields.forEach((formField) => {
      const value = fieldValues[formField.field.key]
      if (value !== undefined && !isEmptyValue(value)) fields[formField.field.key] = value
    })
    onSubmit({
      title: title.trim(),
      description: description.trim() || undefined,
      summary: summary.trim() || undefined,
      severity,
      mode,
      visibility,
      initialStatus,
      incidentTypeId: incidentTypeId === NO_TYPE ? undefined : incidentTypeId,
      fields: Object.keys(fields).length > 0 ? fields : undefined,
    })
  }

  return (
    <div className="space-y-4">
      {originHint && (
        <p className="text-xs text-muted-foreground rounded-md bg-muted/60 px-3 py-2">{originHint}</p>
      )}

      <div className="grid gap-1.5">
        <Label htmlFor="incident-title">
          Title <span className="text-danger-fg">*</span>
        </Label>
        <Input
          id="incident-title"
          value={title}
          onChange={(e) => setTitle(e.target.value)}
          placeholder="Short, specific summary of what is happening"
        />
        {showErrors && !title.trim() && (
          <p className="text-xs text-danger-fg">A title is required.</p>
        )}
      </div>

      <div className="grid gap-1.5">
        <Label htmlFor="incident-summary">Summary</Label>
        <Input
          id="incident-summary"
          value={summary}
          onChange={(e) => setSummary(e.target.value)}
          placeholder="One-line status for responders and stakeholders"
        />
      </div>

      <div className="grid gap-1.5">
        <Label htmlFor="incident-description">Description</Label>
        <Textarea
          id="incident-description"
          value={description}
          onChange={(e) => setDescription(e.target.value)}
          placeholder="What is affected, impact, and anything known so far"
          rows={3}
        />
      </div>

      <div className="grid gap-1.5">
        <Label>
          Severity <span className="text-danger-fg">*</span>
        </Label>
        <div className="flex flex-wrap gap-2">
          {SEVERITIES.map((sev) => (
            <Button
              key={sev}
              type="button"
              variant={severity === sev ? 'default' : 'outline'}
              size="sm"
              onClick={() => setSeverity(sev)}
            >
              {sev}
            </Button>
          ))}
        </div>
      </div>

      <div className="grid gap-3 sm:grid-cols-3">
        <div className="grid gap-1.5">
          <Label htmlFor="incident-mode">Mode</Label>
          <Select value={mode} onValueChange={(value) => setMode(value as OnCallIncidentMode)}>
            <SelectTrigger id="incident-mode" className="h-9">
              <SelectValue />
            </SelectTrigger>
            <SelectContent>
              {INCIDENT_MODES.map((value) => (
                <SelectItem key={value} value={value}>
                  {INCIDENT_MODE_META[value].label}
                </SelectItem>
              ))}
            </SelectContent>
          </Select>
          <p className="text-[11px] text-muted-foreground">{INCIDENT_MODE_META[mode].description}</p>
        </div>

        <div className="grid gap-1.5">
          <Label htmlFor="incident-visibility">Visibility</Label>
          <Select
            value={visibility}
            onValueChange={(value) => setVisibility(value as OnCallIncidentVisibility)}
          >
            <SelectTrigger id="incident-visibility" className="h-9">
              <SelectValue />
            </SelectTrigger>
            <SelectContent>
              {INCIDENT_VISIBILITIES.map((value) => (
                <SelectItem key={value} value={value}>
                  {INCIDENT_VISIBILITY_META[value].label}
                </SelectItem>
              ))}
            </SelectContent>
          </Select>
          <p className="text-[11px] text-muted-foreground">
            {INCIDENT_VISIBILITY_META[visibility].description}
          </p>
        </div>

        <div className="grid gap-1.5">
          <Label htmlFor="incident-status">Initial status</Label>
          <Select value={initialStatus} onValueChange={setInitialStatus}>
            <SelectTrigger id="incident-status" className="h-9">
              <SelectValue />
            </SelectTrigger>
            <SelectContent>
              {INITIAL_INCIDENT_STATUSES.map((status) => (
                <SelectItem key={status.value} value={status.value}>
                  {status.label}
                </SelectItem>
              ))}
            </SelectContent>
          </Select>
        </div>
      </div>

      <div className="grid gap-1.5">
        <Label htmlFor="incident-type">Incident type</Label>
        <Select value={incidentTypeId} onValueChange={setIncidentTypeId}>
          <SelectTrigger id="incident-type" className="h-9">
            <SelectValue />
          </SelectTrigger>
          <SelectContent>
            <SelectItem value={NO_TYPE}>No specific type</SelectItem>
            {(types ?? [])
              .filter((type) => type.enabled)
              .map((type) => (
                <SelectItem key={type.id} value={type.id}>
                  {type.name}
                </SelectItem>
              ))}
          </SelectContent>
        </Select>
      </div>

      {formsLoading && (
        <p className="text-xs text-muted-foreground">Loading declaration form…</p>
      )}

      {visibleFields.length > 0 && (
        <div className="space-y-3 rounded-lg border bg-muted/30 p-3">
          <p className="text-xs font-medium uppercase tracking-wider text-muted-foreground">
            {activeForm?.name ?? 'Incident details'}
          </p>
          {visibleFields.map((formField) => (
            <ConfiguredField
              key={formField.id}
              formField={formField}
              value={fieldValues[formField.field.key]}
              onChange={(value) => setFieldValue(formField.field.key, value)}
              showError={showErrors}
            />
          ))}
        </div>
      )}

      <div className="flex items-center justify-end gap-2 pt-1">
        {onCancel && (
          <Button type="button" variant="outline" onClick={onCancel} disabled={isSubmitting}>
            Cancel
          </Button>
        )}
        <Button type="button" onClick={handleSubmit} disabled={isSubmitting}>
          {isSubmitting ? 'Declaring…' : submitLabel}
        </Button>
      </div>
    </div>
  )
}

interface ConfiguredFieldProps {
  formField: IncidentFormFieldDefinition
  value: IncidentFieldValue | undefined
  onChange: (value: IncidentFieldValue | undefined) => void
  showError: boolean
}

function ConfiguredField({formField, value, onChange, showError}: Readonly<ConfiguredFieldProps>) {
  const {field, required, helpText} = formField
  const fieldId = `field-${formField.id}`
  const invalid = showError && required && isEmptyValue(value)

  return (
    <div className="grid gap-1.5">
      <Label htmlFor={fieldId} className="flex items-center gap-1">
        {field.name}
        {required && <span className="text-danger-fg">*</span>}
      </Label>
      <ConfiguredFieldControl formField={formField} value={value} onChange={onChange} fieldId={fieldId} />
      {helpText && <p className="text-[11px] text-muted-foreground">{helpText}</p>}
      {invalid && (
        <p className="flex items-center gap-1 text-xs text-danger-fg">
          <AlertTriangle className="h-3 w-3" /> {field.name} is required.
        </p>
      )}
    </div>
  )
}

interface ConfiguredFieldControlProps {
  formField: IncidentFormFieldDefinition
  value: IncidentFieldValue | undefined
  onChange: (value: IncidentFieldValue | undefined) => void
  fieldId: string
}

function ConfiguredFieldControl({
  formField,
  value,
  onChange,
  fieldId,
}: Readonly<ConfiguredFieldControlProps>) {
  const {field} = formField

  switch (field.valueType) {
    case 'SELECT':
      return (
        <Select
          value={typeof value === 'string' ? value : ''}
          onValueChange={(next) => onChange(next)}
        >
          <SelectTrigger id={fieldId} className="h-9">
            <SelectValue placeholder="Select an option" />
          </SelectTrigger>
          <SelectContent>
            {field.options.map((option) => (
              <SelectItem key={option.id} value={option.value}>
                {option.label}
              </SelectItem>
            ))}
          </SelectContent>
        </Select>
      )
    case 'MULTI_SELECT': {
      const selected = Array.isArray(value) ? (value as string[]) : []
      return (
        <div className="flex flex-wrap gap-3 rounded-md border bg-background px-3 py-2">
          {field.options.map((option) => {
            const checked = selected.includes(option.value)
            return (
              <label key={option.id} className="flex items-center gap-1.5 text-sm">
                <Checkbox
                  checked={checked}
                  onCheckedChange={(next) => {
                    const set = new Set(selected)
                    if (next === true) set.add(option.value)
                    else set.delete(option.value)
                    const list = [...set]
                    onChange(list.length > 0 ? list : undefined)
                  }}
                />
                {option.label}
              </label>
            )
          })}
        </div>
      )
    }
    case 'NUMBER':
      return (
        <Input
          id={fieldId}
          type="number"
          value={typeof value === 'number' ? String(value) : ''}
          onChange={(e) => {
            const raw = e.target.value
            if (raw === '') return onChange(undefined)
            const parsed = Number(raw)
            onChange(Number.isNaN(parsed) ? undefined : parsed)
          }}
        />
      )
    case 'LINK':
      return (
        <Input
          id={fieldId}
          type="url"
          placeholder="https://…"
          value={typeof value === 'string' ? value : ''}
          onChange={(e) => onChange(e.target.value || undefined)}
        />
      )
    default:
      // TEXT, USER, TEAM, SERVICE, CATALOG_RESOURCE all carry a string identifier
      // or value; passed through verbatim to the backend.
      return (
        <Input
          id={fieldId}
          placeholder={placeholderForType(formField)}
          value={typeof value === 'string' ? value : ''}
          onChange={(e) => onChange(e.target.value || undefined)}
        />
      )
  }
}

function placeholderForType(formField: IncidentFormFieldDefinition): string {
  switch (formField.field.valueType) {
    case 'USER':
      return 'User identifier'
    case 'TEAM':
      return 'Team identifier'
    case 'SERVICE':
      return 'Service name'
    case 'CATALOG_RESOURCE':
      return formField.field.catalogResourceType
        ? `${formField.field.catalogResourceType} identifier`
        : 'Catalog resource identifier'
    default:
      return ''
  }
}
