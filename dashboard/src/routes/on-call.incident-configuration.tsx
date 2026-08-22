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
import {createFileRoute} from '@tanstack/react-router'
import {useMutation, useQuery, useQueryClient} from '@tanstack/react-query'
import {History, Info, Plus, Trash2} from 'lucide-react'

import {api} from '@/lib/api'
import type {
  CreateIncidentCustomFieldInput,
  CreateIncidentFormFieldInput,
  CreateIncidentFormInput,
  CreateIncidentRoleInput,
  CreateIncidentTypeInput,
  IncidentCustomFieldDefinition,
  IncidentCustomFieldValueType,
  IncidentFieldValue,
  IncidentFormStage,
} from '@/lib/api/types'
import {Badge} from '@/components/ui/badge'
import {Button} from '@/components/ui/button'
import {SectionCard} from '@/components/ui/section-card'
import {EmptyState} from '@/components/ui/empty-state'
import {Input} from '@/components/ui/input'
import {Textarea} from '@/components/ui/textarea'
import {Label} from '@/components/ui/label'
import {Switch} from '@/components/ui/switch'
import {Checkbox} from '@/components/ui/checkbox'
import {Tabs, TabsContent, TabsList, TabsTrigger} from '@/components/ui/tabs'
import {
  Dialog,
  DialogContent,
  DialogDescription,
  DialogFooter,
  DialogHeader,
  DialogTitle,
} from '@/components/ui/dialog'
import {
  Select,
  SelectContent,
  SelectItem,
  SelectTrigger,
  SelectValue,
} from '@/components/ui/select'
import {useToast} from '@/hooks/useToast'
import {
  FIELD_VALUE_TYPES,
  FIELD_VALUE_TYPE_LABELS,
  FORM_STAGES,
  FORM_STAGE_LABELS,
  OPTION_FIELD_TYPES,
  buildFormFieldInput,
  isScalarConditionField,
  parseNumericInput,
} from '@/components/on-call/incident-modeling'

export const Route = createFileRoute('/on-call/incident-configuration')({
  component: IncidentConfiguration,
})

const DEFAULT_TYPE = '__default__'

function slugify(value: string, separator: '_' | '-'): string {
  let result = ''
  let separatorPending = false

  for (const character of value.trim().toLowerCase()) {
    const codePoint = character.codePointAt(0) ?? 0
    const isDigit = codePoint >= 48 && codePoint <= 57
    const isLowercaseLetter = codePoint >= 97 && codePoint <= 122
    if (!isDigit && !isLowercaseLetter) {
      separatorPending = result.length > 0
      continue
    }

    if (separatorPending) result += separator
    result += character
    separatorPending = false
  }

  return result
}

function IncidentConfiguration() {
  return (
    <div className="space-y-4">
      <div className="flex items-start gap-2 rounded-lg border border-info-border bg-info-bg px-3 py-2 text-sm">
        <Info className="mt-0.5 h-4 w-4 shrink-0 text-info-fg" />
        <p className="text-muted-foreground">
          Incident types, fields, forms, and roles are saved as{' '}
          <span className="font-medium text-foreground">versioned snapshots</span>. Editing a
          definition supersedes the previous version, and incidents keep the exact version captured
          when they were declared.
        </p>
      </div>

      <Tabs defaultValue="types">
        <TabsList>
          <TabsTrigger value="types">Types</TabsTrigger>
          <TabsTrigger value="fields">Custom fields</TabsTrigger>
          <TabsTrigger value="forms">Forms</TabsTrigger>
          <TabsTrigger value="roles">Roles</TabsTrigger>
        </TabsList>
        <TabsContent value="types">
          <TypesTab />
        </TabsContent>
        <TabsContent value="fields">
          <FieldsTab />
        </TabsContent>
        <TabsContent value="forms">
          <FormsTab />
        </TabsContent>
        <TabsContent value="roles">
          <RolesTab />
        </TabsContent>
      </Tabs>
    </div>
  )
}

function useConfigError() {
  const {toast} = useToast()
  return (error: Error) => toast({title: 'Error', description: error.message, variant: 'destructive'})
}

function VersionBadge({version}: Readonly<{version: number}>) {
  return (
    <Badge variant="neutral" size="sm" className="gap-1">
      <History className="h-3 w-3" />v{version}
    </Badge>
  )
}

// ──── Types ────

function TypesTab() {
  const queryClient = useQueryClient()
  const {toast} = useToast()
  const onError = useConfigError()
  const [open, setOpen] = useState(false)

  const {data: types, isLoading} = useQuery({
    queryKey: ['incident-types'],
    queryFn: () => api.getIncidentTypes(),
  })
  const mutation = useMutation({
    mutationFn: (input: CreateIncidentTypeInput) => api.createIncidentType(input),
    onSuccess: () => {
      queryClient.invalidateQueries({queryKey: ['incident-types']})
      setOpen(false)
      toast({title: 'Incident type saved'})
    },
    onError,
  })

  return (
    <SectionCard
      title="Incident types"
      count={types?.length || undefined}
      actions={
        <Button size="sm" className="h-7 gap-1 px-2 text-xs" onClick={() => setOpen(true)}>
          <Plus className="h-3.5 w-3.5" /> New type
        </Button>
      }
      bodyClassName="space-y-2"
    >
      {isLoading && <p className="text-xs text-muted-foreground">Loading…</p>}
      {!isLoading && (types?.length ?? 0) === 0 && (
        <EmptyState icon={Info} title="No incident types" description="Create a type to tailor declaration forms." />
      )}
      {types?.map((type) => (
        <div key={type.id} className="flex items-start justify-between gap-3 rounded-lg border p-3">
          <div className="min-w-0">
            <div className="flex items-center gap-2">
              <span className="text-sm font-medium">{type.name}</span>
              <VersionBadge version={type.version} />
              <Badge variant={type.enabled ? 'success' : 'neutral'} size="sm">
                {type.enabled ? 'Enabled' : 'Disabled'}
              </Badge>
            </div>
            <p className="mt-0.5 font-mono text-[11px] text-muted-foreground">{type.key}</p>
            {type.description && <p className="mt-1 text-xs text-muted-foreground">{type.description}</p>}
          </div>
        </div>
      ))}
      {open && (
        <TypeDialog isPending={mutation.isPending} onClose={() => setOpen(false)} onCreate={(input) => mutation.mutate(input)} />
      )}
    </SectionCard>
  )
}

function TypeDialog({
  isPending,
  onClose,
  onCreate,
}: Readonly<{
  isPending: boolean
  onClose: () => void
  onCreate: (input: CreateIncidentTypeInput) => void
}>) {
  const [name, setName] = useState('')
  const [key, setKey] = useState('')
  const [keyDirty, setKeyDirty] = useState(false)
  const [description, setDescription] = useState('')
  const [enabled, setEnabled] = useState(true)
  const effectiveKey = keyDirty ? key : slugify(name, '_')

  return (
    <Dialog open onOpenChange={(next) => !next && onClose()}>
      <DialogContent>
        <DialogHeader>
          <DialogTitle>New incident type</DialogTitle>
          <DialogDescription>Group incidents and drive their configured declaration form.</DialogDescription>
        </DialogHeader>
        <div className="grid gap-3 py-2">
          <div className="grid gap-1.5">
            <Label htmlFor="type-name">Name</Label>
            <Input id="type-name" value={name} onChange={(e) => setName(e.target.value)} placeholder="Security incident" />
          </div>
          <div className="grid gap-1.5">
            <Label htmlFor="type-key">Key</Label>
            <Input
              id="type-key"
              value={effectiveKey}
              onChange={(e) => {
                setKeyDirty(true)
                setKey(e.target.value)
              }}
              placeholder="security_incident"
              className="font-mono"
            />
            <p className="text-[11px] text-muted-foreground">Stable identifier — lowercase letters, numbers, underscores.</p>
          </div>
          <div className="grid gap-1.5">
            <Label htmlFor="type-description">Description</Label>
            <Textarea id="type-description" value={description} onChange={(e) => setDescription(e.target.value)} rows={2} />
          </div>
          <label className="flex items-center gap-2 text-sm">
            <Switch checked={enabled} onCheckedChange={setEnabled} />
            Enabled for new incidents
          </label>
        </div>
        <DialogFooter>
          <Button variant="outline" onClick={onClose} disabled={isPending}>
            Cancel
          </Button>
          <Button
            disabled={isPending || !name.trim() || !effectiveKey}
            onClick={() =>
              onCreate({
                key: effectiveKey,
                name: name.trim(),
                description: description.trim() || undefined,
                enabled,
              })
            }
          >
            {isPending ? 'Saving…' : 'Save type'}
          </Button>
        </DialogFooter>
      </DialogContent>
    </Dialog>
  )
}

// ──── Custom fields ────

function FieldsTab() {
  const queryClient = useQueryClient()
  const {toast} = useToast()
  const onError = useConfigError()
  const [open, setOpen] = useState(false)

  const {data: fields, isLoading} = useQuery({
    queryKey: ['incident-fields'],
    queryFn: () => api.getIncidentCustomFields(),
  })
  const mutation = useMutation({
    mutationFn: (input: CreateIncidentCustomFieldInput) => api.createIncidentCustomField(input),
    onSuccess: () => {
      queryClient.invalidateQueries({queryKey: ['incident-fields']})
      setOpen(false)
      toast({title: 'Custom field saved'})
    },
    onError,
  })

  return (
    <SectionCard
      title="Custom fields"
      count={fields?.length || undefined}
      actions={
        <Button size="sm" className="h-7 gap-1 px-2 text-xs" onClick={() => setOpen(true)}>
          <Plus className="h-3.5 w-3.5" /> New field
        </Button>
      }
      bodyClassName="space-y-2"
    >
      {isLoading && <p className="text-xs text-muted-foreground">Loading…</p>}
      {!isLoading && (fields?.length ?? 0) === 0 && (
        <EmptyState icon={Info} title="No custom fields" description="Custom fields power your stage forms." />
      )}
      {fields?.map((field) => (
        <div key={field.id} className="flex items-start justify-between gap-3 rounded-lg border p-3">
          <div className="min-w-0">
            <div className="flex flex-wrap items-center gap-2">
              <span className="text-sm font-medium">{field.name}</span>
              <VersionBadge version={field.version} />
              <Badge variant="info" size="sm">
                {FIELD_VALUE_TYPE_LABELS[field.valueType]}
              </Badge>
              {field.catalogResourceType && (
                <Badge variant="neutral" size="sm">
                  {field.catalogResourceType}
                </Badge>
              )}
            </div>
            <p className="mt-0.5 font-mono text-[11px] text-muted-foreground">{field.key}</p>
            {field.options.length > 0 && (
              <div className="mt-1.5 flex flex-wrap gap-1">
                {field.options.map((option) => (
                  <span key={option.id} className="rounded bg-muted px-1.5 py-0.5 text-[11px] text-muted-foreground">
                    {option.label}
                  </span>
                ))}
              </div>
            )}
          </div>
        </div>
      ))}
      {open && (
        <FieldDialog isPending={mutation.isPending} onClose={() => setOpen(false)} onCreate={(input) => mutation.mutate(input)} />
      )}
    </SectionCard>
  )
}

interface OptionDraft {
  id: string
  value: string
  label: string
  color: string
}

function createOptionDraft(): OptionDraft {
  return {id: globalThis.crypto.randomUUID(), value: '', label: '', color: ''}
}

function updateOptionDraft(
  options: OptionDraft[],
  id: string,
  property: 'value' | 'label',
  value: string
): OptionDraft[] {
  return options.map((option) => (option.id === id ? {...option, [property]: value} : option))
}

function removeOptionDraft(options: OptionDraft[], id: string): OptionDraft[] {
  return options.filter((option) => option.id !== id)
}

function FieldDialog({
  isPending,
  onClose,
  onCreate,
}: Readonly<{
  isPending: boolean
  onClose: () => void
  onCreate: (input: CreateIncidentCustomFieldInput) => void
}>) {
  const [name, setName] = useState('')
  const [key, setKey] = useState('')
  const [keyDirty, setKeyDirty] = useState(false)
  const [description, setDescription] = useState('')
  const [valueType, setValueType] = useState<IncidentCustomFieldValueType>('TEXT')
  const [catalogResourceType, setCatalogResourceType] = useState('')
  const [options, setOptions] = useState<OptionDraft[]>([createOptionDraft()])
  const effectiveKey = keyDirty ? key : slugify(name, '_')

  const needsOptions = OPTION_FIELD_TYPES.includes(valueType)
  const needsCatalog = valueType === 'CATALOG_RESOURCE'
  const validOptions = options.filter((option) => option.value.trim() && option.label.trim())
  const canSubmit =
    name.trim() &&
    effectiveKey &&
    (!needsOptions || validOptions.length > 0) &&
    (!needsCatalog || catalogResourceType.trim())

  const submit = () => {
    onCreate({
      key: effectiveKey,
      name: name.trim(),
      description: description.trim() || undefined,
      valueType,
      catalogResourceType: needsCatalog ? catalogResourceType.trim() : undefined,
      options: needsOptions
        ? validOptions.map((option, index) => ({
            value: option.value.trim(),
            label: option.label.trim(),
            position: index,
            color: option.color.trim() || undefined,
          }))
        : undefined,
    })
  }

  return (
    <Dialog open onOpenChange={(next) => !next && onClose()}>
      <DialogContent className="max-h-[85vh] overflow-y-auto">
        <DialogHeader>
          <DialogTitle>New custom field</DialogTitle>
          <DialogDescription>Reusable field for incident forms.</DialogDescription>
        </DialogHeader>
        <div className="grid gap-3 py-2">
          <div className="grid gap-3 sm:grid-cols-2">
            <div className="grid gap-1.5">
              <Label htmlFor="field-name">Name</Label>
              <Input id="field-name" value={name} onChange={(e) => setName(e.target.value)} placeholder="Affected region" />
            </div>
            <div className="grid gap-1.5">
              <Label htmlFor="field-key">Key</Label>
              <Input
                id="field-key"
                value={effectiveKey}
                onChange={(e) => {
                  setKeyDirty(true)
                  setKey(e.target.value)
                }}
                className="font-mono"
              />
            </div>
          </div>
          <div className="grid gap-1.5">
            <Label htmlFor="field-type">Type</Label>
            <Select value={valueType} onValueChange={(value) => setValueType(value as IncidentCustomFieldValueType)}>
              <SelectTrigger id="field-type">
                <SelectValue />
              </SelectTrigger>
              <SelectContent>
                {FIELD_VALUE_TYPES.map((type) => (
                  <SelectItem key={type} value={type}>
                    {FIELD_VALUE_TYPE_LABELS[type]}
                  </SelectItem>
                ))}
              </SelectContent>
            </Select>
          </div>
          <div className="grid gap-1.5">
            <Label htmlFor="field-description">Description</Label>
            <Input id="field-description" value={description} onChange={(e) => setDescription(e.target.value)} />
          </div>

          {needsCatalog && (
            <div className="grid gap-1.5">
              <Label htmlFor="field-catalog">Catalog resource type</Label>
              <Input
                id="field-catalog"
                value={catalogResourceType}
                onChange={(e) => setCatalogResourceType(e.target.value)}
                placeholder="service, host, team…"
              />
            </div>
          )}

          {needsOptions && (
            <div className="grid gap-2">
              <Label>Options</Label>
              {options.map((option, index) => (
                <div key={option.id} className="flex items-center gap-2">
                  <Input
                    aria-label={`Option ${index + 1} value`}
                    value={option.value}
                    onChange={(e) =>
                      setOptions((previous) =>
                        updateOptionDraft(previous, option.id, 'value', e.target.value)
                      )
                    }
                    placeholder="value"
                    className="font-mono"
                  />
                  <Input
                    aria-label={`Option ${index + 1} label`}
                    value={option.label}
                    onChange={(e) =>
                      setOptions((previous) =>
                        updateOptionDraft(previous, option.id, 'label', e.target.value)
                      )
                    }
                    placeholder="Label"
                  />
                  <Button
                    variant="ghost"
                    size="icon"
                    className="h-8 w-8 shrink-0"
                    aria-label={`Remove option ${index + 1}`}
                    onClick={() => setOptions(removeOptionDraft(options, option.id))}
                  >
                    <Trash2 className="h-4 w-4" />
                  </Button>
                </div>
              ))}
              <Button
                variant="outline"
                size="sm"
                className="w-fit"
                onClick={() => setOptions((previous) => [...previous, createOptionDraft()])}
              >
                <Plus className="mr-1 h-3.5 w-3.5" /> Add option
              </Button>
            </div>
          )}
        </div>
        <DialogFooter>
          <Button variant="outline" onClick={onClose} disabled={isPending}>
            Cancel
          </Button>
          <Button disabled={isPending || !canSubmit} onClick={submit}>
            {isPending ? 'Saving…' : 'Save field'}
          </Button>
        </DialogFooter>
      </DialogContent>
    </Dialog>
  )
}

// ──── Forms ────

function FormsTab() {
  const queryClient = useQueryClient()
  const {toast} = useToast()
  const onError = useConfigError()
  const [open, setOpen] = useState(false)

  const {data: forms, isLoading} = useQuery({
    queryKey: ['incident-forms', 'all'],
    queryFn: () => api.getIncidentForms(),
  })
  const {data: types} = useQuery({queryKey: ['incident-types'], queryFn: () => api.getIncidentTypes()})
  const {data: fields} = useQuery({queryKey: ['incident-fields'], queryFn: () => api.getIncidentCustomFields()})
  const mutation = useMutation({
    mutationFn: (input: CreateIncidentFormInput) => api.createIncidentForm(input),
    onSuccess: () => {
      queryClient.invalidateQueries({queryKey: ['incident-forms', 'all']})
      queryClient.invalidateQueries({queryKey: ['incident-forms', 'DECLARATION']})
      setOpen(false)
      toast({title: 'Form saved'})
    },
    onError,
  })

  const typeName = (id?: string) => types?.find((type) => type.id === id)?.name

  return (
    <SectionCard
      title="Stage forms"
      count={forms?.length || undefined}
      actions={
        <Button size="sm" className="h-7 gap-1 px-2 text-xs" onClick={() => setOpen(true)}>
          <Plus className="h-3.5 w-3.5" /> New form
        </Button>
      }
      bodyClassName="space-y-2"
    >
      {isLoading && <p className="text-xs text-muted-foreground">Loading…</p>}
      {!isLoading && (forms?.length ?? 0) === 0 && (
        <EmptyState icon={Info} title="No forms" description="Compose a form to capture details at each stage." />
      )}
      {forms?.map((form) => (
        <div key={form.id} className="rounded-lg border p-3">
          <div className="flex flex-wrap items-center gap-2">
            <span className="text-sm font-medium">{form.name}</span>
            <VersionBadge version={form.version} />
            <Badge variant="info" size="sm">
              {FORM_STAGE_LABELS[form.stage]}
            </Badge>
            <Badge variant="neutral" size="sm">
              {form.incidentTypeId ? (typeName(form.incidentTypeId) ?? 'Type-specific') : 'Any type'}
            </Badge>
            <span className="text-[11px] text-muted-foreground">
              {form.fields.length} field{form.fields.length === 1 ? '' : 's'}
            </span>
          </div>
          {form.fields.length > 0 && (
            <ul className="mt-2 space-y-1">
              {form.fields.map((formField) => (
                <li key={formField.id} className="flex items-center gap-2 text-xs text-muted-foreground">
                  <span className="tabular-nums text-muted-foreground/60">{formField.position + 1}.</span>
                  <span className="text-foreground">{formField.field.name}</span>
                  {formField.required && <Badge variant="neutral" size="sm">Required</Badge>}
                  {!formField.visible && <Badge variant="neutral" size="sm">Hidden</Badge>}
                  {Object.keys(formField.condition).length > 0 && (
                    <Badge variant="neutral" size="sm">Conditional</Badge>
                  )}
                </li>
              ))}
            </ul>
          )}
        </div>
      ))}
      {open && (
        <FormDialog
          isPending={mutation.isPending}
          fields={fields ?? []}
          types={(types ?? []).map((type) => ({id: type.id, name: type.name}))}
          onClose={() => setOpen(false)}
          onCreate={(input) => mutation.mutate(input)}
        />
      )}
    </SectionCard>
  )
}

interface FormFieldDraft {
  fieldId: string
  visible: boolean
  required: boolean
  helpText: string
  defaultValue: IncidentFieldValue | undefined
  conditionFieldKey: string
  conditionEquals: IncidentFieldValue | undefined
}

const NO_VALUE = '__no_value__'

// A numeric default control that keeps the raw text locally so partial entries
// like "-", "1.", or "-3.5" are not blocked or clobbered at input time. It
// stores a finite number when the text parses, and clears the stored value for
// empty/incomplete input; server-side validation remains the source of truth.
function NumberValueInput({
  value,
  onChange,
  ariaLabel,
}: Readonly<{
  value: IncidentFieldValue | undefined
  onChange: (value: IncidentFieldValue | undefined) => void
  ariaLabel: string
}>) {
  const [text, setText] = useState(typeof value === 'number' ? String(value) : '')
  return (
    <Input
      aria-label={ariaLabel}
      type="text"
      inputMode="decimal"
      value={text}
      onChange={(e) => {
        const raw = e.target.value
        setText(raw)
        onChange(parseNumericInput(raw))
      }}
    />
  )
}

// A control whose stored value is type-correct for the field it targets, so
// defaults and condition-equality reach the backend as the right JSON type
// (string / number / string[]) instead of a coerced string.
function TypedValueControl({
  field,
  value,
  onChange,
  ariaLabel,
}: Readonly<{
  field: IncidentCustomFieldDefinition
  value: IncidentFieldValue | undefined
  onChange: (value: IncidentFieldValue | undefined) => void
  ariaLabel: string
}>) {
  switch (field.valueType) {
    case 'SELECT':
      return (
        <Select
          value={typeof value === 'string' && value ? value : NO_VALUE}
          onValueChange={(next) => onChange(next === NO_VALUE ? undefined : next)}
        >
          <SelectTrigger aria-label={ariaLabel}>
            <SelectValue placeholder="Select an option" />
          </SelectTrigger>
          <SelectContent>
            <SelectItem value={NO_VALUE}>No value</SelectItem>
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
        <fieldset
          className="m-0 flex min-w-0 flex-wrap gap-3 rounded-md border bg-background px-3 py-2"
          aria-label={ariaLabel}
        >
          {field.options.map((option) => (
            <label key={option.id} className="flex items-center gap-1.5 text-sm">
              <Checkbox
                checked={selected.includes(option.value)}
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
          ))}
        </fieldset>
      )
    }
    case 'NUMBER':
      return <NumberValueInput value={value} onChange={onChange} ariaLabel={ariaLabel} />
    case 'LINK':
      return (
        <Input
          aria-label={ariaLabel}
          type="url"
          placeholder="https://…"
          value={typeof value === 'string' ? value : ''}
          onChange={(e) => onChange(e.target.value || undefined)}
        />
      )
    default:
      // TEXT / USER / TEAM / SERVICE / CATALOG_RESOURCE all carry a string.
      return (
        <Input
          aria-label={ariaLabel}
          placeholder={field.valueType === 'TEXT' ? 'Value' : 'Identifier'}
          value={typeof value === 'string' ? value : ''}
          onChange={(e) => onChange(e.target.value || undefined)}
        />
      )
  }
}

function FormDialog({
  isPending,
  fields,
  types,
  onClose,
  onCreate,
}: Readonly<{
  isPending: boolean
  fields: IncidentCustomFieldDefinition[]
  types: {id: string; name: string}[]
  onClose: () => void
  onCreate: (input: CreateIncidentFormInput) => void
}>) {
  const [name, setName] = useState('')
  const [stage, setStage] = useState<IncidentFormStage>('DECLARATION')
  const [typeId, setTypeId] = useState<string>(DEFAULT_TYPE)
  const [rows, setRows] = useState<FormFieldDraft[]>([])
  const [addFieldId, setAddFieldId] = useState('')

  const fieldById = new Map(fields.map((field) => [field.id, field]))
  const fieldByKey = new Map(fields.map((field) => [field.key, field]))
  const availableFields = fields.filter((field) => !rows.some((row) => row.fieldId === field.id))

  const addField = () => {
    if (!addFieldId) return
    setRows((prev) => [
      ...prev,
      {
        fieldId: addFieldId,
        visible: true,
        required: false,
        helpText: '',
        defaultValue: undefined,
        conditionFieldKey: '',
        conditionEquals: undefined,
      },
    ])
    setAddFieldId('')
  }

  const updateRow = (index: number, patch: Partial<FormFieldDraft>) =>
    setRows((prev) => prev.map((row, i) => (i === index ? {...row, ...patch} : row)))
  const removeRow = (index: number) => setRows((prev) => prev.filter((_, i) => i !== index))
  const moveRow = (index: number, direction: -1 | 1) => {
    const target = index + direction
    if (target < 0 || target >= rows.length) return
    setRows((prev) => {
      const next = [...prev]
      ;[next[index], next[target]] = [next[target], next[index]]
      return next
    })
  }

  const submit = () => {
    const formFields: CreateIncidentFormFieldInput[] = rows.map((row, index) =>
      buildFormFieldInput({...row, position: index}),
    )
    onCreate({
      incidentTypeId: typeId === DEFAULT_TYPE ? undefined : typeId,
      stage,
      name: name.trim(),
      fields: formFields,
    })
  }

  return (
    <Dialog open onOpenChange={(next) => !next && onClose()}>
      <DialogContent className="max-h-[85vh] overflow-y-auto sm:max-w-2xl">
        <DialogHeader>
          <DialogTitle>New stage form</DialogTitle>
          <DialogDescription>Compose the fields shown during a stage. Order sets the display position.</DialogDescription>
        </DialogHeader>
        <div className="grid gap-3 py-2">
          <div className="grid gap-3 sm:grid-cols-3">
            <div className="grid gap-1.5 sm:col-span-1">
              <Label htmlFor="form-stage">Stage</Label>
              <Select value={stage} onValueChange={(value) => setStage(value as IncidentFormStage)}>
                <SelectTrigger id="form-stage">
                  <SelectValue />
                </SelectTrigger>
                <SelectContent>
                  {FORM_STAGES.map((value) => (
                    <SelectItem key={value} value={value}>
                      {FORM_STAGE_LABELS[value]}
                    </SelectItem>
                  ))}
                </SelectContent>
              </Select>
            </div>
            <div className="grid gap-1.5 sm:col-span-2">
              <Label htmlFor="form-name">Name</Label>
              <Input id="form-name" value={name} onChange={(e) => setName(e.target.value)} placeholder="Declaration form" />
            </div>
          </div>
          <div className="grid gap-1.5">
            <Label htmlFor="form-type">Incident type</Label>
            <Select value={typeId} onValueChange={setTypeId}>
              <SelectTrigger id="form-type">
                <SelectValue />
              </SelectTrigger>
              <SelectContent>
                <SelectItem value={DEFAULT_TYPE}>Any type (default)</SelectItem>
                {types.map((type) => (
                  <SelectItem key={type.id} value={type.id}>
                    {type.name}
                  </SelectItem>
                ))}
              </SelectContent>
            </Select>
          </div>

          <div className="grid gap-2">
            <Label>Fields</Label>
            {rows.length === 0 && (
              <p className="text-xs text-muted-foreground">No fields added yet.</p>
            )}
            {rows.map((row, index) => {
              const field = fieldById.get(row.fieldId)
              return (
                <div key={row.fieldId} className="space-y-2 rounded-lg border p-3">
                  <div className="flex items-center justify-between gap-2">
                    <div className="flex items-center gap-2">
                      <span className="tabular-nums text-xs text-muted-foreground">{index + 1}.</span>
                      <span className="text-sm font-medium">{field?.name ?? row.fieldId}</span>
                      {field && (
                        <Badge variant="info" size="sm">
                          {FIELD_VALUE_TYPE_LABELS[field.valueType]}
                        </Badge>
                      )}
                    </div>
                    <div className="flex items-center gap-1">
                      <Button variant="ghost" size="icon" className="h-7 w-7" aria-label="Move up" disabled={index === 0} onClick={() => moveRow(index, -1)}>
                        ↑
                      </Button>
                      <Button
                        variant="ghost"
                        size="icon"
                        className="h-7 w-7"
                        aria-label="Move down"
                        disabled={index === rows.length - 1}
                        onClick={() => moveRow(index, 1)}
                      >
                        ↓
                      </Button>
                      <Button variant="ghost" size="icon" className="h-7 w-7" aria-label="Remove field" onClick={() => removeRow(index)}>
                        <Trash2 className="h-4 w-4" />
                      </Button>
                    </div>
                  </div>
                  <div className="flex flex-wrap items-center gap-4">
                    <label className="flex items-center gap-1.5 text-xs">
                      <Switch checked={row.visible} onCheckedChange={(next) => updateRow(index, {visible: next, required: next ? row.required : false})} />
                      Visible
                    </label>
                    <label className="flex items-center gap-1.5 text-xs">
                      <Switch
                        checked={row.required}
                        disabled={!row.visible}
                        onCheckedChange={(next) => updateRow(index, {required: next})}
                      />
                      Required
                    </label>
                  </div>
                  <div className="grid gap-2 sm:grid-cols-2">
                    <div className="grid gap-1">
                      <span className="text-[11px] text-muted-foreground">Help text</span>
                      <Input
                        aria-label={`Help text for ${field?.name ?? 'field'}`}
                        value={row.helpText}
                        onChange={(e) => updateRow(index, {helpText: e.target.value})}
                        placeholder="Shown under the field"
                      />
                    </div>
                    <div className="grid gap-1">
                      <span className="text-[11px] text-muted-foreground">Default value</span>
                      {field ? (
                        <TypedValueControl
                          field={field}
                          value={row.defaultValue}
                          onChange={(value) => updateRow(index, {defaultValue: value})}
                          ariaLabel={`Default value for ${field.name}`}
                        />
                      ) : (
                        <Input disabled aria-label="Default value" placeholder="—" />
                      )}
                    </div>
                  </div>
                  <div className="grid gap-2 sm:grid-cols-2">
                    <div className="grid gap-1">
                      <span className="text-[11px] text-muted-foreground">Only show when field</span>
                      <Select
                        value={row.conditionFieldKey || DEFAULT_TYPE}
                        onValueChange={(value) =>
                          updateRow(index, {
                            conditionFieldKey: value === DEFAULT_TYPE ? '' : value,
                            conditionEquals: undefined,
                          })
                        }
                      >
                        <SelectTrigger aria-label={`Condition field for ${field?.name ?? 'field'}`}>
                          <SelectValue />
                        </SelectTrigger>
                        <SelectContent>
                          <SelectItem value={DEFAULT_TYPE}>Always</SelectItem>
                          {rows
                            .filter((_, i) => i !== index)
                            .map((other) => fieldById.get(other.fieldId))
                            .filter(
                              (other): other is IncidentCustomFieldDefinition =>
                                Boolean(other) && isScalarConditionField(other as IncidentCustomFieldDefinition),
                            )
                            .map((other) => (
                              <SelectItem key={other.id} value={other.key}>
                                {other.name}
                              </SelectItem>
                            ))}
                        </SelectContent>
                      </Select>
                    </div>
                    <div className="grid gap-1">
                      <span className="text-[11px] text-muted-foreground">equals</span>
                      {row.conditionFieldKey && fieldByKey.get(row.conditionFieldKey) ? (
                        <TypedValueControl
                          field={fieldByKey.get(row.conditionFieldKey) as IncidentCustomFieldDefinition}
                          value={row.conditionEquals}
                          onChange={(value) => updateRow(index, {conditionEquals: value})}
                          ariaLabel={`Condition value for ${field?.name ?? 'field'}`}
                        />
                      ) : (
                        <Input
                          disabled
                          aria-label={`Condition value for ${field?.name ?? 'field'}`}
                          placeholder="Select a field first"
                        />
                      )}
                    </div>
                  </div>
                </div>
              )
            })}

            {availableFields.length > 0 && (
              <div className="flex items-center gap-2">
                <Select value={addFieldId} onValueChange={setAddFieldId}>
                  <SelectTrigger aria-label="Add field">
                    <SelectValue placeholder="Add a field…" />
                  </SelectTrigger>
                  <SelectContent>
                    {availableFields.map((field) => (
                      <SelectItem key={field.id} value={field.id}>
                        {field.name}
                      </SelectItem>
                    ))}
                  </SelectContent>
                </Select>
                <Button variant="outline" size="sm" disabled={!addFieldId} onClick={addField}>
                  <Plus className="mr-1 h-3.5 w-3.5" /> Add
                </Button>
              </div>
            )}
            {fields.length === 0 && (
              <p className="text-xs text-muted-foreground">Create custom fields first to compose a form.</p>
            )}
          </div>
        </div>
        <DialogFooter>
          <Button variant="outline" onClick={onClose} disabled={isPending}>
            Cancel
          </Button>
          <Button disabled={isPending || !name.trim()} onClick={submit}>
            {isPending ? 'Saving…' : 'Save form'}
          </Button>
        </DialogFooter>
      </DialogContent>
    </Dialog>
  )
}

// ──── Roles ────

function RolesTab() {
  const queryClient = useQueryClient()
  const {toast} = useToast()
  const onError = useConfigError()
  const [open, setOpen] = useState(false)

  const {data: roles, isLoading} = useQuery({
    queryKey: ['incident-role-definitions'],
    queryFn: () => api.getIncidentRoles(),
  })
  const mutation = useMutation({
    mutationFn: (input: CreateIncidentRoleInput) => api.createIncidentRole(input),
    onSuccess: () => {
      queryClient.invalidateQueries({queryKey: ['incident-role-definitions']})
      setOpen(false)
      toast({title: 'Role saved'})
    },
    onError,
  })

  return (
    <SectionCard
      title="Responder roles"
      count={roles?.length || undefined}
      actions={
        <Button size="sm" className="h-7 gap-1 px-2 text-xs" onClick={() => setOpen(true)}>
          <Plus className="h-3.5 w-3.5" /> New role
        </Button>
      }
      bodyClassName="space-y-2"
    >
      {isLoading && <p className="text-xs text-muted-foreground">Loading…</p>}
      {!isLoading && (roles?.length ?? 0) === 0 && (
        <EmptyState icon={Info} title="No roles" description="Roles define who does what during an incident." />
      )}
      {roles?.map((role) => (
        <div key={role.id} className="rounded-lg border p-3">
          <div className="flex flex-wrap items-center gap-2">
            <span className="text-sm font-medium">{role.name}</span>
            <VersionBadge version={role.version} />
            {role.required && <Badge variant="neutral" size="sm">Required</Badge>}
            {role.default && <Badge variant="neutral" size="sm">Default</Badge>}
          </div>
          <p className="mt-0.5 font-mono text-[11px] text-muted-foreground">{role.key}</p>
          {role.description && <p className="mt-1 text-xs text-muted-foreground">{role.description}</p>}
          {role.responsibilities.length > 0 && (
            <ul className="mt-1.5 flex flex-wrap gap-1">
              {role.responsibilities.map((item) => (
                <li key={item} className="rounded bg-muted px-1.5 py-0.5 text-[11px] text-muted-foreground">
                  {item}
                </li>
              ))}
            </ul>
          )}
          {role.privateInstructions && (
            <div className="mt-2 rounded-md border border-warning-border bg-warning-bg/40 p-2">
              <p className="text-[11px] font-medium uppercase tracking-wider text-warning-fg">
                Private responder instructions
              </p>
              <p className="mt-0.5 text-xs text-muted-foreground">{role.privateInstructions}</p>
            </div>
          )}
        </div>
      ))}
      {open && (
        <RoleDialog isPending={mutation.isPending} onClose={() => setOpen(false)} onCreate={(input) => mutation.mutate(input)} />
      )}
    </SectionCard>
  )
}

function RoleDialog({
  isPending,
  onClose,
  onCreate,
}: Readonly<{
  isPending: boolean
  onClose: () => void
  onCreate: (input: CreateIncidentRoleInput) => void
}>) {
  const [name, setName] = useState('')
  const [key, setKey] = useState('')
  const [keyDirty, setKeyDirty] = useState(false)
  const [description, setDescription] = useState('')
  const [responsibilities, setResponsibilities] = useState('')
  const [privateInstructions, setPrivateInstructions] = useState('')
  const [required, setRequired] = useState(false)
  const [isDefault, setIsDefault] = useState(false)
  const effectiveKey = keyDirty ? key : slugify(name, '-')
  const responsibilityList = responsibilities
    .split('\n')
    .map((item) => item.trim())
    .filter(Boolean)

  return (
    <Dialog open onOpenChange={(next) => !next && onClose()}>
      <DialogContent className="max-h-[85vh] overflow-y-auto">
        <DialogHeader>
          <DialogTitle>New responder role</DialogTitle>
          <DialogDescription>Define responsibilities and optional private instructions.</DialogDescription>
        </DialogHeader>
        <div className="grid gap-3 py-2">
          <div className="grid gap-3 sm:grid-cols-2">
            <div className="grid gap-1.5">
              <Label htmlFor="role-name">Name</Label>
              <Input id="role-name" value={name} onChange={(e) => setName(e.target.value)} placeholder="Scribe" />
            </div>
            <div className="grid gap-1.5">
              <Label htmlFor="role-key">Key</Label>
              <Input
                id="role-key"
                value={effectiveKey}
                onChange={(e) => {
                  setKeyDirty(true)
                  setKey(e.target.value)
                }}
                className="font-mono"
              />
            </div>
          </div>
          <div className="grid gap-1.5">
            <Label htmlFor="role-description">Description</Label>
            <Input id="role-description" value={description} onChange={(e) => setDescription(e.target.value)} />
          </div>
          <div className="grid gap-1.5">
            <Label htmlFor="role-responsibilities">Responsibilities (one per line)</Label>
            <Textarea
              id="role-responsibilities"
              value={responsibilities}
              onChange={(e) => setResponsibilities(e.target.value)}
              rows={3}
              placeholder={'Keep the running record\nTrack decisions and actions'}
            />
          </div>
          <div className="grid gap-1.5">
            <Label htmlFor="role-private">Private responder instructions</Label>
            <Textarea
              id="role-private"
              value={privateInstructions}
              onChange={(e) => setPrivateInstructions(e.target.value)}
              rows={2}
              placeholder="Delivered privately to the assignee; omitted from the general incident detail and timeline."
            />
            <p className="text-[11px] text-muted-foreground">
              Delivered privately to the assignee and omitted from the general incident detail and timeline. They
              stay visible here in configuration for anyone who can open this page.
            </p>
          </div>
          <div className="flex flex-wrap gap-4">
            <label className="flex items-center gap-2 text-sm">
              <Switch checked={required} onCheckedChange={setRequired} />
              Required
            </label>
            <label className="flex items-center gap-2 text-sm">
              <Switch checked={isDefault} onCheckedChange={setIsDefault} />
              Assign by default
            </label>
          </div>
        </div>
        <DialogFooter>
          <Button variant="outline" onClick={onClose} disabled={isPending}>
            Cancel
          </Button>
          <Button
            disabled={isPending || !name.trim() || !effectiveKey || responsibilityList.length === 0}
            onClick={() =>
              onCreate({
                key: effectiveKey,
                name: name.trim(),
                description: description.trim() || undefined,
                responsibilities: responsibilityList,
                privateInstructions: privateInstructions.trim() || undefined,
                required,
                default: isDefault,
              })
            }
          >
            {isPending ? 'Saving…' : 'Save role'}
          </Button>
        </DialogFooter>
      </DialogContent>
    </Dialog>
  )
}
