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
import * as DialogPrimitive from '@radix-ui/react-dialog'
import {useMutation, useQueryClient} from '@tanstack/react-query'
import {Lock, X} from 'lucide-react'
import {api, type CustomDataSourceResponse, type TestConnectionResult} from '@/lib/api'
import {cn} from '@/lib/utils'
import {Button} from '@/components/ui/button'
import {getVendor} from './dataSourceCatalog'
import {
  type DsFormState,
  buildPayload, buildTestRequest, defaultFormState, hydrateFormState, isFormReady,
} from './dataSourceConnection'
import {DataSourcePickerStep} from './DataSourcePickerStep'
import {DataSourceConfigureStep} from './DataSourceConfigureStep'

interface DataSourceConnectDialogProps {
  readonly mode: 'create' | 'edit'
  readonly initial?: CustomDataSourceResponse
  readonly onClose: () => void
  readonly onSaved?: () => void
}

/**
 * The two-step "Add data source" connection dialog. Mount it only while open
 * (the parent controls that), so its form state initializes cleanly per-open
 * without any setState-in-effect.
 */
export function DataSourceConnectDialog({mode, initial, onClose, onSaved}: DataSourceConnectDialogProps) {
  const editing = mode === 'edit'
  const queryClient = useQueryClient()
  const [step, setStep] = useState<1 | 2>(editing ? 2 : 1)
  const [form, setForm] = useState<DsFormState | null>(
    () => (editing && initial ? hydrateFormState(initial) : null)
  )
  const [testResult, setTestResult] = useState<TestConnectionResult | null>(null)

  const vendor = getVendor(form?.vendor)

  const saveMutation = useMutation({
    mutationFn: () => {
      const payload = buildPayload(form!)
      return editing && initial
        ? api.updateCustomDataSource(initial.id, payload)
        : api.createCustomDataSource(payload)
    },
    onSuccess: () => {
      queryClient.invalidateQueries({queryKey: ['custom-datasources']})
      queryClient.invalidateQueries({queryKey: ['datasources']})
      onSaved?.()
      onClose()
    },
  })

  const testMutation = useMutation({
    mutationFn: () => api.testDataSourceConnection(buildTestRequest(form!)),
    onSuccess: (result) => setTestResult(result),
    onError: () => setTestResult({success: false, message: 'Connection test failed'}),
  })

  function pickVendor(key: string) {
    setForm(defaultFormState(key))
    setTestResult(null)
    setStep(2)
  }

  function update(patch: Partial<DsFormState>) {
    setForm((f) => (f ? {...f, ...patch} : f))
    setTestResult(null)
  }

  function back() {
    setStep(1)
  }

  const ready = form != null && isFormReady(form)

  return (
    <DialogPrimitive.Root open onOpenChange={(o) => { if (!o) onClose() }}>
      <DialogPrimitive.Portal>
        <DialogPrimitive.Overlay className="fixed inset-0 z-50 bg-black/70 backdrop-blur-[2px] data-[state=open]:animate-in data-[state=open]:fade-in-0" />
        <DialogPrimitive.Content
          className="fixed left-1/2 top-1/2 z-50 flex max-h-[calc(100vh-56px)] w-[min(680px,calc(100vw-2rem))] -translate-x-1/2 -translate-y-1/2 flex-col overflow-hidden rounded-xl border border-border bg-card shadow-2xl data-[state=open]:animate-in data-[state=open]:fade-in-0 data-[state=open]:zoom-in-95"
          onOpenAutoFocus={(e) => { if (step === 2) e.preventDefault() }}
        >
          <DialogPrimitive.Title className="sr-only">
            {editing ? 'Edit data source' : 'Add data source'}
          </DialogPrimitive.Title>
          <DialogPrimitive.Description className="sr-only">
            Configure a connection to an external database or metrics backend.
          </DialogPrimitive.Description>

          {/* header */}
          <div className="flex shrink-0 items-center gap-3 border-b border-border px-4 py-3">
            <h2 className="text-[0.9375rem] font-semibold tracking-tight text-foreground">
              {editing ? 'Edit data source' : 'Add data source'}
            </h2>
            {!editing && (
              <div className="ml-2 flex items-center gap-1.5 text-[10px] text-muted-foreground/80">
                <StepPill n={1} label="Choose" on={step === 1} />
                <span className="h-px w-3.5 bg-border" />
                <StepPill n={2} label="Configure" on={step === 2} />
              </div>
            )}
            <DialogPrimitive.Close
              className="ml-auto grid h-7 w-7 place-items-center rounded-md text-muted-foreground hover:bg-accent/40 hover:text-foreground"
              aria-label="Close"
            >
              <X className="h-4 w-4" />
            </DialogPrimitive.Close>
          </div>

          {/* body */}
          <div className="flex-1 overflow-y-auto p-4">
            {step === 1 || !form || !vendor ? (
              <DataSourcePickerStep onPick={pickVendor} />
            ) : (
              <DataSourceConfigureStep
                state={form}
                vendor={vendor}
                editing={editing}
                update={update}
                onBack={back}
                testResult={testResult}
                testing={testMutation.isPending}
                onTest={() => testMutation.mutate()}
              />
            )}
          </div>

          {/* footer (configure step only) */}
          {step === 2 && form && vendor && (
            <div className="flex shrink-0 items-center gap-3 border-t border-border px-4 py-3">
              <span className="flex items-center gap-1.5 text-xs text-muted-foreground">
                <Lock className="h-3.5 w-3.5 text-success-solid" /> Credentials encrypted at rest · AES-256-GCM
              </span>
              <div className="ml-auto flex items-center gap-2">
                <Button variant="ghost" size="sm" onClick={onClose}>Cancel</Button>
                <Button size="sm" disabled={!ready || saveMutation.isPending} onClick={() => saveMutation.mutate()}>
                  {saveMutation.isPending
                    ? 'Saving…'
                    : editing ? 'Save changes' : 'Add data source'}
                </Button>
              </div>
            </div>
          )}
        </DialogPrimitive.Content>
      </DialogPrimitive.Portal>
    </DialogPrimitive.Root>
  )
}

function StepPill({n, label, on}: {n: number; label: string; on: boolean}) {
  return (
    <span className={cn('flex items-center gap-1.5', on ? 'text-foreground' : 'text-muted-foreground/70')}>
      <span className={cn(
        'grid h-4 w-4 place-items-center rounded-full text-[10px] font-bold',
        on ? 'bg-primary text-primary-foreground' : 'border border-border bg-muted text-muted-foreground'
      )}>
        {n}
      </span>
      {label}
    </span>
  )
}
