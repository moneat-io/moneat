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
import type {
  CreateDetectionRuleRequest,
  DetectionPreviewResponse,
  DetectionRuleResponse,
} from '@/lib/api'
import {Button} from '@/components/ui/button'
import {Input} from '@/components/ui/input'
import {Label} from '@/components/ui/label'
import {Textarea} from '@/components/ui/textarea'
import {Switch} from '@/components/ui/switch'
import {CodeEditor} from '@/components/ui/code-editor'
import {SEVERITY_ORDER} from './securityChips'
import {buildRuleRequest, emptyRuleForm, ruleToForm, type RuleFormState} from './ruleFormState'
import {RulePreview} from './RulePreview'

interface RuleFormProps {
  // Existing rule when editing; undefined when creating.
  rule?: DetectionRuleResponse
  // Creates when ruleId is undefined, otherwise updates that rule. After a preview persists a draft
  // we hold its id and pass it here so later saves update the draft instead of creating duplicates.
  onSubmit: (request: CreateDetectionRuleRequest, ruleId?: number) => Promise<DetectionRuleResponse>
  // Runs a preview against a saved rule id (preview evaluates the persisted rule).
  onPreview: (ruleId: number) => Promise<DetectionPreviewResponse>
  onCancel: () => void
  isSubmitting?: boolean
}

const TYPE_OPTIONS: {value: RuleFormState['type']; label: string}[] = [
  {value: 'threshold', label: 'Threshold'},
  {value: 'new_value', label: 'New value'},
]

export function RuleForm({rule, onSubmit, onPreview, onCancel, isSubmitting}: RuleFormProps) {
  const [form, setForm] = useState<RuleFormState>(() => (rule ? ruleToForm(rule) : emptyRuleForm()))
  const [savedRuleId, setSavedRuleId] = useState<number | undefined>(rule?.id)
  const [error, setError] = useState<string | null>(null)
  const [preview, setPreview] = useState<DetectionPreviewResponse | null>(null)
  const [previewing, setPreviewing] = useState(false)
  const [previewError, setPreviewError] = useState<string | null>(null)
  const [draftSaved, setDraftSaved] = useState(false)

  const isUnsaved = savedRuleId == null

  const set = <K extends keyof RuleFormState>(key: K, value: RuleFormState[K]) =>
    setForm((prev) => ({...prev, [key]: value}))

  const handleSubmit = () => {
    const built = buildRuleRequest(form)
    if (!built.ok) {
      setError(built.error)
      return
    }
    setError(null)
    // Once a preview (or an earlier save) has persisted the rule, route subsequent saves through its
    // id so we update rather than create a duplicate.
    // onError on the parent mutation surfaces failures; swallow here to avoid an unhandled rejection.
    void onSubmit(built.request, savedRuleId)
      .then((saved) => setSavedRuleId(saved.id))
      .catch(() => {})
  }

  const handlePreview = () => {
    const built = buildRuleRequest(form)
    if (!built.ok) {
      setError(built.error)
      return
    }
    setError(null)
    setPreviewing(true)
    setPreviewError(null)
    // The backend /preview endpoint evaluates a persisted rule, so we must save before previewing.
    // For an unsaved rule we save it as a DISABLED draft regardless of the Enabled toggle, so
    // previewing never silently enables a rule the scheduler would then run. For an already-saved
    // rule we update-then-preview, preserving its current enabled state.
    // TODO: a stateless preview endpoint (evaluate an unsaved rule body) would remove this save.
    const previewingUnsaved = isUnsaved
    const request = previewingUnsaved ? {...built.request, enabled: false} : built.request
    onSubmit(request, savedRuleId)
      .then((saved) => {
        setSavedRuleId(saved.id)
        if (previewingUnsaved) {
          setForm((prev) => ({...prev, enabled: false}))
          setDraftSaved(true)
        }
        return onPreview(saved.id)
      })
      .then((result) => setPreview(result))
      .catch((err: unknown) => setPreviewError(err instanceof Error ? err.message : 'Preview failed'))
      .finally(() => setPreviewing(false))
  }

  return (
    <div className="space-y-3">
      <Field label="Name" htmlFor="rule-name">
        <Input
          id="rule-name"
          value={form.name}
          onChange={(e) => set('name', e.target.value)}
          placeholder="Repeated failed logins"
          className="h-8 text-xs"
        />
      </Field>

      <Field label="Description" htmlFor="rule-description">
        <Input
          id="rule-description"
          value={form.description}
          onChange={(e) => set('description', e.target.value)}
          className="h-8 text-xs"
        />
      </Field>

      <Field label="Filter" htmlFor="rule-filter">
        <CodeEditor
          value={form.filter}
          onChange={(value) => set('filter', value)}
          language="sql"
          rows={3}
          placeholder='status:"failed" service:auth'
        />
        <p className="mt-1 text-[10px] text-muted-foreground">Log query that selects matching events.</p>
      </Field>

      <div className="grid grid-cols-2 gap-3">
        <Field label="Type" htmlFor="rule-type">
          <select
            id="rule-type"
            aria-label="Rule type"
            value={form.type}
            onChange={(e) => set('type', e.target.value as RuleFormState['type'])}
            className="h-8 w-full rounded-md border border-input bg-background px-2 text-xs">
            {TYPE_OPTIONS.map((opt) => (
              <option key={opt.value} value={opt.value}>
                {opt.label}
              </option>
            ))}
          </select>
        </Field>
        <Field label="Severity" htmlFor="rule-severity">
          <select
            id="rule-severity"
            aria-label="Severity"
            value={form.severity}
            onChange={(e) => set('severity', e.target.value as RuleFormState['severity'])}
            className="h-8 w-full rounded-md border border-input bg-background px-2 text-xs capitalize">
            {SEVERITY_ORDER.map((sev) => (
              <option key={sev} value={sev}>
                {sev}
              </option>
            ))}
          </select>
        </Field>
      </div>

      <div className="grid grid-cols-2 gap-3">
        <Field label="Window (seconds)" htmlFor="rule-window">
          <Input
            id="rule-window"
            type="number"
            min={1}
            value={form.windowSeconds}
            onChange={(e) => set('windowSeconds', Number(e.target.value))}
            className="h-8 text-xs"
          />
        </Field>
        {form.type === 'threshold' && (
          <Field label="Threshold count" htmlFor="rule-threshold">
            <Input
              id="rule-threshold"
              type="number"
              min={1}
              value={form.thresholdCount}
              onChange={(e) => set('thresholdCount', e.target.value)}
              className="h-8 text-xs"
            />
          </Field>
        )}
      </div>

      <Field label="Group by" htmlFor="rule-groupby">
        <Input
          id="rule-groupby"
          value={form.groupBy}
          onChange={(e) => set('groupBy', e.target.value)}
          placeholder="user.id, source.ip"
          className="h-8 text-xs"
        />
        <p className="mt-1 text-[10px] text-muted-foreground">Comma-separated fields.</p>
      </Field>

      <Field label="Signal title" htmlFor="rule-signal-title">
        <Input
          id="rule-signal-title"
          value={form.signalTitle}
          onChange={(e) => set('signalTitle', e.target.value)}
          className="h-8 text-xs"
        />
      </Field>

      <Field label="Signal message" htmlFor="rule-signal-message">
        <Textarea
          id="rule-signal-message"
          value={form.signalMessage}
          onChange={(e) => set('signalMessage', e.target.value)}
          rows={2}
          className="text-xs"
        />
      </Field>

      <Field label="Tags" htmlFor="rule-tags">
        <Input
          id="rule-tags"
          value={form.tags}
          onChange={(e) => set('tags', e.target.value)}
          placeholder="auth, brute-force"
          className="h-8 text-xs"
        />
      </Field>

      <div className="flex items-center gap-2">
        <Switch
          checked={form.enabled}
          onCheckedChange={(checked) => set('enabled', checked)}
          aria-label="Enabled"
        />
        <Label className="text-xs">Enabled</Label>
      </div>

      {error && <p className="text-xs text-destructive">{error}</p>}

      <div className="flex items-center gap-2 pt-1">
        <Button size="sm" disabled={isSubmitting} onClick={handleSubmit}>
          {savedRuleId ? 'Save changes' : 'Create rule'}
        </Button>
        <Button size="sm" variant="outline" disabled={isSubmitting || previewing} onClick={handlePreview}>
          {previewing ? 'Previewing…' : isUnsaved ? 'Save draft & preview' : 'Preview'}
        </Button>
        <Button size="sm" variant="ghost" onClick={onCancel}>
          Cancel
        </Button>
      </div>

      {draftSaved && (
        <p className="text-xs text-muted-foreground">
          Draft saved (disabled). It won’t produce signals until you enable it.
        </p>
      )}
      {previewError && <p className="text-xs text-destructive">{previewError}</p>}
      {preview && <RulePreview preview={preview} />}
    </div>
  )
}

function Field({label, htmlFor, children}: {label: string; htmlFor: string; children: React.ReactNode}) {
  return (
    <div className="space-y-1">
      <Label htmlFor={htmlFor} className="text-[10px] uppercase tracking-wide text-muted-foreground">
        {label}
      </Label>
      {children}
    </div>
  )
}
