// Moneat - observability platform
// Copyright (C) 2026 Moneat
//
// This program is free software: you can redistribute it and/or modify
// it under the terms of the GNU Affero General Public License as published by
// the Free Software Foundation, either version 3 of the License, or
// (at your option) any later version.

import {useMutation, useQueryClient} from '@tanstack/react-query'
import {
  api,
  type CreateDebuggerProbeRequest,
  type DebuggerProbe,
  type DebuggerProbeType,
  type DebuggerProbeWhereType,
  type UpdateDebuggerProbeRequest,
} from '@/lib/api'
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
import {Switch} from '@/components/ui/switch'
import {Textarea} from '@/components/ui/textarea'
import {useToast} from '@/hooks/use-toast'
import {cn} from '@/lib/utils'
import {Activity, BarChart3, Camera, Loader2, Sparkles} from 'lucide-react'
import {useMemo, useState} from 'react'

interface CreateProbeDialogProps {
  open: boolean
  onOpenChange: (open: boolean) => void
  probe?: DebuggerProbe | null
}

interface ProbeTypeOption {
  value: DebuggerProbeType
  label: string
  description: string
  icon: typeof Activity
  iconClassName: string
  bgClassName: string
}

interface ProbeFormState {
  service: string
  environment: string
  language: string
  active: boolean
  whereType: DebuggerProbeWhereType
  typeName: string
  methodName: string
  sourceFile: string
  sourceLines: string
  template: string
  metricName: string
  metricKind: 'count' | 'gauge' | 'histogram'
  tags: string
  captureConfig: string
}

const probeTypeOptions: ProbeTypeOption[] = [
  {
    value: 'log_probe',
    label: 'Log Probe',
    description: 'Add log output at any code location without redeploying',
    icon: Activity,
    iconClassName: 'text-blue-600',
    bgClassName: 'bg-blue-500/10',
  },
  {
    value: 'snapshot',
    label: 'Snapshot',
    description: 'Capture local variable state at a code location',
    icon: Camera,
    iconClassName: 'text-emerald-600',
    bgClassName: 'bg-emerald-500/10',
  },
  {
    value: 'span_decoration',
    label: 'Span Decoration',
    description: 'Add custom attributes to active spans',
    icon: Sparkles,
    iconClassName: 'text-amber-600',
    bgClassName: 'bg-amber-500/10',
  },
  {
    value: 'metric_probe',
    label: 'Metric Probe',
    description: 'Emit a custom metric from a code location',
    icon: BarChart3,
    iconClassName: 'text-violet-600',
    bgClassName: 'bg-violet-500/10',
  },
]

const languageOptions = [
  {value: 'java', label: 'Java'},
  {value: 'python', label: 'Python'},
  {value: 'dotnet', label: '.NET'},
  {value: 'go', label: 'Go'},
  {value: 'ruby', label: 'Ruby'},
  {value: 'php', label: 'PHP'},
  {value: 'nodejs', label: 'Node.js'},
]

function createDefaultFormState(): ProbeFormState {
  return {
    service: '',
    environment: '*',
    language: 'java',
    active: true,
    whereType: 'method',
    typeName: '',
    methodName: '',
    sourceFile: '',
    sourceLines: '',
    template: '',
    metricName: '',
    metricKind: 'count',
    tags: '',
    captureConfig: '',
  }
}

function mapProbeToFormState(probe: DebuggerProbe): ProbeFormState {
  return {
    service: probe.service,
    environment: probe.environment,
    language: probe.language,
    active: probe.active,
    whereType: probe.whereType,
    typeName: probe.typeName ?? '',
    methodName: probe.methodName ?? '',
    sourceFile: probe.sourceFile ?? '',
    sourceLines: probe.sourceLines ?? '',
    template: probe.template ?? '',
    metricName: probe.metricName ?? '',
    metricKind: probe.metricKind === 'gauge' || probe.metricKind === 'histogram' ? probe.metricKind : 'count',
    tags: probe.tags ?? '',
    captureConfig: probe.captureConfig ?? '',
  }
}

export default function CreateProbeDialog({open, onOpenChange, probe}: CreateProbeDialogProps) {
  const {toast} = useToast()
  const queryClient = useQueryClient()
  const isEdit = Boolean(probe)

  const [step, setStep] = useState<1 | 2>(() => (probe ? 2 : 1))
  const [selectedType, setSelectedType] = useState<DebuggerProbeType>(() => probe?.probeType ?? 'log_probe')
  const [formState, setFormState] = useState<ProbeFormState>(() =>
    probe ? mapProbeToFormState(probe) : createDefaultFormState()
  )

  const createMutation = useMutation({
    mutationFn: (request: CreateDebuggerProbeRequest) => api.createDebuggerProbe(request),
    onSuccess: () => {
      queryClient.invalidateQueries({queryKey: ['debugger-probes']})
      toast({title: 'Probe created'})
      handleClose()
    },
    onError: (error: Error) => {
      toast({
        title: 'Failed to create probe',
        description: error.message,
        variant: 'destructive',
      })
    },
  })

  const updateMutation = useMutation({
    mutationFn: (payload: {probeId: string; request: UpdateDebuggerProbeRequest}) =>
      api.updateDebuggerProbe(payload.probeId, payload.request),
    onSuccess: () => {
      queryClient.invalidateQueries({queryKey: ['debugger-probes']})
      toast({title: 'Probe updated'})
      handleClose()
    },
    onError: (error: Error) => {
      toast({
        title: 'Failed to update probe',
        description: error.message,
        variant: 'destructive',
      })
    },
  })

  const isPending = createMutation.isPending || updateMutation.isPending

  const selectedTypeMeta = useMemo(
    () => probeTypeOptions.find((item) => item.value === selectedType) ?? probeTypeOptions[0],
    [selectedType]
  )

  const handleClose = () => {
    setStep(1)
    setSelectedType('log_probe')
    setFormState(createDefaultFormState())
    onOpenChange(false)
  }

  const handleSubmit = () => {
    const service = formState.service.trim()
    if (!service) {
      toast({title: 'Service is required', variant: 'destructive'})
      return
    }

    if (formState.whereType === 'method') {
      if (!formState.typeName.trim() || !formState.methodName.trim()) {
        toast({title: 'Class/Type name and method name are required', variant: 'destructive'})
        return
      }
    } else if (!formState.sourceFile.trim() || !formState.sourceLines.trim()) {
      toast({title: 'Source file and line number(s) are required', variant: 'destructive'})
      return
    }

    if (selectedType === 'metric_probe' && !formState.metricName.trim()) {
      toast({title: 'Metric name is required for metric probes', variant: 'destructive'})
      return
    }

    const payloadBase: CreateDebuggerProbeRequest = {
      probeType: selectedType,
      service,
      environment: formState.environment.trim() || '*',
      language: formState.language,
      active: formState.active,
      whereType: formState.whereType,
      typeName: formState.whereType === 'method' ? formState.typeName.trim() : undefined,
      methodName: formState.whereType === 'method' ? formState.methodName.trim() : undefined,
      sourceFile: formState.whereType === 'line' ? formState.sourceFile.trim() : undefined,
      sourceLines: formState.whereType === 'line' ? formState.sourceLines.trim() : undefined,
    }

    const payloadWithType: CreateDebuggerProbeRequest = {
      ...payloadBase,
      template: selectedType === 'log_probe' ? formState.template : undefined,
      captureConfig: selectedType === 'snapshot' ? formState.captureConfig : undefined,
      metricName: selectedType === 'metric_probe' ? formState.metricName.trim() : undefined,
      metricKind: selectedType === 'metric_probe' ? formState.metricKind : undefined,
      tags: selectedType === 'span_decoration' ? formState.tags : undefined,
    }

    if (probe) {
      updateMutation.mutate({probeId: probe.id, request: payloadWithType})
      return
    }

    createMutation.mutate(payloadWithType)
  }

  return (
    <Dialog open={open} onOpenChange={(nextOpen) => {
      if (!nextOpen) {
        handleClose()
        return
      }
      onOpenChange(nextOpen)
    }}>
      <DialogContent className="max-w-3xl max-h-[90vh] overflow-y-auto">
        <DialogHeader>
          <DialogTitle>{isEdit ? 'Edit Probe' : 'Create Probe'}</DialogTitle>
          <DialogDescription>
            {step === 1 ? 'Select probe type' : `Configure ${selectedTypeMeta.label.toLowerCase()}`}
          </DialogDescription>
        </DialogHeader>

        {step === 1 && !isEdit ? (
          <div className="grid grid-cols-1 md:grid-cols-2 gap-4 py-4">
            {probeTypeOptions.map((option) => (
              <Button
                key={option.value}
                type="button"
                variant="ghost"
                onClick={() => {
                  setSelectedType(option.value)
                  setStep(2)
                }}
                className="h-auto justify-start flex items-start rounded-xl border p-4 text-left transition-colors hover:bg-accent"
              >
                <div className={cn('mr-4 rounded-lg p-2', option.bgClassName)}>
                  <option.icon className={cn('h-6 w-6', option.iconClassName)} />
                </div>
                <div>
                  <div className="font-semibold mb-1">{option.label}</div>
                  <div className="text-sm text-muted-foreground">{option.description}</div>
                </div>
              </Button>
            ))}
          </div>
        ) : (
          <div className="space-y-5 py-4">
            <div className="grid gap-4 md:grid-cols-2">
              <div>
                <Label htmlFor="service">Service</Label>
                <Input
                  id="service"
                  value={formState.service}
                  onChange={(event) => setFormState({...formState, service: event.target.value})}
                  placeholder="checkout-service"
                />
              </div>
              <div>
                <Label htmlFor="environment">Environment</Label>
                <Input
                  id="environment"
                  value={formState.environment}
                  onChange={(event) => setFormState({...formState, environment: event.target.value})}
                  placeholder="*"
                />
              </div>
            </div>

            <div className="grid gap-4 md:grid-cols-2">
              <div>
                <Label htmlFor="language">Language</Label>
                <Select
                  value={formState.language}
                  onValueChange={(value) => setFormState({...formState, language: value})}
                >
                  <SelectTrigger id="language">
                    <SelectValue />
                  </SelectTrigger>
                  <SelectContent>
                    {languageOptions.map((language) => (
                      <SelectItem key={language.value} value={language.value}>
                        {language.label}
                      </SelectItem>
                    ))}
                  </SelectContent>
                </Select>
              </div>
              <div className="flex items-end justify-between rounded-lg border px-3 py-2">
                <div>
                  <Label htmlFor="active">Active</Label>
                  <p className="text-xs text-muted-foreground">Install this probe on agents</p>
                </div>
                <Switch
                  id="active"
                  checked={formState.active}
                  onCheckedChange={(checked) => setFormState({...formState, active: checked})}
                />
              </div>
            </div>

            <div className="space-y-2">
              <Label>Where</Label>
              <div className="grid grid-cols-2 gap-2">
                <Button
                  type="button"
                  variant={formState.whereType === 'method' ? 'default' : 'outline'}
                  onClick={() => setFormState({...formState, whereType: 'method'})}
                >
                  Method
                </Button>
                <Button
                  type="button"
                  variant={formState.whereType === 'line' ? 'default' : 'outline'}
                  onClick={() => setFormState({...formState, whereType: 'line'})}
                >
                  Source Line
                </Button>
              </div>
            </div>

            {formState.whereType === 'method' ? (
              <div className="grid gap-4 md:grid-cols-2">
                <div>
                  <Label htmlFor="typeName">Class / Type Name</Label>
                  <Input
                    id="typeName"
                    value={formState.typeName}
                    onChange={(event) => setFormState({...formState, typeName: event.target.value})}
                    placeholder="com.acme.checkout.PaymentService"
                  />
                </div>
                <div>
                  <Label htmlFor="methodName">Method Name</Label>
                  <Input
                    id="methodName"
                    value={formState.methodName}
                    onChange={(event) => setFormState({...formState, methodName: event.target.value})}
                    placeholder="chargeCustomer"
                  />
                </div>
              </div>
            ) : (
              <div className="grid gap-4 md:grid-cols-2">
                <div>
                  <Label htmlFor="sourceFile">Source File</Label>
                  <Input
                    id="sourceFile"
                    value={formState.sourceFile}
                    onChange={(event) => setFormState({...formState, sourceFile: event.target.value})}
                    placeholder="src/main/kotlin/com/acme/checkout/PaymentService.kt"
                  />
                </div>
                <div>
                  <Label htmlFor="sourceLines">Line Number(s)</Label>
                  <Input
                    id="sourceLines"
                    value={formState.sourceLines}
                    onChange={(event) => setFormState({...formState, sourceLines: event.target.value})}
                    placeholder="42 or 42-45"
                  />
                </div>
              </div>
            )}

            {selectedType === 'log_probe' && (
              <div>
                <Label htmlFor="template">Log Message Template</Label>
                <Textarea
                  id="template"
                  value={formState.template}
                  onChange={(event) => setFormState({...formState, template: event.target.value})}
                  placeholder="User {user.id} checkout failed: {error.message}"
                />
              </div>
            )}

            {selectedType === 'snapshot' && (
              <div>
                <Label htmlFor="captureConfig">Capture Config</Label>
                <Textarea
                  id="captureConfig"
                  value={formState.captureConfig}
                  onChange={(event) => setFormState({...formState, captureConfig: event.target.value})}
                  placeholder='{"maxReferenceDepth": 3, "maxCollectionSize": 100, "maxLength": 255}'
                />
              </div>
            )}

            {selectedType === 'metric_probe' && (
              <div className="grid gap-4 md:grid-cols-2">
                <div>
                  <Label htmlFor="metricName">Metric Name</Label>
                  <Input
                    id="metricName"
                    value={formState.metricName}
                    onChange={(event) => setFormState({...formState, metricName: event.target.value})}
                    placeholder="checkout.failed.count"
                  />
                </div>
                <div>
                  <Label htmlFor="metricKind">Metric Kind</Label>
                  <Select
                    value={formState.metricKind}
                    onValueChange={(value) =>
                      setFormState({...formState, metricKind: value as ProbeFormState['metricKind']})
                    }
                  >
                    <SelectTrigger id="metricKind">
                      <SelectValue />
                    </SelectTrigger>
                    <SelectContent>
                      <SelectItem value="count">Count</SelectItem>
                      <SelectItem value="gauge">Gauge</SelectItem>
                      <SelectItem value="histogram">Histogram</SelectItem>
                    </SelectContent>
                  </Select>
                </div>
              </div>
            )}

            {selectedType === 'span_decoration' && (
              <div>
                <Label htmlFor="tags">Tag Key-Value Pairs</Label>
                <Textarea
                  id="tags"
                  value={formState.tags}
                  onChange={(event) => setFormState({...formState, tags: event.target.value})}
                  placeholder='http.route:/checkout, customer_tier:{user.tier}'
                />
              </div>
            )}
          </div>
        )}

        <DialogFooter>
          {step === 2 && !isEdit ? (
            <Button type="button" variant="outline" onClick={() => setStep(1)}>
              Back
            </Button>
          ) : (
            <Button type="button" variant="outline" onClick={handleClose}>
              Cancel
            </Button>
          )}

          {step === 2 && (
            <Button onClick={handleSubmit} disabled={isPending}>
              {isPending ? (
                <>
                  <Loader2 className="mr-2 h-4 w-4 animate-spin" />
                  Saving...
                </>
              ) : isEdit ? (
                'Save Changes'
              ) : (
                'Create Probe'
              )}
            </Button>
          )}
        </DialogFooter>
      </DialogContent>
    </Dialog>
  )
}
