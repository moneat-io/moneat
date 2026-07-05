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

import {useMemo, useState, type ComponentType} from 'react'
import {useMutation, useQuery, useQueryClient} from '@tanstack/react-query'
import {
  Boxes,
  Container,
  Database,
  FileCode2,
  Flame,
  Gauge,
  KeyRound,
  Layers,
  ListTree,
  PackageSearch,
  Router as RouterIcon,
  ScrollText,
  Server,
  ServerCog,
  Ship,
  Waypoints,
} from 'lucide-react'
import {api} from '@/lib/api'
import {cn} from '@/lib/utils'
import {useToast} from '@/hooks/useToast'
import {Badge} from '@/components/ui/badge'
import {Button} from '@/components/ui/button'
import {Checkbox} from '@/components/ui/checkbox'
import {CopyBlock} from '@/components/ui/copy-block'
import {Input} from '@/components/ui/input'
import {SectionCard} from '@/components/ui/section-card'
import {
  AGENT_CAPABILITIES,
  AGENT_PLATFORMS,
  DEFAULT_AGENT_CAPABILITIES,
  PLACEHOLDER_AGENT_KEY,
  buildAgentArtifacts,
  type AgentCapabilities,
  type AgentCapabilityId,
  type AgentPlatform,
} from '@/lib/agent-config'

const PLATFORM_ICONS: Record<AgentPlatform, ComponentType<{className?: string}>> = {
  host: Server,
  docker: Container,
  compose: Layers,
  kubernetes: Ship,
}

const CAPABILITY_ICONS: Record<AgentCapabilityId, ComponentType<{className?: string}>> = {
  infraMetrics: Gauge,
  containers: Boxes,
  processes: ListTree,
  logs: ScrollText,
  apm: Waypoints,
  profiling: Flame,
  dbm: Database,
  netDevices: RouterIcon,
  sbom: PackageSearch,
}

type OutputTab = 'config' | 'install' | 'verify'

function PlatformPicker({
  platform,
  onSelect,
}: {
  readonly platform: AgentPlatform
  readonly onSelect: (next: AgentPlatform) => void
}) {
  return (
    <div className="grid grid-cols-2 gap-2">
      {AGENT_PLATFORMS.map((option) => {
        const Icon = PLATFORM_ICONS[option.id]
        const selected = option.id === platform
        return (
          <button
            key={option.id}
            type="button"
            onClick={() => onSelect(option.id)}
            aria-pressed={selected}
            className={cn(
              'flex items-start gap-2 rounded-md border p-2 text-left transition-colors',
              selected ? 'border-primary bg-primary/5' : 'border-border hover:bg-muted/50'
            )}
          >
            <span
              className={cn(
                'mt-0.5 flex h-6 w-6 shrink-0 items-center justify-center rounded',
                selected ? 'bg-primary/10 text-primary' : 'bg-muted text-muted-foreground'
              )}
            >
              <Icon className="h-3.5 w-3.5" />
            </span>
            <span className="min-w-0">
              <span className="block text-xs font-medium leading-tight">{option.label}</span>
              <span className="mt-0.5 block text-[11px] leading-snug text-muted-foreground">{option.hint}</span>
            </span>
          </button>
        )
      })}
    </div>
  )
}

function CapabilityChecklist({
  caps,
  onToggle,
}: {
  readonly caps: AgentCapabilities
  readonly onToggle: (id: AgentCapabilityId, next: boolean) => void
}) {
  return (
    <div className="grid grid-cols-1 gap-0.5 sm:grid-cols-2">
      {AGENT_CAPABILITIES.map((capability) => {
        const Icon = CAPABILITY_ICONS[capability.id]
        const checked = caps[capability.id]
        return (
          <label
            key={capability.id}
            className={cn(
              'flex items-start gap-2 rounded-md p-1.5 transition-colors',
              capability.locked ? 'cursor-default' : 'cursor-pointer hover:bg-muted/40'
            )}
          >
            <Checkbox
              checked={checked}
              disabled={capability.locked}
              onCheckedChange={(value) => onToggle(capability.id, value === true)}
              className="mt-0.5"
              aria-label={capability.label}
            />
            <span className="min-w-0">
              <span className="flex items-center gap-1.5 text-xs font-medium leading-tight">
                <Icon className="h-3 w-3 text-muted-foreground" />
                {capability.label}
                {capability.locked && (
                  <Badge variant="neutral" size="sm" className="px-1 py-0 text-[9px] uppercase tracking-wide">
                    always on
                  </Badge>
                )}
              </span>
              <span className="mt-0.5 block text-[11px] leading-snug text-muted-foreground">{capability.hint}</span>
            </span>
          </label>
        )
      })}
    </div>
  )
}

function AgentKeyField({
  createdKey,
  existingCount,
  isPending,
  onCreate,
}: {
  readonly createdKey: string | null
  readonly existingCount: number
  readonly isPending: boolean
  readonly onCreate: (name: string) => void
}) {
  const [name, setName] = useState('Infrastructure agent')
  const helperText = agentKeyHelperText(existingCount)

  if (createdKey) {
    return (
      <div className="rounded-md border border-success-border/60 bg-success-bg/40 px-3 py-2">
        <p className="mb-1 flex items-center gap-1.5 text-[11px] font-medium text-success-fg">
          <KeyRound className="h-3 w-3" />
          Agent key created — copy it now, it is shown once and is embedded in the config below.
        </p>
        <code className="block break-all font-mono text-xs text-foreground">{createdKey}</code>
      </div>
    )
  }

  return (
    <div className="flex flex-col gap-2">
      <div className="flex items-center gap-2">
        <Input
          value={name}
          onChange={(event) => setName(event.target.value)}
          placeholder="e.g. Production hosts"
          className="h-8 text-xs"
          aria-label="Agent key name"
        />
        <Button
          type="button"
          size="sm"
          className="h-8 shrink-0 gap-1.5 text-xs"
          disabled={isPending || !name.trim()}
          onClick={() => onCreate(name.trim())}
        >
          <KeyRound className="h-3.5 w-3.5" />
          {isPending ? 'Creating…' : 'Create key'}
        </Button>
      </div>
      <p className="text-[11px] text-muted-foreground">
        {helperText}
      </p>
    </div>
  )
}

function agentKeyHelperText(existingCount: number): string {
  if (existingCount <= 0) {
    return 'Generate an organization agent key. It is shown once and dropped straight into the config.'
  }
  const keyLabel = existingCount === 1 ? 'key' : 'keys'
  return `${existingCount} agent ${keyLabel} already exist. Create a new one to embed it here — existing values can't be revealed again.`
}

function VerifyPanel() {
  return (
    <ol className="flex flex-col gap-2">
      {[
        'Save the config, then run the install command on your target.',
        'Hosts appear in Infrastructure within a few minutes with CPU, memory, and agent version.',
        'Enabled capabilities (logs, APM, profiling…) populate their own sections as data arrives.',
      ].map((step, index) => (
        <li key={step} className="flex gap-2.5">
          <span className="flex h-5 w-5 shrink-0 items-center justify-center rounded-full bg-info-bg text-[11px] font-semibold text-info-fg">
            {index + 1}
          </span>
          <span className="text-xs leading-5 text-muted-foreground">{step}</span>
        </li>
      ))}
    </ol>
  )
}

export function InfrastructureAgentSetup() {
  const queryClient = useQueryClient()
  const {toast} = useToast()
  const [platform, setPlatform] = useState<AgentPlatform>('docker')
  const [caps, setCaps] = useState<AgentCapabilities>(DEFAULT_AGENT_CAPABILITIES)
  const [createdKey, setCreatedKey] = useState<string | null>(null)
  const [tab, setTab] = useState<OutputTab>('config')

  const {data: agentKeys} = useQuery({
    queryKey: ['agentApiKeys'],
    queryFn: () => api.getAgentApiKeys(),
    enabled: api.isAuthenticated(),
  })

  const createKeyMutation = useMutation({
    mutationFn: (name: string) => api.createAgentApiKey(name),
    onSuccess: (response) => {
      setCreatedKey(response.key)
      queryClient.invalidateQueries({queryKey: ['agentApiKeys']})
      toast({title: 'Agent key created', description: 'Copy the key now — it will not be shown again.'})
    },
    onError: () =>
      toast({title: 'Could not create key', description: 'Please try again.', variant: 'destructive'}),
  })

  const artifacts = useMemo(() => buildAgentArtifacts(platform, caps, createdKey), [platform, caps, createdKey])
  const platformMeta = AGENT_PLATFORMS.find((option) => option.id === platform)
  const active = tab === 'install' ? artifacts.install : artifacts.config
  const enabledCount = AGENT_CAPABILITIES.filter((capability) => caps[capability.id]).length

  const outputTabs: {id: OutputTab; label: string}[] = [
    {id: 'config', label: artifacts.config.label},
    {id: 'install', label: artifacts.install.label},
    {id: 'verify', label: 'Verify'},
  ]

  return (
    <div className="grid grid-cols-1 gap-4 lg:grid-cols-[340px_minmax(0,1fr)]">
      <SectionCard title="Agent" icon={ServerCog} bodyClassName="flex flex-col gap-4">
        <div className="flex flex-col gap-2">
          <p className="text-[11px] font-medium uppercase tracking-wide text-muted-foreground">Platform</p>
          <PlatformPicker platform={platform} onSelect={setPlatform} />
        </div>

        <div className="flex flex-col gap-2 border-t pt-3">
          <div className="flex items-center justify-between">
            <p className="text-[11px] font-medium uppercase tracking-wide text-muted-foreground">Collect</p>
            <span className="text-[11px] tabular-nums text-muted-foreground">{enabledCount} enabled</span>
          </div>
          <CapabilityChecklist
            caps={caps}
            onToggle={(id, next) => setCaps((current) => ({...current, [id]: next}))}
          />
        </div>

        <div className="flex flex-col gap-2 border-t pt-3">
          <p className="text-[11px] font-medium uppercase tracking-wide text-muted-foreground">Agent key</p>
          <AgentKeyField
            createdKey={createdKey}
            existingCount={agentKeys?.keys.length ?? 0}
            isPending={createKeyMutation.isPending}
            onCreate={(name) => createKeyMutation.mutate(name)}
          />
        </div>
      </SectionCard>

      <SectionCard
        title="Generated configuration"
        icon={FileCode2}
        bodyClassName="flex flex-col gap-3"
        actions={
          <div className="flex items-center gap-1 rounded-md border bg-background p-0.5">
            {outputTabs.map((outputTab) => (
              <button
                key={outputTab.id}
                type="button"
                onClick={() => setTab(outputTab.id)}
                className={cn(
                  'rounded px-2 py-1 text-[11px] font-medium transition-colors',
                  tab === outputTab.id
                    ? 'bg-secondary text-secondary-foreground'
                    : 'text-muted-foreground hover:text-foreground'
                )}
              >
                {outputTab.label}
              </button>
            ))}
          </div>
        }
      >
        {tab === 'verify' ? (
          <VerifyPanel />
        ) : (
          <>
            <div className="flex flex-wrap items-center justify-between gap-2">
              <p className="text-xs text-muted-foreground">{active.note}</p>
              {active.path && (
                <code className="rounded bg-muted px-1.5 py-0.5 font-mono text-[11px] text-foreground">
                  {active.path}
                </code>
              )}
            </div>
            <CopyBlock code={active.code} language={active.language} />
            {!createdKey && (
              <p className="text-[11px] text-warning-fg">
                Create an agent key above to replace the <code className="font-mono">{PLACEHOLDER_AGENT_KEY}</code> placeholder.
              </p>
            )}
            <p className="text-[11px] text-muted-foreground">
              {platformMeta?.label} · regenerates live as you change platform or capabilities.
            </p>
          </>
        )}
      </SectionCard>
    </div>
  )
}
