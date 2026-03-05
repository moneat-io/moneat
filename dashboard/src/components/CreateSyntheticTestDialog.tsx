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

import {useMutation} from '@tanstack/react-query'
import {api, type CreateSyntheticTestPayload} from '@/lib/api'
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
import {Select, SelectContent, SelectItem, SelectTrigger, SelectValue} from '@/components/ui/select'
import {Textarea} from '@/components/ui/textarea'
import {FlaskConical, GitMerge, Plus, X, Shield, Globe, Wifi} from 'lucide-react'
import {useState} from 'react'
import {useToast} from '@/hooks/use-toast'

interface CreateSyntheticTestDialogProps {
  open: boolean
  onOpenChange: (open: boolean) => void
  onSuccess: () => void
}

interface Header {
  key: string
  value: string
}

interface Assertion {
  type: string
  operator: string
  value: string
  target: string
}

interface VariableExtraction {
  name: string
  source: string
  path: string
}

interface TestStep {
  name: string
  url: string
  method: string
  body: string
  extractions: VariableExtraction[]
}

interface FormState {
  type: 'api' | 'multistep' | 'ssl' | 'dns' | 'tcp'
  name: string
  url: string
  method: string
  headers: Header[]
  body: string
  authMethod: string
  authUsername: string
  authPassword: string
  authToken: string
  steps: TestStep[]
  assertions: Assertion[]
  intervalMinutes: number
  timeoutSeconds: number
  tags: string[]
  tagInput: string
  retryCount: number
  retryIntervalMs: number
  alertOnFailure: boolean
  hostname: string
  port: string
}

const DEFAULT_FORM: FormState = {
  type: 'api',
  name: '',
  url: '',
  method: 'GET',
  headers: [],
  body: '',
  authMethod: 'none',
  authUsername: '',
  authPassword: '',
  authToken: '',
  steps: [{name: 'Step 1', url: '', method: 'GET', body: '', extractions: []}],
  assertions: [{type: 'status_code', operator: 'equals', value: '200', target: ''}],
  intervalMinutes: 5,
  timeoutSeconds: 30,
  tags: [],
  tagInput: '',
  retryCount: 0,
  retryIntervalMs: 300,
  alertOnFailure: false,
  hostname: '',
  port: '',
}

type DialogStep = 1 | 2 | 3 | 4

const STEP_DESCRIPTIONS: Record<DialogStep, string> = {
  1: 'Select test type',
  2: 'Configure the test',
  3: 'Define assertions',
  4: 'Set schedule',
}

const HTTP_METHODS = ['GET', 'POST', 'PUT', 'PATCH', 'DELETE']
const BODY_METHODS = ['POST', 'PUT', 'PATCH']

function toCreateSyntheticTestPayload(form: FormState): CreateSyntheticTestPayload {
  const headers = form.headers
    .filter((header) => header.key.trim().length > 0)
    .reduce<Record<string, string>>((acc, header) => {
      acc[header.key.trim()] = header.value
      return acc
    }, {})

  const authMethod = form.authMethod === 'none' ? null : form.authMethod
  const authUser = authMethod === 'basic' ? form.authUsername || null : null
  const authPass =
    authMethod === 'basic'
      ? form.authPassword || null
      : authMethod === 'bearer'
        ? form.authToken || null
        : null

  const assertions = form.assertions.map((assertion) => ({
    type: assertion.type,
    target: assertion.target,
    operator: assertion.operator,
    value: assertion.value,
  }))

  const steps =
    form.type === 'multistep'
      ? form.steps.map((step, index) => ({
          name: step.name || `Step ${index + 1}`,
          url: step.url.trim(),
          method: step.method,
          body: BODY_METHODS.includes(step.method) && step.body ? step.body : null,
          assertions: [],
          extractVariables: step.extractions
            .filter((extraction) => extraction.name.trim().length > 0)
            .map((extraction) => ({
              name: extraction.name.trim(),
              source: extraction.source === 'json_path' ? 'body_json_path' : extraction.source,
              path: extraction.path.trim(),
            })),
        }))
      : []

  const config = ['ssl', 'dns', 'tcp'].includes(form.type)
    ? {
        hostname: form.hostname || null,
        port: form.port ? parseInt(form.port) : null,
        protocol: form.type === 'tcp' ? 'tcp' : null,
      }
    : null

  return {
    name: form.name.trim(),
    testType: form.type,
    intervalSeconds: form.intervalMinutes * 60,
    timeoutSeconds: form.timeoutSeconds,
    url: ['api', 'dns'].includes(form.type) ? form.url.trim() || null : null,
    method: form.method,
    headers: Object.keys(headers).length > 0 ? headers : null,
    body: BODY_METHODS.includes(form.method) && form.body ? form.body : null,
    authMethod: authMethod as 'basic' | 'bearer' | null,
    authUser,
    authPass,
    assertions,
    steps,
    tags: form.tags,
    retryCount: form.retryCount,
    retryIntervalMs: form.retryIntervalMs,
    alertOnFailure: form.alertOnFailure,
    config,
  }
}

export default function CreateSyntheticTestDialog({
  open,
  onOpenChange,
  onSuccess,
}: CreateSyntheticTestDialogProps) {
  const {toast} = useToast()
  const [step, setStep] = useState<DialogStep>(1)
  const [form, setForm] = useState<FormState>(DEFAULT_FORM)

  const createMutation = useMutation({
    mutationFn: (data: CreateSyntheticTestPayload) => api.createSyntheticTest(data),
    onSuccess: () => {
      toast({title: 'Synthetic test created successfully'})
      onSuccess()
      handleClose()
    },
    onError: (error: Error) => {
      toast({title: 'Failed to create test', description: error.message, variant: 'destructive'})
    },
  })

  const handleClose = () => {
    setStep(1)
    setForm(DEFAULT_FORM)
    onOpenChange(false)
  }

  const handleCreate = () => {
    if (!form.name) {
      toast({title: 'Test name is required', variant: 'destructive'})
      return
    }
    if (form.type === 'api' && !form.url) {
      toast({title: 'URL is required', variant: 'destructive'})
      return
    }
    if (form.type === 'multistep' && form.steps.some((s) => !s.url)) {
      toast({title: 'All steps must have a URL', variant: 'destructive'})
      return
    }
    if (form.type === 'ssl' && !form.hostname) {
      toast({title: 'Hostname is required for SSL tests', variant: 'destructive'})
      return
    }
    if (form.type === 'dns' && !form.url) {
      toast({title: 'Hostname is required for DNS tests', variant: 'destructive'})
      return
    }
    if (form.type === 'tcp' && (!form.hostname || !form.port)) {
      toast({title: 'Hostname and port are required for TCP tests', variant: 'destructive'})
      return
    }
    createMutation.mutate(toCreateSyntheticTestPayload(form))
  }

  const addHeader = () => setForm((f) => ({...f, headers: [...f.headers, {key: '', value: ''}]}))
  const removeHeader = (i: number) =>
    setForm((f) => ({...f, headers: f.headers.filter((_, idx) => idx !== i)}))
  const updateHeader = (i: number, field: 'key' | 'value', value: string) =>
    setForm((f) => ({
      ...f,
      headers: f.headers.map((h, idx) => (idx === i ? {...h, [field]: value} : h)),
    }))

  const addAssertion = () =>
    setForm((f) => ({
      ...f,
      assertions: [...f.assertions, {type: 'status_code', operator: 'equals', value: '', target: ''}],
    }))
  const removeAssertion = (i: number) =>
    setForm((f) => ({...f, assertions: f.assertions.filter((_, idx) => idx !== i)}))
  const updateAssertion = (i: number, field: keyof Assertion, value: string) =>
    setForm((f) => ({
      ...f,
      assertions: f.assertions.map((a, idx) => (idx === i ? {...a, [field]: value} : a)),
    }))

  const addStep = () =>
    setForm((f) => ({
      ...f,
      steps: [
        ...f.steps,
        {name: `Step ${f.steps.length + 1}`, url: '', method: 'GET', body: '', extractions: []},
      ],
    }))
  const removeStep = (i: number) =>
    setForm((f) => ({...f, steps: f.steps.filter((_, idx) => idx !== i)}))
  const updateStep = (i: number, field: keyof Omit<TestStep, 'extractions'>, value: string) =>
    setForm((f) => ({
      ...f,
      steps: f.steps.map((s, idx) => (idx === i ? {...s, [field]: value} : s)),
    }))

  const addExtraction = (stepIdx: number) =>
    setForm((f) => ({
      ...f,
      steps: f.steps.map((s, idx) =>
        idx === stepIdx
          ? {...s, extractions: [...s.extractions, {name: '', source: 'json_path', path: ''}]}
          : s
      ),
    }))
  const removeExtraction = (stepIdx: number, exIdx: number) =>
    setForm((f) => ({
      ...f,
      steps: f.steps.map((s, idx) =>
        idx === stepIdx ? {...s, extractions: s.extractions.filter((_, i) => i !== exIdx)} : s
      ),
    }))
  const updateExtraction = (stepIdx: number, exIdx: number, field: keyof VariableExtraction, value: string) =>
    setForm((f) => ({
      ...f,
      steps: f.steps.map((s, idx) =>
        idx === stepIdx
          ? {...s, extractions: s.extractions.map((e, i) => (i === exIdx ? {...e, [field]: value} : e))}
          : s
      ),
    }))

  return (
    <Dialog open={open} onOpenChange={onOpenChange}>
      <DialogContent className="max-w-2xl max-h-[90vh] overflow-y-auto">
        <DialogHeader>
          <DialogTitle>Create Synthetic Test</DialogTitle>
          <DialogDescription>{STEP_DESCRIPTIONS[step]}</DialogDescription>
        </DialogHeader>

        {step === 1 && (
          <div className="grid grid-cols-1 md:grid-cols-2 gap-4 py-4">
            <button
              onClick={() => {
                setForm((f) => ({...f, type: 'api'}))
                setStep(2)
              }}
              className="flex items-start p-4 border rounded-xl hover:bg-accent hover:border-primary/50 transition-all text-left group"
            >
              <div className="p-2 rounded-lg mr-4 bg-violet-500/10 text-violet-500 group-hover:scale-110 transition-transform">
                <FlaskConical className="h-6 w-6" />
              </div>
              <div>
                <div className="font-semibold mb-1">API Test</div>
                <div className="text-sm text-muted-foreground">Single HTTP request with assertions</div>
              </div>
            </button>
            <button
              onClick={() => {
                setForm((f) => ({...f, type: 'multistep'}))
                setStep(2)
              }}
              className="flex items-start p-4 border rounded-xl hover:bg-accent hover:border-primary/50 transition-all text-left group"
            >
              <div className="p-2 rounded-lg mr-4 bg-blue-500/10 text-blue-500 group-hover:scale-110 transition-transform">
                <GitMerge className="h-6 w-6" />
              </div>
              <div>
                <div className="font-semibold mb-1">Multistep API Test</div>
                <div className="text-sm text-muted-foreground">Chain of HTTP requests with variable extraction</div>
              </div>
            </button>
            <button
              onClick={() => {
                setForm((f) => ({...f, type: 'ssl', assertions: [{type: 'certificate_expiry_days', operator: 'greater_than', value: '30', target: ''}]}))
                setStep(2)
              }}
              className="flex items-start p-4 border rounded-xl hover:bg-accent hover:border-primary/50 transition-all text-left group"
            >
              <div className="p-2 rounded-lg mr-4 bg-amber-500/10 text-amber-500 group-hover:scale-110 transition-transform">
                <Shield className="h-6 w-6" />
              </div>
              <div>
                <div className="font-semibold mb-1">SSL Certificate</div>
                <div className="text-sm text-muted-foreground">Monitor certificate validity and expiry</div>
              </div>
            </button>
            <button
              onClick={() => {
                setForm((f) => ({...f, type: 'dns', assertions: [{type: 'resolution_time', operator: 'less_than', value: '1000', target: ''}]}))
                setStep(2)
              }}
              className="flex items-start p-4 border rounded-xl hover:bg-accent hover:border-primary/50 transition-all text-left group"
            >
              <div className="p-2 rounded-lg mr-4 bg-cyan-500/10 text-cyan-500 group-hover:scale-110 transition-transform">
                <Globe className="h-6 w-6" />
              </div>
              <div>
                <div className="font-semibold mb-1">DNS Check</div>
                <div className="text-sm text-muted-foreground">Validate DNS resolution and records</div>
              </div>
            </button>
            <button
              onClick={() => {
                setForm((f) => ({...f, type: 'tcp', assertions: [{type: 'port_open', operator: 'equals', value: 'true', target: ''}]}))
                setStep(2)
              }}
              className="flex items-start p-4 border rounded-xl hover:bg-accent hover:border-primary/50 transition-all text-left group"
            >
              <div className="p-2 rounded-lg mr-4 bg-emerald-500/10 text-emerald-500 group-hover:scale-110 transition-transform">
                <Wifi className="h-6 w-6" />
              </div>
              <div>
                <div className="font-semibold mb-1">TCP Check</div>
                <div className="text-sm text-muted-foreground">Test port connectivity and response time</div>
              </div>
            </button>
          </div>
        )}

        {step === 2 && (
          <div className="space-y-4 py-4">
            <div>
              <Label htmlFor="test-name">Test Name</Label>
              <Input
                id="test-name"
                value={form.name}
                onChange={(e) => setForm((f) => ({...f, name: e.target.value}))}
                placeholder="My API Test"
              />
            </div>

            {form.type === 'api' && (
              <>
                <div className="flex gap-2">
                  <div className="w-32 shrink-0">
                    <Label htmlFor="method">Method</Label>
                    <Select value={form.method} onValueChange={(v) => setForm((f) => ({...f, method: v}))}>
                      <SelectTrigger id="method">
                        <SelectValue />
                      </SelectTrigger>
                      <SelectContent>
                        {HTTP_METHODS.map((m) => (
                          <SelectItem key={m} value={m}>{m}</SelectItem>
                        ))}
                      </SelectContent>
                    </Select>
                  </div>
                  <div className="flex-1">
                    <Label htmlFor="url">URL</Label>
                    <Input
                      id="url"
                      value={form.url}
                      onChange={(e) => setForm((f) => ({...f, url: e.target.value}))}
                      placeholder="https://api.example.com/health"
                    />
                  </div>
                </div>

                <div>
                  <div className="flex items-center justify-between mb-2">
                    <Label>Headers</Label>
                    <Button variant="outline" size="sm" onClick={addHeader}>
                      <Plus className="h-3.5 w-3.5 mr-1" />Add Header
                    </Button>
                  </div>
                  <div className="space-y-2">
                    {form.headers.map((header, i) => (
                      <div key={i} className="flex gap-2">
                        <Input
                          placeholder="Key"
                          value={header.key}
                          onChange={(e) => updateHeader(i, 'key', e.target.value)}
                        />
                        <Input
                          placeholder="Value"
                          value={header.value}
                          onChange={(e) => updateHeader(i, 'value', e.target.value)}
                        />
                        <Button variant="ghost" size="icon" onClick={() => removeHeader(i)}>
                          <X className="h-4 w-4" />
                        </Button>
                      </div>
                    ))}
                  </div>
                </div>

                {BODY_METHODS.includes(form.method) && (
                  <div>
                    <Label htmlFor="body">Request Body</Label>
                    <Textarea
                      id="body"
                      value={form.body}
                      onChange={(e) => setForm((f) => ({...f, body: e.target.value}))}
                      placeholder='{"key": "value"}'
                      rows={4}
                    />
                  </div>
                )}

                <div>
                  <Label htmlFor="auth-method">Authentication</Label>
                  <Select
                    value={form.authMethod}
                    onValueChange={(v) => setForm((f) => ({...f, authMethod: v}))}
                  >
                    <SelectTrigger id="auth-method">
                      <SelectValue />
                    </SelectTrigger>
                    <SelectContent>
                      <SelectItem value="none">None</SelectItem>
                      <SelectItem value="basic">Basic Auth</SelectItem>
                      <SelectItem value="bearer">Bearer Token</SelectItem>
                    </SelectContent>
                  </Select>
                </div>

                {form.authMethod === 'basic' && (
                  <div className="flex gap-2">
                    <div className="flex-1">
                      <Label htmlFor="auth-username">Username</Label>
                      <Input
                        id="auth-username"
                        value={form.authUsername}
                        onChange={(e) => setForm((f) => ({...f, authUsername: e.target.value}))}
                        placeholder="username"
                      />
                    </div>
                    <div className="flex-1">
                      <Label htmlFor="auth-password">Password</Label>
                      <Input
                        id="auth-password"
                        type="password"
                        value={form.authPassword}
                        onChange={(e) => setForm((f) => ({...f, authPassword: e.target.value}))}
                        placeholder="password"
                      />
                    </div>
                  </div>
                )}

                {form.authMethod === 'bearer' && (
                  <div>
                    <Label htmlFor="auth-token">Bearer Token</Label>
                    <Input
                      id="auth-token"
                      value={form.authToken}
                      onChange={(e) => setForm((f) => ({...f, authToken: e.target.value}))}
                      placeholder="your-token"
                    />
                  </div>
                )}
              </>
            )}

            {form.type === 'multistep' && (
              <div className="space-y-3">
                <div className="flex items-center justify-between">
                  <Label>Steps</Label>
                  <Button variant="outline" size="sm" onClick={addStep}>
                    <Plus className="h-3.5 w-3.5 mr-1" />Add Step
                  </Button>
                </div>
                {form.steps.map((s, si) => (
                  <div key={si} className="border rounded-lg p-4 space-y-3">
                    <div className="flex items-center justify-between">
                      <span className="text-sm font-medium text-muted-foreground">Step {si + 1}</span>
                      {form.steps.length > 1 && (
                        <Button variant="ghost" size="icon" onClick={() => removeStep(si)}>
                          <X className="h-4 w-4" />
                        </Button>
                      )}
                    </div>
                    <Input
                      placeholder="Step name"
                      value={s.name}
                      onChange={(e) => updateStep(si, 'name', e.target.value)}
                    />
                    <div className="flex gap-2">
                      <div className="w-28 shrink-0">
                        <Select value={s.method} onValueChange={(v) => updateStep(si, 'method', v)}>
                          <SelectTrigger>
                            <SelectValue />
                          </SelectTrigger>
                          <SelectContent>
                            {HTTP_METHODS.map((m) => (
                              <SelectItem key={m} value={m}>{m}</SelectItem>
                            ))}
                          </SelectContent>
                        </Select>
                      </div>
                      <Input
                        placeholder="https://api.example.com/endpoint"
                        value={s.url}
                        onChange={(e) => updateStep(si, 'url', e.target.value)}
                        className="flex-1"
                      />
                    </div>
                    {BODY_METHODS.includes(s.method) && (
                      <Textarea
                        placeholder='{"key": "value"}'
                        value={s.body}
                        onChange={(e) => updateStep(si, 'body', e.target.value)}
                        rows={3}
                      />
                    )}
                    <div className="space-y-2">
                      <div className="flex items-center justify-between">
                        <span className="text-xs font-medium text-muted-foreground">Variable Extractions</span>
                        <Button variant="ghost" size="sm" className="h-7 text-xs" onClick={() => addExtraction(si)}>
                          <Plus className="h-3 w-3 mr-1" />Extract
                        </Button>
                      </div>
                      {s.extractions.map((ex, ei) => (
                        <div key={ei} className="flex gap-2 items-center">
                          <Input
                            placeholder="var name"
                            value={ex.name}
                            onChange={(e) => updateExtraction(si, ei, 'name', e.target.value)}
                            className="w-28"
                          />
                          <Select
                            value={ex.source}
                            onValueChange={(v) => updateExtraction(si, ei, 'source', v)}
                          >
                            <SelectTrigger className="w-32">
                              <SelectValue />
                            </SelectTrigger>
                            <SelectContent>
                              <SelectItem value="json_path">JSON Path</SelectItem>
                              <SelectItem value="header">Header</SelectItem>
                            </SelectContent>
                          </Select>
                          <Input
                            placeholder="$.data.id"
                            value={ex.path}
                            onChange={(e) => updateExtraction(si, ei, 'path', e.target.value)}
                            className="flex-1"
                          />
                          <Button variant="ghost" size="icon" onClick={() => removeExtraction(si, ei)}>
                            <X className="h-3.5 w-3.5" />
                          </Button>
                        </div>
                      ))}
                    </div>
                  </div>
                ))}
              </div>
            )}

            {form.type === 'ssl' && (
              <div>
                <Label htmlFor="hostname">Hostname</Label>
                <Input
                  id="hostname"
                  value={form.hostname}
                  onChange={(e) => setForm((f) => ({...f, hostname: e.target.value}))}
                  placeholder="example.com"
                />
                <p className="text-xs text-muted-foreground mt-1">Domain to check SSL certificate for</p>
              </div>
            )}

            {form.type === 'dns' && (
              <div>
                <Label htmlFor="dns-url">Hostname</Label>
                <Input
                  id="dns-url"
                  value={form.url}
                  onChange={(e) => setForm((f) => ({...f, url: e.target.value}))}
                  placeholder="example.com"
                />
                <p className="text-xs text-muted-foreground mt-1">Hostname to resolve</p>
              </div>
            )}

            {form.type === 'tcp' && (
              <div className="space-y-3">
                <div>
                  <Label htmlFor="tcp-host">Hostname</Label>
                  <Input
                    id="tcp-host"
                    value={form.hostname}
                    onChange={(e) => setForm((f) => ({...f, hostname: e.target.value}))}
                    placeholder="example.com"
                  />
                </div>
                <div>
                  <Label htmlFor="tcp-port">Port</Label>
                  <Input
                    id="tcp-port"
                    type="number"
                    value={form.port}
                    onChange={(e) => setForm((f) => ({...f, port: e.target.value}))}
                    placeholder="443"
                  />
                </div>
              </div>
            )}

            {/* Tags */}
            <div>
              <Label>Tags</Label>
              <div className="flex gap-2 mt-1">
                <Input
                  placeholder="Add tag..."
                  value={form.tagInput}
                  onChange={(e) => setForm((f) => ({...f, tagInput: e.target.value}))}
                  onKeyDown={(e) => {
                    if (e.key === 'Enter' && form.tagInput.trim()) {
                      e.preventDefault()
                      setForm((f) => ({
                        ...f,
                        tags: [...f.tags, f.tagInput.trim()],
                        tagInput: '',
                      }))
                    }
                  }}
                />
                <Button
                  variant="outline"
                  size="sm"
                  type="button"
                  onClick={() => {
                    if (form.tagInput.trim()) {
                      setForm((f) => ({
                        ...f,
                        tags: [...f.tags, f.tagInput.trim()],
                        tagInput: '',
                      }))
                    }
                  }}
                >
                  Add
                </Button>
              </div>
              {form.tags.length > 0 && (
                <div className="flex gap-1 mt-2 flex-wrap">
                  {form.tags.map((tag, i) => (
                    <span
                      key={i}
                      className="inline-flex items-center gap-1 px-2 py-0.5 bg-secondary text-secondary-foreground rounded-md text-xs"
                    >
                      {tag}
                      <button
                        onClick={() => setForm((f) => ({...f, tags: f.tags.filter((_, idx) => idx !== i)}))}
                        className="hover:text-destructive"
                      >
                        <X className="h-3 w-3" />
                      </button>
                    </span>
                  ))}
                </div>
              )}
            </div>
          </div>
        )}

        {step === 3 && (
          <div className="space-y-4 py-4">
            <div className="flex items-center justify-between">
              <Label>Assertions</Label>
              <Button variant="outline" size="sm" onClick={addAssertion}>
                <Plus className="h-3.5 w-3.5 mr-1" />Add Assertion
              </Button>
            </div>
            <div className="space-y-3">
              {form.assertions.map((assertion, i) => (
                <div key={i} className="flex gap-2 flex-wrap items-center">
                  <Select
                    value={assertion.type}
                    onValueChange={(v) => updateAssertion(i, 'type', v)}
                  >
                    <SelectTrigger className="w-36">
                      <SelectValue />
                    </SelectTrigger>
                    <SelectContent>
                      <SelectItem value="status_code">Status Code</SelectItem>
                      <SelectItem value="body_contains">Body Contains</SelectItem>
                      <SelectItem value="response_time">Response Time</SelectItem>
                      <SelectItem value="header">Header</SelectItem>
                      <SelectItem value="certificate_expiry_days">Cert Expiry (days)</SelectItem>
                      <SelectItem value="certificate_valid">Cert Valid</SelectItem>
                      <SelectItem value="resolved_ip">Resolved IP</SelectItem>
                      <SelectItem value="resolution_time">Resolution Time</SelectItem>
                      <SelectItem value="connection_time">Connection Time</SelectItem>
                      <SelectItem value="port_open">Port Open</SelectItem>
                    </SelectContent>
                  </Select>
                  {assertion.type === 'header' && (
                    <Input
                      placeholder="Header name"
                      value={assertion.target}
                      onChange={(e) => updateAssertion(i, 'target', e.target.value)}
                      className="w-36"
                    />
                  )}
                  <Select
                    value={assertion.operator}
                    onValueChange={(v) => updateAssertion(i, 'operator', v)}
                  >
                    <SelectTrigger className="w-36">
                      <SelectValue />
                    </SelectTrigger>
                    <SelectContent>
                      <SelectItem value="equals">Equals</SelectItem>
                      <SelectItem value="contains">Contains</SelectItem>
                      <SelectItem value="less_than">Less than</SelectItem>
                      <SelectItem value="greater_than">Greater than</SelectItem>
                    </SelectContent>
                  </Select>
                  <Input
                    placeholder="Value"
                    value={assertion.value}
                    onChange={(e) => updateAssertion(i, 'value', e.target.value)}
                    className="flex-1 min-w-24"
                  />
                  <Button variant="ghost" size="icon" onClick={() => removeAssertion(i)}>
                    <X className="h-4 w-4" />
                  </Button>
                </div>
              ))}
            </div>
          </div>
        )}

        {step === 4 && (
          <div className="space-y-4 py-4">
            <div>
              <Label htmlFor="interval">Check Interval</Label>
              <Select
                value={String(form.intervalMinutes)}
                onValueChange={(v) => setForm((f) => ({...f, intervalMinutes: parseInt(v)}))}
              >
                <SelectTrigger id="interval">
                  <SelectValue />
                </SelectTrigger>
                <SelectContent>
                  <SelectItem value="1">Every 1 minute</SelectItem>
                  <SelectItem value="5">Every 5 minutes</SelectItem>
                  <SelectItem value="15">Every 15 minutes</SelectItem>
                  <SelectItem value="30">Every 30 minutes</SelectItem>
                  <SelectItem value="60">Every 60 minutes</SelectItem>
                </SelectContent>
              </Select>
            </div>
            <div>
              <Label htmlFor="timeout">Timeout (seconds)</Label>
              <Input
                id="timeout"
                type="number"
                min={10}
                max={60}
                value={form.timeoutSeconds}
                onChange={(e) => setForm((f) => ({...f, timeoutSeconds: parseInt(e.target.value)}))}
              />
              <p className="text-xs text-muted-foreground mt-1">Between 10 and 60 seconds</p>
            </div>
            <div className="border-t pt-4 mt-4">
              <Label className="text-sm font-medium">Retry Policy</Label>
              <div className="flex gap-3 mt-2">
                <div className="flex-1">
                  <Label htmlFor="retry-count" className="text-xs text-muted-foreground">Retries</Label>
                  <Select
                    value={String(form.retryCount)}
                    onValueChange={(v) => setForm((f) => ({...f, retryCount: parseInt(v)}))}
                  >
                    <SelectTrigger id="retry-count">
                      <SelectValue />
                    </SelectTrigger>
                    <SelectContent>
                      <SelectItem value="0">None</SelectItem>
                      <SelectItem value="1">1 retry</SelectItem>
                      <SelectItem value="2">2 retries</SelectItem>
                      <SelectItem value="3">3 retries</SelectItem>
                    </SelectContent>
                  </Select>
                </div>
                {form.retryCount > 0 && (
                  <div className="flex-1">
                    <Label htmlFor="retry-interval" className="text-xs text-muted-foreground">Interval (ms)</Label>
                    <Input
                      id="retry-interval"
                      type="number"
                      min={100}
                      max={5000}
                      value={form.retryIntervalMs}
                      onChange={(e) => setForm((f) => ({...f, retryIntervalMs: parseInt(e.target.value)}))}
                    />
                  </div>
                )}
              </div>
            </div>
            <div className="border-t pt-4 mt-4">
              <div className="flex items-center gap-2">
                <input
                  type="checkbox"
                  id="alert-on-failure"
                  checked={form.alertOnFailure}
                  onChange={(e) => setForm((f) => ({...f, alertOnFailure: e.target.checked}))}
                  className="rounded border-input"
                />
                <Label htmlFor="alert-on-failure" className="text-sm">
                  Alert on failure
                </Label>
              </div>
              <p className="text-xs text-muted-foreground mt-1">
                Send notifications when this test transitions from passing to failing
              </p>
            </div>
          </div>
        )}

        <DialogFooter>
          {step > 1 && (
            <Button variant="outline" onClick={() => setStep((step - 1) as DialogStep)}>
              Back
            </Button>
          )}
          <Button variant="outline" onClick={handleClose}>
            Cancel
          </Button>
          {step > 1 && step < 4 && (
            <Button onClick={() => setStep((step + 1) as DialogStep)}>
              Next
            </Button>
          )}
          {step === 4 && (
            <Button onClick={handleCreate} disabled={createMutation.isPending}>
              {createMutation.isPending ? 'Creating...' : 'Create Test'}
            </Button>
          )}
        </DialogFooter>
      </DialogContent>
    </Dialog>
  )
}
