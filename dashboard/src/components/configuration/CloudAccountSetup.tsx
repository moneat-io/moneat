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
import {useMutation, useQuery, useQueryClient} from '@tanstack/react-query'
import {CloudCog, DollarSign, Gauge, Layers} from 'lucide-react'
import {cn} from '@/lib/utils'
import {api, formatErrorForLogging} from '@/lib/api'
import type {CloudSourceCreateRequest, CloudSourceProvider, CloudSourceProviderConfig} from '@/lib/api'
import {Badge} from '@/components/ui/badge'
import {Button} from '@/components/ui/button'
import {Checkbox} from '@/components/ui/checkbox'
import {CopyBlock} from '@/components/ui/copy-block'
import {Input} from '@/components/ui/input'
import {Label} from '@/components/ui/label'
import {SectionCard} from '@/components/ui/section-card'
import {useToast} from '@/hooks/useToast'

type CollectId = 'metrics' | 'inventory' | 'cost'
type SourceStatus = 'healthy' | 'syncing' | 'pending' | 'error' | string

interface FieldDef {
  readonly key: keyof CloudSourceProviderConfig
  readonly label: string
  readonly placeholder: string
  readonly required?: boolean
}

interface ProviderDef {
  readonly id: CloudSourceProvider
  readonly label: string
  readonly tag: string
  readonly accent: string
  readonly fields: readonly FieldDef[]
}

const CLOUD_SOURCE_QUERY_KEY = ['cloud-sources'] as const
const CATALOG_QUERY_KEY = ['monitoring', 'resource-catalog'] as const

const COLLECT_OPTIONS: {id: CollectId; label: string; hint: string; icon: typeof Gauge}[] = [
  {id: 'metrics', label: 'Metrics', hint: 'Provider metrics', icon: Gauge},
  {id: 'inventory', label: 'Inventory', hint: 'Resources and tags', icon: Layers},
  {id: 'cost', label: 'Cost', hint: 'Billing data', icon: DollarSign},
]

const PROVIDERS: readonly ProviderDef[] = [
  {
    id: 'aws',
    label: 'Amazon Web Services',
    tag: 'AWS',
    accent: 'text-warning-fg',
    fields: [
      {key: 'accountId', label: 'Account ID', placeholder: '123456789012', required: true},
      {key: 'roleName', label: 'IAM role name', placeholder: 'MoneatIntegrationRole', required: true},
    ],
  },
  {
    id: 'gcp',
    label: 'Google Cloud',
    tag: 'GCP',
    accent: 'text-info-fg',
    fields: [
      {key: 'projectId', label: 'Project ID', placeholder: 'my-project-1234', required: true},
      {key: 'billingExportTable', label: 'Billing export table', placeholder: 'billing.dataset.export'},
    ],
  },
  {
    id: 'azure',
    label: 'Microsoft Azure',
    tag: 'AZ',
    accent: 'text-info-fg',
    fields: [
      {key: 'tenantId', label: 'Tenant ID', placeholder: '00000000-0000-0000-0000-000000000000', required: true},
      {
        key: 'subscriptionId',
        label: 'Subscription ID',
        placeholder: '00000000-0000-0000-0000-000000000000',
        required: true,
      },
    ],
  },
]

function providerDisplay(provider: CloudSourceProvider): ProviderDef {
  return PROVIDERS.find((option) => option.id === provider) ?? PROVIDERS[0]
}

function statusBadgeVariant(status: SourceStatus): 'success' | 'warning' | 'danger' | 'neutral' {
  if (status === 'healthy') return 'success'
  if (status === 'syncing' || status === 'pending') return 'warning'
  if (status === 'error') return 'danger'
  return 'neutral'
}

function snippetWithValues(snippet: string, values: CloudSourceProviderConfig): string {
  return snippet
    .replaceAll('PROJECT_ID', values.projectId?.trim() || 'PROJECT_ID')
    .replaceAll('SUBSCRIPTION_ID', values.subscriptionId?.trim() || 'SUBSCRIPTION_ID')
}

function fieldValue(values: CloudSourceProviderConfig, key: keyof CloudSourceProviderConfig): string {
  return values[key]?.toString() ?? ''
}

function setFieldValue(
  values: CloudSourceProviderConfig,
  key: keyof CloudSourceProviderConfig,
  value: string
): CloudSourceProviderConfig {
  return {...values, [key]: value}
}

function sourceDisplayName(provider: CloudSourceProvider, values: CloudSourceProviderConfig): string {
  const providerDef = providerDisplay(provider)
  const identifier = values.accountId || values.projectId || values.subscriptionId
  return `${providerDef.tag} ${identifier ?? 'cloud account'}`
}

function buildCreateRequest(
  provider: CloudSourceProvider,
  values: CloudSourceProviderConfig,
  collect: Record<CollectId, boolean>
): CloudSourceCreateRequest {
  return {
    provider,
    displayName: sourceDisplayName(provider, values),
    config: values,
    collectMetrics: collect.metrics,
    collectInventory: collect.inventory,
    collectCost: collect.cost,
    collectLogs: false,
  }
}

function requiredFieldsFilled(providerDef: ProviderDef, values: CloudSourceProviderConfig): boolean {
  return providerDef.fields
    .filter((field) => field.required)
    .every((field) => fieldValue(values, field.key).trim() !== '')
}

function ProviderPicker({
  provider,
  onSelect,
}: {
  readonly provider: CloudSourceProvider
  readonly onSelect: (next: CloudSourceProvider) => void
}) {
  return (
    <div className="grid grid-cols-3 gap-2">
      {PROVIDERS.map((option) => {
        const selected = option.id === provider
        return (
          <button
            key={option.id}
            type="button"
            onClick={() => onSelect(option.id)}
            aria-pressed={selected}
            className={cn(
              'flex flex-col items-center gap-1 rounded-md border px-2 py-2.5 transition-colors',
              selected ? 'border-primary bg-primary/5' : 'border-border hover:bg-muted/50'
            )}
          >
            <span className={cn('font-mono text-xs font-semibold', option.accent)}>{option.tag}</span>
            <span className="text-[11px] leading-tight text-muted-foreground">{option.label.split(' ')[0]}</span>
          </button>
        )
      })}
    </div>
  )
}

export function CloudAccountSetup() {
  const [provider, setProvider] = useState<CloudSourceProvider>('aws')
  const [values, setValues] = useState<CloudSourceProviderConfig>({})
  const [collect, setCollect] = useState<Record<CollectId, boolean>>({
    metrics: true,
    inventory: true,
    cost: false,
  })
  const providerDef = providerDisplay(provider)
  const queryClient = useQueryClient()
  const {toast} = useToast()

  const previewQuery = useQuery({
    queryKey: ['cloud-source-preview', provider],
    queryFn: () => api.getCloudSourceSetupPreview(provider),
  })
  const sourcesQuery = useQuery({
    queryKey: CLOUD_SOURCE_QUERY_KEY,
    queryFn: api.getCloudSources,
  })

  const currentSource = useMemo(
    () => sourcesQuery.data?.find((source) => source.provider === provider),
    [provider, sourcesQuery.data]
  )

  const connectMutation = useMutation({
    mutationFn: () => {
      if (currentSource) return api.syncCloudSource(currentSource.id)
      return api.createCloudSource(buildCreateRequest(provider, values, collect))
    },
    onSuccess: (source) => {
      void queryClient.invalidateQueries({queryKey: CLOUD_SOURCE_QUERY_KEY})
      void queryClient.invalidateQueries({queryKey: CATALOG_QUERY_KEY})
      toast({title: source.status === 'healthy' ? 'Cloud source connected' : 'Cloud source saved'})
    },
    onError: (error) => {
      toast({
        title: 'Cloud connect failed',
        description: formatErrorForLogging(error),
        variant: 'destructive',
      })
    },
  })

  const preview = previewQuery.data
  const sourceStatus = connectMutation.data?.status ?? currentSource?.status
  const snippet = preview ? snippetWithValues(preview.snippet, values) : 'Loading setup...'
  const canConnect = currentSource !== undefined || requiredFieldsFilled(providerDef, values)

  const selectProvider = (next: CloudSourceProvider) => {
    setProvider(next)
    setValues({})
  }

  return (
    <div className="grid gap-4 lg:grid-cols-[340px_minmax(0,1fr)]">
      <SectionCard title="Cloud account" icon={CloudCog} bodyClassName="flex flex-col gap-4">
        <div className="flex flex-col gap-2">
          <p className="text-[11px] font-medium uppercase tracking-wide text-muted-foreground">Provider</p>
          <ProviderPicker provider={provider} onSelect={selectProvider} />
        </div>

        <div className="flex flex-col gap-2.5 border-t pt-3">
          {providerDef.fields.map((field) => (
            <div key={field.key} className="flex flex-col gap-1">
              <Label htmlFor={`cloud-${field.key}`} className="text-[11px] text-muted-foreground">
                {field.label}
              </Label>
              <Input
                id={`cloud-${field.key}`}
                value={fieldValue(values, field.key)}
                onChange={(event) => setValues((current) => setFieldValue(current, field.key, event.target.value))}
                placeholder={field.placeholder}
                className="h-8 font-mono text-xs"
              />
            </div>
          ))}
          <div className="flex items-center justify-between rounded-md bg-muted/40 px-2 py-1.5">
            <span className="text-[11px] text-muted-foreground">External ID</span>
            <code className="font-mono text-[11px] text-foreground">{preview?.externalId ?? '...'}</code>
          </div>
          <div className="flex items-center justify-between rounded-md bg-muted/40 px-2 py-1.5">
            <span className="text-[11px] text-muted-foreground">Moneat principal</span>
            <code className="max-w-[180px] truncate font-mono text-[11px] text-foreground">
              {preview?.principal ?? '...'}
            </code>
          </div>
        </div>

        <div className="flex flex-col gap-1.5 border-t pt-3">
          <p className="text-[11px] font-medium uppercase tracking-wide text-muted-foreground">Collect</p>
          <div className="grid grid-cols-2 gap-0.5">
            {COLLECT_OPTIONS.map((option) => {
              const Icon = option.icon
              return (
                <label
                  key={option.id}
                  className="flex cursor-pointer items-start gap-2 rounded-md p-1.5 transition-colors hover:bg-muted/40"
                >
                  <Checkbox
                    checked={collect[option.id]}
                    onCheckedChange={(value) =>
                      setCollect((current) => ({...current, [option.id]: value === true}))
                    }
                    className="mt-0.5"
                    aria-label={option.label}
                  />
                  <span className="min-w-0">
                    <span className="flex items-center gap-1.5 text-xs font-medium leading-tight">
                      <Icon className="h-3 w-3 text-muted-foreground" />
                      {option.label}
                    </span>
                    <span className="mt-0.5 block text-[11px] leading-snug text-muted-foreground">{option.hint}</span>
                  </span>
                </label>
              )
            })}
          </div>
        </div>
      </SectionCard>

      <SectionCard
        title={preview?.snippetLabel ?? 'Setup'}
        icon={CloudCog}
        bodyClassName="flex flex-col gap-3"
        actions={
          <div className="flex items-center gap-2">
            {sourceStatus ? (
              <Badge variant={statusBadgeVariant(sourceStatus)} size="sm" className="capitalize">
                {sourceStatus}
              </Badge>
            ) : null}
            <Button
              type="button"
              size="sm"
              className="h-7 gap-1.5 text-xs"
              disabled={!canConnect || connectMutation.isPending || previewQuery.isLoading}
              onClick={() => connectMutation.mutate()}
            >
              {currentSource ? 'Sync' : 'Connect'}
            </Button>
          </div>
        }
      >
        <p className="text-xs text-muted-foreground">Apply setup, then connect.</p>
        <CopyBlock code={snippet} language={preview?.snippetLanguage ?? 'text'} />
        {currentSource?.lastError ? (
          <p className="rounded-md border border-danger-border/60 bg-danger-bg/40 px-3 py-2 text-[11px] text-danger-fg">
            {currentSource.lastError}
          </p>
        ) : null}
      </SectionCard>
    </div>
  )
}
