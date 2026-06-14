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

import {useMemo, useState, type ReactNode} from 'react'
import {createFileRoute, redirect} from '@tanstack/react-router'
import {useMutation, useQuery, useQueryClient} from '@tanstack/react-query'
import {api} from '@/lib/api'
import type {
  CreatedFeatureFlagSdkKey,
  FeatureFlag,
  FeatureFlagConfig,
  FeatureFlagJsonValue,
  FeatureFlagSdkKeyType,
  FeatureFlagSegment,
  FeatureFlagValueType,
  FeatureFlagVariantRequest,
} from '@/lib/api'
import {Badge} from '@/components/ui/badge'
import {Button} from '@/components/ui/button'
import {Card, CardContent, CardHeader, CardTitle} from '@/components/ui/card'
import {CodeEditor} from '@/components/ui/code-editor'
import {CopyBlock} from '@/components/ui/copy-block'
import {Input} from '@/components/ui/input'
import {Label} from '@/components/ui/label'
import {Select, SelectContent, SelectItem, SelectTrigger, SelectValue} from '@/components/ui/select'
import {Switch} from '@/components/ui/switch'
import {Table, TableBody, TableCell, TableHead, TableHeader, TableRow} from '@/components/ui/table'
import {Tabs, TabsContent, TabsList, TabsTrigger} from '@/components/ui/tabs'
import {Textarea} from '@/components/ui/textarea'
import {Tooltip, TooltipContent, TooltipProvider, TooltipTrigger} from '@/components/ui/tooltip'
import {PageHeader} from '@/components/ui/page-header'
import {StatCard} from '@/components/ui/stat-card'
import {EmptyState} from '@/components/ui/empty-state'
import {useToast} from '@/hooks/useToast'
import {cn} from '@/lib/utils'
import {
  BarChart3,
  BookOpen,
  Check,
  ChevronDown,
  Flag,
  Info,
  KeyRound,
  Plus,
  RefreshCcw,
  Save,
  Search,
  Shield,
  Trash2,
  X,
} from 'lucide-react'

export const Route = createFileRoute('/feature-flags')({
  beforeLoad: async ({location}) => {
    if (!api.isAuthenticated()) {
      const hasSession = await api.checkAuth()
      if (!hasSession) throw redirect({to: '/login', search: {redirect: location.href}})
    }
  },
  component: FeatureFlagsPage,
})

const valueTypes: FeatureFlagValueType[] = ['BOOLEAN', 'STRING', 'INTEGER', 'DOUBLE', 'OBJECT']
const defaultVariants = JSON.stringify([
  {key: 'off', name: 'Off', value: false},
  {key: 'on', name: 'On', value: true},
], null, 2)
const defaultSegmentConditions = JSON.stringify({all: []}, null, 2)
const tabTriggerClass = [
  'border border-transparent',
  'data-[state=active]:border-foreground data-[state=active]:bg-background',
  'data-[state=active]:ring-1 data-[state=active]:ring-foreground/20',
].join(' ')
const tooltipText = {
  environment: 'Choose which environment you are editing and evaluating flags for.',
  refresh: 'Reload flags, analytics, SDK keys, segments, and audit data for this environment.',
  flags: 'Total active feature flags returned for the selected environment.',
  evaluations: 'Feature flag evaluations recorded over the analytics window.',
  uniqueKeys: 'Distinct targeting keys, usually users or accounts, seen in flag evaluations.',
  sdkKeys: 'SDK keys that can evaluate flags through the OpenFeature OFREP endpoint.',
  flagKey: 'Stable identifier used by SDKs. Use a dotted name such as checkout.enabled.',
  flagName: 'Human-readable name shown in the dashboard.',
  valueType: 'The JSON value type returned by every variant for this flag.',
  clientVisible: 'Allows client SDK keys to evaluate this flag. Leave off for server-only flags.',
  variantsJson: 'Editable variant definitions. Keys are referenced by targeting and fallback rules.',
  tags: 'Comma-separated labels for grouping and searching flags.',
  createFlag: 'Create the flag with the variants, tags, and visibility settings shown here.',
  environments: 'Named deployment scopes. Each environment has independent targeting configuration.',
  environmentKey: 'Stable environment identifier used by SDK keys and evaluation requests.',
  environmentName: 'Display name for the environment selector.',
  addEnvironment: 'Create a new environment and switch the page to it.',
  saveConfig: 'Save targeting, enabled state, default variant, and off variant for this environment.',
  archive: 'Archive this flag so SDKs can no longer evaluate it.',
  enabled: 'Turns this flag on for the selected environment. Off returns the off variant.',
  defaultVariant: 'Fallback variant returned when no targeting rule matches.',
  offVariant: 'Variant returned while the flag is disabled.',
  rulesJson: 'JSON targeting rules. Rules can reference segments and rollout percentages.',
  analyticsSummary: 'Recent evaluation counts by variant for this flag.',
  segmentList: 'Saved audience groups that can be reused by targeting rules.',
  segmentKey: 'Stable segment identifier used from targeting rules.',
  segmentName: 'Human-readable segment name shown in this dashboard.',
  segmentDescription: 'Optional internal note describing who belongs in this segment.',
  segmentConditions: 'JSON conditions evaluated against the flag evaluation context.',
  saveSegment: 'Create or update this reusable segment.',
  deleteSegment: 'Archive this segment so new targeting rules cannot use it.',
  sdkKeyName: 'Internal label for this key, such as Production server key.',
  sdkKeyType: 'Server keys can evaluate all flags. Client keys can only evaluate client-visible flags.',
  createKey: 'Create a new SDK key. The full secret is only shown once.',
  revokeKey: 'Revoke this SDK key so it can no longer evaluate flags.',
} as const

type TooltipSide = 'top' | 'right' | 'bottom' | 'left'

interface ConfigDraft {
  enabled: boolean
  defaultVariantKey: string
  offVariantKey: string
  rules: string
}

interface EnvironmentDraft {
  key: string
  name: string
}

interface SegmentDraft {
  key: string
  name: string
  description: string
  conditions: string
}

function parseJson<T>(raw: string, field: string): T {
  try {
    return JSON.parse(raw) as T
  } catch {
    throw new Error(`Invalid ${field} JSON`)
  }
}

function stringifyJson(value: unknown): string {
  return JSON.stringify(value ?? {}, null, 2)
}

function formatValue(value: FeatureFlagJsonValue): string {
  if (typeof value === 'object') return JSON.stringify(value)
  return String(value)
}

function selectedConfig(flag: FeatureFlag | null, environment: string): FeatureFlagConfig | null {
  return flag?.configs.find((config) => config.environmentKey === environment) ?? flag?.configs[0] ?? null
}

function flagMatchesSearch(flag: FeatureFlag, config: FeatureFlagConfig | null, search: string): boolean {
  if (!search) return true
  const status = config?.enabled ? 'on enabled active' : 'off disabled inactive'
  const visibility = flag.clientVisible ? 'client client-visible' : 'server server-only'
  const searchable = [
    flag.name,
    flag.key,
    flag.valueType,
    status,
    visibility,
    ...flag.tags,
  ].join(' ').toLowerCase()
  return searchable.includes(search)
}

function variantsDraftFromFlag(flag: FeatureFlag | null): string {
  const variants = flag?.variants.map((variant) => ({
    key: variant.key,
    name: variant.name,
    value: variant.value,
  })) ?? []
  return JSON.stringify(variants, null, 2)
}

function draftFromConfig(config: FeatureFlagConfig | null): ConfigDraft {
  return {
    enabled: config?.enabled ?? false,
    defaultVariantKey: config?.defaultVariantKey ?? '',
    offVariantKey: config?.offVariantKey ?? '',
    rules: stringifyJson(config?.rules ?? {rules: []}),
  }
}

function segmentDraftFromSegment(segment: FeatureFlagSegment): SegmentDraft {
  return {
    key: segment.key,
    name: segment.name,
    description: segment.description ?? '',
    conditions: stringifyJson(segment.conditions),
  }
}

function TooltipWrap({
  content,
  children,
  side = 'top',
}: {
  content: string
  children: ReactNode
  side?: TooltipSide
}) {
  return (
    <Tooltip>
      <TooltipTrigger asChild>{children}</TooltipTrigger>
      <TooltipContent side={side} className="max-w-72 leading-snug">
        {content}
      </TooltipContent>
    </Tooltip>
  )
}

function HelpIcon({content}: {content: string}) {
  return (
    <TooltipWrap content={content}>
      <button
        type="button"
        aria-label={content}
        className={cn(
          'inline-flex h-4 w-4 items-center justify-center rounded-full text-muted-foreground',
          'transition-colors hover:text-foreground focus-visible:outline-none focus-visible:ring-2',
          'focus-visible:ring-ring'
        )}
      >
        <Info className="h-3.5 w-3.5" />
      </button>
    </TooltipWrap>
  )
}

function FieldLabel({
  children,
  htmlFor,
  tooltip,
}: {
  children: ReactNode
  htmlFor?: string
  tooltip?: string
}) {
  return (
    <div className="flex items-center gap-1.5">
      <Label htmlFor={htmlFor}>{children}</Label>
      {tooltip && <HelpIcon content={tooltip} />}
    </div>
  )
}

function FeatureFlagsPage() {
  const {toast} = useToast()
  const queryClient = useQueryClient()
  const [environment, setEnvironment] = useState('production')
  const [selectedKey, setSelectedKey] = useState<string | null>(null)
  const [flagSearch, setFlagSearch] = useState('')
  const [newFlag, setNewFlag] = useState({
    key: '',
    name: '',
    valueType: 'BOOLEAN' as FeatureFlagValueType,
    clientVisible: false,
    tags: '',
    variants: defaultVariants,
  })
  const [configDrafts, setConfigDrafts] = useState<Record<string, ConfigDraft>>({})
  const [variantDrafts, setVariantDrafts] = useState<Record<string, string>>({})
  const [environmentDraft, setEnvironmentDraft] = useState<EnvironmentDraft>({key: '', name: ''})
  const [segmentDraft, setSegmentDraft] = useState<SegmentDraft>({
    key: '',
    name: '',
    description: '',
    conditions: defaultSegmentConditions,
  })
  const [sdkKeyDraft, setSdkKeyDraft] = useState({
    name: '',
    keyType: 'server' as FeatureFlagSdkKeyType,
  })
  const [createdKey, setCreatedKey] = useState<CreatedFeatureFlagSdkKey | null>(null)

  const flagsQuery = useQuery({
    queryKey: ['feature-flags', environment],
    queryFn: () => api.listFeatureFlags(environment),
  })
  const sdkKeysQuery = useQuery({
    queryKey: ['feature-flag-sdk-keys'],
    queryFn: () => api.listFeatureFlagSdkKeys(),
  })
  const analyticsQuery = useQuery({
    queryKey: ['feature-flag-analytics', environment],
    queryFn: () => api.getFeatureFlagAnalytics(environment),
  })
  const auditQuery = useQuery({
    queryKey: ['feature-flag-audit'],
    queryFn: () => api.listFeatureFlagAuditEvents(50),
  })
  const segmentsQuery = useQuery({
    queryKey: ['feature-flag-segments'],
    queryFn: () => api.listFeatureFlagSegments(),
  })

  const environments = flagsQuery.data?.environments ?? []
  const flags = useMemo(() => flagsQuery.data?.flags ?? [], [flagsQuery.data?.flags])
  const normalizedFlagSearch = flagSearch.trim().toLowerCase()
  const filteredFlags = useMemo(() => {
    return flags.filter((flag) => flagMatchesSearch(flag, selectedConfig(flag, environment), normalizedFlagSearch))
  }, [environment, flags, normalizedFlagSearch])
  const selectedFlag = useMemo(() => {
    const selected = flags.find((flag) => flag.key === selectedKey) ?? null
    if (!normalizedFlagSearch) return selected ?? flags[0] ?? null
    const selectedIsVisible = selected && filteredFlags.some((flag) => flag.key === selected.key)
    return selectedIsVisible ? selected : filteredFlags[0] ?? null
  }, [filteredFlags, flags, normalizedFlagSearch, selectedKey])
  const config = selectedConfig(selectedFlag, environment)
  const analytics = analyticsQuery.data
  const configDraftKey = `${selectedFlag?.key ?? 'none'}:${environment}:${config?.version ?? 0}`
  const configDraft = configDrafts[configDraftKey] ?? draftFromConfig(config)
  const variantDraftKey = `${selectedFlag?.key ?? 'none'}:${selectedFlag?.updatedAt ?? 'new'}`
  const variantDraft = variantDrafts[variantDraftKey] ?? variantsDraftFromFlag(selectedFlag)
  const updateConfigDraft = (value: ConfigDraft) => {
    setConfigDrafts((previous) => ({...previous, [configDraftKey]: value}))
  }
  const updateVariantDraft = (value: string) => {
    setVariantDrafts((previous) => ({...previous, [variantDraftKey]: value}))
  }

  const createFlagMutation = useMutation({
    mutationFn: () => {
      const variants = parseJson<FeatureFlagVariantRequest[]>(newFlag.variants, 'variants')
      return api.createFeatureFlag({
        key: newFlag.key.trim(),
        name: newFlag.name.trim(),
        valueType: newFlag.valueType,
        clientVisible: newFlag.clientVisible,
        tags: newFlag.tags.split(',').map((tag) => tag.trim()).filter(Boolean),
        variants,
        defaultVariantKey: variants[0]?.key ?? null,
        offVariantKey: variants[0]?.key ?? null,
      })
    },
    onSuccess: (flag) => {
      toast({title: 'Feature flag created'})
      setSelectedKey(flag.key)
      setNewFlag({...newFlag, key: '', name: ''})
      queryClient.invalidateQueries({queryKey: ['feature-flags']})
    },
    onError: (error: Error) => {
      toast({title: 'Failed to create flag', description: error.message, variant: 'destructive'})
    },
  })

  const updateFlagVariantsMutation = useMutation({
    mutationFn: () => {
      if (!selectedFlag) throw new Error('No flag selected')
      return api.updateFeatureFlag(selectedFlag.key, {
        variants: parseJson<FeatureFlagVariantRequest[]>(variantDraft, 'variants'),
      })
    },
    onSuccess: () => {
      toast({title: 'Variants saved'})
      queryClient.invalidateQueries({queryKey: ['feature-flags']})
      queryClient.invalidateQueries({queryKey: ['feature-flag-audit']})
    },
    onError: (error: Error) => {
      toast({title: 'Failed to save variants', description: error.message, variant: 'destructive'})
    },
  })

  const updateConfigMutation = useMutation({
    mutationFn: () => {
      if (!selectedFlag) throw new Error('No flag selected')
      return api.updateFeatureFlagConfig(selectedFlag.key, environment, {
        enabled: configDraft.enabled,
        defaultVariantKey: configDraft.defaultVariantKey || null,
        offVariantKey: configDraft.offVariantKey || null,
        rules: parseJson<unknown>(configDraft.rules, 'rules'),
      })
    },
    onSuccess: () => {
      toast({title: 'Targeting saved'})
      queryClient.invalidateQueries({queryKey: ['feature-flags']})
      queryClient.invalidateQueries({queryKey: ['feature-flag-audit']})
    },
    onError: (error: Error) => {
      toast({title: 'Failed to save targeting', description: error.message, variant: 'destructive'})
    },
  })

  const deleteFlagMutation = useMutation({
    mutationFn: (key: string) => api.deleteFeatureFlag(key),
    onSuccess: () => {
      toast({title: 'Feature flag archived'})
      setSelectedKey(null)
      queryClient.invalidateQueries({queryKey: ['feature-flags']})
    },
    onError: (error: Error) => {
      toast({title: 'Failed to archive flag', description: error.message, variant: 'destructive'})
    },
  })

  const createEnvironmentMutation = useMutation({
    mutationFn: () => api.createFeatureFlagEnvironment({
      key: environmentDraft.key.trim(),
      name: environmentDraft.name.trim(),
    }),
    onSuccess: (created) => {
      toast({title: 'Environment created'})
      setEnvironment(created.key)
      setEnvironmentDraft({key: '', name: ''})
      queryClient.invalidateQueries({queryKey: ['feature-flags']})
    },
    onError: (error: Error) => {
      toast({title: 'Failed to create environment', description: error.message, variant: 'destructive'})
    },
  })

  const upsertSegmentMutation = useMutation({
    mutationFn: () => api.upsertFeatureFlagSegment({
      key: segmentDraft.key.trim(),
      name: segmentDraft.name.trim(),
      description: segmentDraft.description.trim() || null,
      conditions: parseJson<unknown>(segmentDraft.conditions, 'segment conditions'),
    }),
    onSuccess: () => {
      toast({title: 'Segment saved'})
      setSegmentDraft({key: '', name: '', description: '', conditions: defaultSegmentConditions})
      queryClient.invalidateQueries({queryKey: ['feature-flag-segments']})
      queryClient.invalidateQueries({queryKey: ['feature-flags']})
    },
    onError: (error: Error) => {
      toast({title: 'Failed to save segment', description: error.message, variant: 'destructive'})
    },
  })

  const deleteSegmentMutation = useMutation({
    mutationFn: (key: string) => api.deleteFeatureFlagSegment(key),
    onSuccess: () => {
      toast({title: 'Segment archived'})
      queryClient.invalidateQueries({queryKey: ['feature-flag-segments']})
      queryClient.invalidateQueries({queryKey: ['feature-flags']})
    },
    onError: (error: Error) => {
      toast({title: 'Failed to archive segment', description: error.message, variant: 'destructive'})
    },
  })

  const createSdkKeyMutation = useMutation({
    mutationFn: () => api.createFeatureFlagSdkKey({
      environmentKey: environment,
      name: sdkKeyDraft.name.trim(),
      keyType: sdkKeyDraft.keyType,
    }),
    onSuccess: (key) => {
      setCreatedKey(key)
      setSdkKeyDraft({...sdkKeyDraft, name: ''})
      queryClient.invalidateQueries({queryKey: ['feature-flag-sdk-keys']})
    },
    onError: (error: Error) => {
      toast({title: 'Failed to create SDK key', description: error.message, variant: 'destructive'})
    },
  })

  const revokeSdkKeyMutation = useMutation({
    mutationFn: (id: string) => api.revokeFeatureFlagSdkKey(id),
    onSuccess: () => queryClient.invalidateQueries({queryKey: ['feature-flag-sdk-keys']}),
  })

  const kotlinExample = `val provider = OfrepProvider.builder()
  .endpoint("https://your-moneat-host/ofrep/v1")
  .apiKey("${createdKey?.key ?? 'mffsk_...'}")
  .build()

OpenFeatureAPI.getInstance().setProvider(provider)
val client = OpenFeatureAPI.getInstance().getClient()
val enabled = client.getBooleanValue("checkout.enabled", false, evaluationContext)`

  return (
    <TooltipProvider delayDuration={200}>
      <div className="flex min-h-full flex-col gap-3 p-3 md:p-4">
        <PageHeader
          icon={Flag}
          title="Feature Flags"
          description="Manage flags, targeting, segments, and SDK keys per environment"
          actions={
            <>
              <Button asChild variant="outline" size="sm" className="h-9 gap-2">
                <a href="/docs/feature-flags">
                  <BookOpen className="h-4 w-4" />
                  Docs
                </a>
              </Button>
              <Select value={environment} onValueChange={setEnvironment}>
                <SelectTrigger className="h-9 w-44">
                  <SelectValue />
                </SelectTrigger>
                <SelectContent>
                  {environments.map((env) => (
                    <SelectItem key={env.key} value={env.key}>{env.name}</SelectItem>
                  ))}
                </SelectContent>
              </Select>
              <Button
                variant="outline"
                size="icon"
                aria-label="Refresh feature flag data"
                className="h-9 w-9"
                onClick={() => {
                  queryClient.invalidateQueries({queryKey: ['feature-flags']})
                  queryClient.invalidateQueries({queryKey: ['feature-flag-analytics']})
                }}
              >
                <RefreshCcw className="h-4 w-4" />
              </Button>
            </>
          }
        />

        <div className="grid grid-cols-2 gap-3 sm:grid-cols-4">
          <StatCard
            icon={Flag}
            tone="accent"
            label="Flags"
            value={flags.length.toLocaleString()}
          />
          <StatCard
            icon={Check}
            tone="info"
            label="Evaluations"
            value={(analytics?.evaluations ?? 0).toLocaleString()}
          />
          <StatCard
            icon={Shield}
            tone="accent"
            label="Unique Keys"
            value={(analytics?.uniqueTargetingKeys ?? 0).toLocaleString()}
          />
          <StatCard
            icon={KeyRound}
            tone="accent"
            label="SDK Keys"
            value={(sdkKeysQuery.data?.keys.length ?? 0).toLocaleString()}
          />
        </div>

        <div className="grid gap-3 lg:grid-cols-[minmax(260px,320px)_1fr]">
        <Card>
          <CardHeader className="space-y-2 p-3 pb-2">
            <div className="flex items-center justify-between gap-2">
              <CardTitle className="text-base">Flags</CardTitle>
              <div className="text-xs tabular-nums text-muted-foreground">
                {normalizedFlagSearch ? `${filteredFlags.length} / ${flags.length}` : flags.length}
              </div>
            </div>
            <div className="relative">
              <Label htmlFor="flag-search" className="sr-only">Search flags</Label>
              <Search className="pointer-events-none absolute left-2.5 top-2.5 h-4 w-4 text-muted-foreground" />
              <Input
                id="flag-search"
                value={flagSearch}
                onChange={(event) => setFlagSearch(event.target.value)}
                placeholder="Search flags..."
                className="h-9 pl-8 pr-9"
              />
              {flagSearch && (
                <Button
                  type="button"
                  variant="ghost"
                  size="icon"
                  aria-label="Clear flag search"
                  className="absolute right-1 top-1 h-7 w-7"
                  onClick={() => setFlagSearch('')}
                >
                  <X className="h-4 w-4" />
                </Button>
              )}
            </div>
          </CardHeader>
          <CardContent className="space-y-3 p-3 pt-0">
            <div className="max-h-[34rem] space-y-2 overflow-y-auto pr-1">
              {flagsQuery.isLoading && <div className="text-sm text-muted-foreground">Loading...</div>}
              {filteredFlags.map((flag) => {
                const flagConfig = selectedConfig(flag, environment)
                return (
                  <button
                    key={flag.key}
                    type="button"
                    onClick={() => setSelectedKey(flag.key)}
                    className={cn(
                      'w-full cursor-pointer rounded-md border p-2.5 text-left transition-colors hover:bg-muted/50',
                      selectedFlag?.key === flag.key && 'border-primary bg-muted/60'
                    )}
                  >
                    <div className="flex items-center justify-between gap-2">
                      <span className="truncate font-medium">{flag.name}</span>
                      <Badge variant={flagConfig?.enabled ? 'default' : 'secondary'}>
                        {flagConfig?.enabled ? 'On' : 'Off'}
                      </Badge>
                    </div>
                    <div className="mt-1 flex flex-wrap items-center gap-2 text-xs text-muted-foreground">
                      <code>{flag.key}</code>
                      <span>{flag.valueType.toLowerCase()}</span>
                      {flag.clientVisible && <span>client</span>}
                    </div>
                  </button>
                )
              })}
              {!flagsQuery.isLoading && filteredFlags.length === 0 && (
                <div className="rounded-md border border-dashed p-4 text-center text-sm text-muted-foreground">
                  No flags match this search.
                </div>
              )}
            </div>

            <details className="group border-t pt-2">
              <summary
                className={cn(
                  'flex cursor-pointer list-none items-center justify-between rounded-md px-1 py-1',
                  'text-sm font-medium hover:bg-muted/50 [&::-webkit-details-marker]:hidden'
                )}
              >
                <span className="flex items-center gap-1.5">
                  <Plus className="h-4 w-4" />
                  Create Flag
                </span>
                <ChevronDown className="h-4 w-4 text-muted-foreground transition-transform group-open:rotate-180" />
              </summary>
              <div className="mt-2 space-y-2.5">
                <div className="grid gap-2">
                  <FieldLabel htmlFor="new-flag-key" tooltip={tooltipText.flagKey}>Key</FieldLabel>
                  <Input
                    id="new-flag-key"
                    value={newFlag.key}
                    onChange={(event) => setNewFlag({...newFlag, key: event.target.value})}
                    placeholder="checkout.enabled"
                  />
                </div>
                <div className="grid gap-2">
                  <FieldLabel htmlFor="new-flag-name" tooltip={tooltipText.flagName}>Name</FieldLabel>
                  <Input
                    id="new-flag-name"
                    value={newFlag.name}
                    onChange={(event) => setNewFlag({...newFlag, name: event.target.value})}
                    placeholder="Checkout Enabled"
                  />
                </div>
                <div className="grid grid-cols-2 gap-2">
                  <div className="grid gap-2">
                    <FieldLabel tooltip={tooltipText.valueType}>Type</FieldLabel>
                    <Select
                      value={newFlag.valueType}
                      onValueChange={(value) => setNewFlag({...newFlag, valueType: value as FeatureFlagValueType})}
                    >
                      <SelectTrigger><SelectValue /></SelectTrigger>
                      <SelectContent>
                        {valueTypes.map((type) => <SelectItem key={type} value={type}>{type}</SelectItem>)}
                      </SelectContent>
                    </Select>
                  </div>
                  <div className="flex items-end gap-2 pb-2">
                    <Switch
                      checked={newFlag.clientVisible}
                      onCheckedChange={(checked) => setNewFlag({...newFlag, clientVisible: checked})}
                    />
                    <FieldLabel tooltip={tooltipText.clientVisible}>Client</FieldLabel>
                  </div>
                </div>
                <FieldLabel tooltip={tooltipText.variantsJson}>Variants JSON</FieldLabel>
                <CodeEditor
                  value={newFlag.variants}
                  onChange={(variants) => setNewFlag({...newFlag, variants})}
                  language="json"
                  rows={5}
                />
                <FieldLabel tooltip={tooltipText.tags}>Tags</FieldLabel>
                <Input
                  value={newFlag.tags}
                  onChange={(event) => setNewFlag({...newFlag, tags: event.target.value})}
                  placeholder="billing, beta"
                />
                <Button
                  className="w-full gap-2"
                  onClick={() => createFlagMutation.mutate()}
                  disabled={!newFlag.key || !newFlag.name || createFlagMutation.isPending}
                >
                  <Plus className="h-4 w-4" />
                  Create Flag
                </Button>
              </div>
            </details>

            <div className="space-y-2.5 border-t pt-3">
              <div className="flex items-center gap-1.5 text-sm font-medium">
                Environments
                <HelpIcon content={tooltipText.environments} />
              </div>
              <div className="flex flex-wrap gap-2">
                {environments.map((env) => (
                  <Badge key={env.key} variant={env.key === environment ? 'default' : 'secondary'}>
                    {env.name}
                  </Badge>
                ))}
              </div>
              <details className="group rounded-md border bg-muted/20 p-2">
                <summary
                  className={cn(
                    'flex cursor-pointer list-none items-center justify-between text-sm font-medium',
                    '[&::-webkit-details-marker]:hidden'
                  )}
                >
                  <span className="flex items-center gap-1.5">
                    <Plus className="h-4 w-4" />
                    Add Environment
                  </span>
                  <ChevronDown className="h-4 w-4 text-muted-foreground transition-transform group-open:rotate-180" />
                </summary>
                <div className="mt-2 space-y-2.5">
                  <div className="grid grid-cols-2 gap-2">
                    <FieldLabel tooltip={tooltipText.environmentKey}>Key</FieldLabel>
                    <FieldLabel tooltip={tooltipText.environmentName}>Name</FieldLabel>
                  </div>
                  <div className="grid grid-cols-2 gap-2">
                    <Input
                      value={environmentDraft.key}
                      onChange={(event) => setEnvironmentDraft({...environmentDraft, key: event.target.value})}
                      placeholder="qa"
                    />
                    <Input
                      value={environmentDraft.name}
                      onChange={(event) => setEnvironmentDraft({...environmentDraft, name: event.target.value})}
                      placeholder="QA"
                    />
                  </div>
                  <Button
                    variant="outline"
                    className="w-full gap-2"
                    onClick={() => createEnvironmentMutation.mutate()}
                    disabled={!environmentDraft.key || !environmentDraft.name || createEnvironmentMutation.isPending}
                  >
                    <Plus className="h-4 w-4" />
                    Add Environment
                  </Button>
                </div>
              </details>
            </div>
          </CardContent>
        </Card>

        <div className="min-w-0">
          {selectedFlag ? (
            <FlagDetail
              flag={selectedFlag}
              config={config}
              configDraft={configDraft}
              setConfigDraft={updateConfigDraft}
              variantDraft={variantDraft}
              setVariantDraft={updateVariantDraft}
              onSaveVariants={() => updateFlagVariantsMutation.mutate()}
              onSaveConfig={() => updateConfigMutation.mutate()}
              onArchive={() => deleteFlagMutation.mutate(selectedFlag.key)}
              environment={environment}
              analytics={analytics}
              auditEvents={auditQuery.data?.events ?? []}
              segments={segmentsQuery.data?.segments ?? []}
              segmentDraft={segmentDraft}
              setSegmentDraft={setSegmentDraft}
              onSaveSegment={() => upsertSegmentMutation.mutate()}
              onDeleteSegment={(key) => deleteSegmentMutation.mutate(key)}
              sdkKeys={sdkKeysQuery.data?.keys ?? []}
              sdkKeyDraft={sdkKeyDraft}
              setSdkKeyDraft={setSdkKeyDraft}
              onCreateSdkKey={() => createSdkKeyMutation.mutate()}
              onRevokeSdkKey={(id) => revokeSdkKeyMutation.mutate(id)}
              createdKey={createdKey}
              kotlinExample={kotlinExample}
            />
          ) : (
            <EmptyState
              icon={Flag}
              title={normalizedFlagSearch ? 'No matching flags' : 'No feature flags yet'}
              description={
                normalizedFlagSearch
                  ? 'No feature flags match this search in the selected environment.'
                  : 'Create your first flag to start targeting and evaluating in this environment.'
              }
            />
          )}
        </div>
      </div>
      </div>
    </TooltipProvider>
  )
}

function FlagDetail(props: Readonly<{
  flag: FeatureFlag
  config: FeatureFlagConfig | null
  configDraft: ConfigDraft
  setConfigDraft: (value: ConfigDraft) => void
  variantDraft: string
  setVariantDraft: (value: string) => void
  onSaveVariants: () => void
  onSaveConfig: () => void
  onArchive: () => void
  environment: string
  analytics: Awaited<ReturnType<typeof api.getFeatureFlagAnalytics>> | undefined
  auditEvents: Awaited<ReturnType<typeof api.listFeatureFlagAuditEvents>>['events']
  segments: FeatureFlagSegment[]
  segmentDraft: SegmentDraft
  setSegmentDraft: (value: SegmentDraft) => void
  onSaveSegment: () => void
  onDeleteSegment: (key: string) => void
  sdkKeys: Awaited<ReturnType<typeof api.listFeatureFlagSdkKeys>>['keys']
  sdkKeyDraft: {name: string; keyType: FeatureFlagSdkKeyType}
  setSdkKeyDraft: (value: {name: string; keyType: FeatureFlagSdkKeyType}) => void
  onCreateSdkKey: () => void
  onRevokeSdkKey: (id: string) => void
  createdKey: CreatedFeatureFlagSdkKey | null
  kotlinExample: string
}>) {
  const flagAnalytics = props.analytics?.variants.filter((item) => item.flagKey === props.flag.key) ?? []

  return (
    <Tabs defaultValue="variants" className="space-y-3">
      <div className="flex flex-col gap-3 rounded-md border bg-card p-3 md:flex-row md:items-start md:justify-between">
        <div className="min-w-0">
          <div className="flex flex-wrap items-center gap-2">
            <h2 className="truncate text-lg font-semibold">{props.flag.name}</h2>
            <Badge variant="secondary">{props.flag.valueType}</Badge>
            {props.flag.clientVisible && <Badge>Client-visible</Badge>}
          </div>
          <code className="mt-1 block truncate text-xs text-muted-foreground">{props.flag.key}</code>
        </div>
        <div className="flex gap-2">
          <Button variant="outline" size="sm" onClick={props.onSaveConfig} className="gap-2">
            <Save className="h-4 w-4" />
            Save
          </Button>
          <Button variant="destructive" size="sm" onClick={props.onArchive} className="gap-2">
            <Trash2 className="h-4 w-4" />
            Archive
          </Button>
        </div>
      </div>

      <TabsList className="flex h-auto flex-wrap justify-start">
        <TabsTrigger value="variants" className={tabTriggerClass}>Variants</TabsTrigger>
        <TabsTrigger value="targeting" className={tabTriggerClass}>Targeting</TabsTrigger>
        <TabsTrigger value="segments" className={tabTriggerClass}>Segments</TabsTrigger>
        <TabsTrigger value="analytics" className={tabTriggerClass}>Analytics</TabsTrigger>
        <TabsTrigger value="audit" className={tabTriggerClass}>Audit</TabsTrigger>
        <TabsTrigger value="setup" className={tabTriggerClass}>SDK Setup</TabsTrigger>
      </TabsList>

      <TabsContent value="variants">
          <Card>
            <CardContent className="space-y-3 p-3">
              <Table>
                <TableHeader>
                  <TableRow>
                    <TableHead>Variant</TableHead>
                    <TableHead>Value</TableHead>
                    <TableHead className="w-24">Order</TableHead>
                  </TableRow>
                </TableHeader>
                <TableBody>
                  {props.flag.variants.map((variant) => (
                    <TableRow key={variant.key}>
                      <TableCell>
                        <div className="font-medium">{variant.name}</div>
                        <code className="text-xs text-muted-foreground">{variant.key}</code>
                      </TableCell>
                      <TableCell><code className="text-xs">{formatValue(variant.value)}</code></TableCell>
                      <TableCell>{variant.sortOrder}</TableCell>
                    </TableRow>
                  ))}
                </TableBody>
              </Table>
              <div className="grid gap-2">
                <FieldLabel tooltip={tooltipText.variantsJson}>Variants JSON</FieldLabel>
                <CodeEditor
                  value={props.variantDraft}
                  onChange={props.setVariantDraft}
                  language="json"
                  rows={10}
                />
              </div>
              <Button variant="outline" size="sm" onClick={props.onSaveVariants} className="gap-2">
                <Save className="h-4 w-4" />
                Save Variants
              </Button>
            </CardContent>
          </Card>
        </TabsContent>

      <TabsContent value="targeting">
        <Card>
          <CardContent className="space-y-3 p-3">
            <div className="flex flex-wrap items-center gap-3">
              <div className="flex items-center gap-2">
                <Switch
                  checked={props.configDraft.enabled}
                  onCheckedChange={(enabled) => props.setConfigDraft({...props.configDraft, enabled})}
                />
                <FieldLabel tooltip={tooltipText.enabled}>Enabled in {props.environment}</FieldLabel>
              </div>
              <Badge variant="outline">{props.segments.length} segments</Badge>
              <Badge variant="outline">v{props.config?.version ?? 0}</Badge>
            </div>
            <div className="grid gap-3 md:grid-cols-2">
              <VariantSelect
                label="Default Variant"
                tooltip={tooltipText.defaultVariant}
                value={props.configDraft.defaultVariantKey}
                variants={props.flag.variants}
                onChange={(value) => props.setConfigDraft({...props.configDraft, defaultVariantKey: value})}
              />
              <VariantSelect
                label="Off Variant"
                tooltip={tooltipText.offVariant}
                value={props.configDraft.offVariantKey}
                variants={props.flag.variants}
                onChange={(value) => props.setConfigDraft({...props.configDraft, offVariantKey: value})}
              />
            </div>
            <div className="grid gap-2">
              <FieldLabel tooltip={tooltipText.rulesJson}>Rules JSON</FieldLabel>
              <Textarea
                value={props.configDraft.rules}
                onChange={(event) => props.setConfigDraft({...props.configDraft, rules: event.target.value})}
                className="min-h-52 font-mono text-xs"
              />
            </div>
          </CardContent>
          </Card>
        </TabsContent>

      <TabsContent value="segments">
        <div className="grid gap-3 lg:grid-cols-[300px_1fr]">
          <Card>
            <CardHeader className="p-3 pb-2">
              <CardTitle className="flex items-center gap-1.5 text-base">
                Segments
                <HelpIcon content={tooltipText.segmentList} />
              </CardTitle>
            </CardHeader>
            <CardContent className="space-y-2 p-3 pt-0">
              {props.segments.map((segment) => (
                <div key={segment.key} className="flex items-center justify-between rounded-md border p-2">
                  <button
                    type="button"
                    className="min-w-0 text-left"
                    onClick={() => props.setSegmentDraft(segmentDraftFromSegment(segment))}
                  >
                    <div className="truncate text-sm font-medium">{segment.name}</div>
                    <code className="text-xs text-muted-foreground">{segment.key}</code>
                  </button>
                  <Button
                    variant="ghost"
                    size="icon"
                    aria-label={`Archive ${segment.name} segment`}
                    onClick={() => props.onDeleteSegment(segment.key)}
                  >
                    <Trash2 className="h-4 w-4" />
                  </Button>
                </div>
              ))}
              {props.segments.length === 0 && (
                <div className="py-6 text-center text-sm text-muted-foreground">No segments yet.</div>
              )}
            </CardContent>
          </Card>
          <Card>
            <CardHeader className="p-3 pb-2">
              <CardTitle className="text-base">Segment Definition</CardTitle>
            </CardHeader>
            <CardContent className="space-y-2.5 p-3 pt-0">
              <div className="grid grid-cols-2 gap-2">
                <FieldLabel tooltip={tooltipText.segmentKey}>Key</FieldLabel>
                <FieldLabel tooltip={tooltipText.segmentName}>Name</FieldLabel>
              </div>
              <div className="grid gap-2 md:grid-cols-2">
                <Input
                  value={props.segmentDraft.key}
                  onChange={(event) => props.setSegmentDraft({...props.segmentDraft, key: event.target.value})}
                  placeholder="beta-users"
                />
                <Input
                  value={props.segmentDraft.name}
                  onChange={(event) => props.setSegmentDraft({...props.segmentDraft, name: event.target.value})}
                  placeholder="Beta Users"
                />
              </div>
              <FieldLabel tooltip={tooltipText.segmentDescription}>Description</FieldLabel>
              <Input
                value={props.segmentDraft.description}
                onChange={(event) => props.setSegmentDraft({...props.segmentDraft, description: event.target.value})}
                placeholder="Optional description"
              />
              <FieldLabel tooltip={tooltipText.segmentConditions}>Conditions JSON</FieldLabel>
              <Textarea
                value={props.segmentDraft.conditions}
                onChange={(event) => props.setSegmentDraft({...props.segmentDraft, conditions: event.target.value})}
                className="min-h-44 font-mono text-xs"
              />
              <Button
                className="gap-2"
                onClick={props.onSaveSegment}
                disabled={!props.segmentDraft.key || !props.segmentDraft.name}
              >
                <Save className="h-4 w-4" />
                Save Segment
              </Button>
            </CardContent>
          </Card>
        </div>
      </TabsContent>

      <TabsContent value="analytics">
        <Card>
          <CardHeader className="p-3 pb-2">
            <CardTitle className="flex items-center gap-2 text-base">
              <BarChart3 className="h-4 w-4" />
              Recent Evaluations
              <HelpIcon content={tooltipText.analyticsSummary} />
            </CardTitle>
          </CardHeader>
          <CardContent className="p-0">
            <Table>
              <TableHeader>
                <TableRow>
                  <TableHead>Variant</TableHead>
                  <TableHead>Evaluations</TableHead>
                  <TableHead>Unique Keys</TableHead>
                </TableRow>
              </TableHeader>
              <TableBody>
                {flagAnalytics.map((item) => (
                  <TableRow key={`${item.flagKey}-${item.variantKey}`}>
                    <TableCell>{item.variantKey || 'none'}</TableCell>
                    <TableCell>{item.evaluations.toLocaleString()}</TableCell>
                    <TableCell>{item.uniqueTargetingKeys.toLocaleString()}</TableCell>
                  </TableRow>
                ))}
                {flagAnalytics.length === 0 && (
                  <TableRow>
                    <TableCell colSpan={3} className="py-8 text-center text-muted-foreground">
                      No evaluations yet.
                    </TableCell>
                  </TableRow>
                )}
              </TableBody>
            </Table>
          </CardContent>
        </Card>
      </TabsContent>

      <TabsContent value="audit">
        <Card>
          <CardContent className="p-0">
            <Table>
              <TableHeader>
                <TableRow>
                  <TableHead>Event</TableHead>
                  <TableHead>Environment</TableHead>
                  <TableHead>When</TableHead>
                </TableRow>
              </TableHeader>
              <TableBody>
                {props.auditEvents
                  .filter((event) => !event.flagKey || event.flagKey === props.flag.key)
                  .map((event) => (
                    <TableRow key={event.id}>
                      <TableCell>{event.eventType}</TableCell>
                      <TableCell>{event.environmentKey ?? 'all'}</TableCell>
                      <TableCell>{new Date(event.createdAt).toLocaleString()}</TableCell>
                    </TableRow>
                  ))}
              </TableBody>
            </Table>
          </CardContent>
        </Card>
      </TabsContent>

      <TabsContent value="setup">
        <div className="grid gap-3 lg:grid-cols-[300px_1fr]">
          <Card>
            <CardHeader className="p-3 pb-2">
              <CardTitle className="flex items-center gap-1.5 text-base">
                SDK Keys
                <HelpIcon content={tooltipText.sdkKeys} />
              </CardTitle>
            </CardHeader>
            <CardContent className="space-y-2.5 p-3 pt-0">
              <FieldLabel tooltip={tooltipText.sdkKeyName}>Name</FieldLabel>
              <Input
                value={props.sdkKeyDraft.name}
                onChange={(event) => props.setSdkKeyDraft({...props.sdkKeyDraft, name: event.target.value})}
                placeholder="Production server key"
              />
              <FieldLabel tooltip={tooltipText.sdkKeyType}>Type</FieldLabel>
              <Select
                value={props.sdkKeyDraft.keyType}
                onValueChange={(value) => props.setSdkKeyDraft({
                  ...props.sdkKeyDraft,
                  keyType: value as FeatureFlagSdkKeyType,
                })}
              >
                <SelectTrigger><SelectValue /></SelectTrigger>
                <SelectContent>
                  <SelectItem value="server">Server</SelectItem>
                  <SelectItem value="client">Client</SelectItem>
                </SelectContent>
              </Select>
              <Button
                className="w-full gap-2"
                onClick={props.onCreateSdkKey}
                disabled={!props.sdkKeyDraft.name}
              >
                <KeyRound className="h-4 w-4" />
                Create Key
              </Button>
              {props.createdKey && <CopyBlock code={props.createdKey.key} language="text" />}
              <div className="space-y-2">
                {props.sdkKeys
                  .filter((key) => key.environmentKey === props.environment)
                  .map((key) => (
                    <div key={key.id} className="flex items-center justify-between rounded-md border p-2">
                      <div className="min-w-0">
                        <div className="truncate text-sm font-medium">{key.name}</div>
                        <div className="text-xs text-muted-foreground">{key.keyPrefix} · {key.keyType}</div>
                      </div>
                      <Button
                        variant="ghost"
                        size="icon"
                        aria-label={`Revoke ${key.name}`}
                        onClick={() => props.onRevokeSdkKey(key.id)}
                      >
                        <Trash2 className="h-4 w-4" />
                      </Button>
                    </div>
                  ))}
              </div>
            </CardContent>
          </Card>
          <CopyBlock code={props.kotlinExample} language="kotlin" />
        </div>
      </TabsContent>
    </Tabs>
  )
}

function VariantSelect({
  label,
  tooltip,
  value,
  variants,
  onChange,
}: {
  label: string
  tooltip: string
  value: string
  variants: FeatureFlag['variants']
  onChange: (value: string) => void
}) {
  return (
    <div className="grid gap-2">
      <FieldLabel tooltip={tooltip}>{label}</FieldLabel>
      <Select value={value} onValueChange={onChange}>
        <SelectTrigger><SelectValue placeholder="Select variant" /></SelectTrigger>
        <SelectContent>
          {variants.map((variant) => (
            <SelectItem key={variant.key} value={variant.key}>{variant.name}</SelectItem>
          ))}
        </SelectContent>
      </Select>
    </div>
  )
}
