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

import {useEffect, useMemo, useRef, useState} from 'react'
import {Link, useNavigate, useRouter} from '@tanstack/react-router'
import {useMutation, useQuery, useQueryClient} from '@tanstack/react-query'
import {
  AlertTriangle,
  Check,
  Copy,
  ExternalLink,
  Gamepad2,
  Layers,
  Loader2,
  Monitor,
  RadioTower,
  Save,
  Server,
  ServerCog,
  Settings2,
  Smartphone,
  Trash2,
} from 'lucide-react'
import {api} from '@/lib/api'
import {trackEvent} from '@/lib/analytics'
import {APP_OVERVIEW_SEARCH} from '@/lib/overview-route'
import {getPlatformInfo, platforms, type PlatformType} from '@/routes/projects'
import {Badge} from '@/components/ui/badge'
import {Button} from '@/components/ui/button'
import {SectionCard} from '@/components/ui/section-card'
import {Dialog, DialogContent, DialogDescription, DialogFooter, DialogHeader, DialogTitle} from '@/components/ui/dialog'
import {Input} from '@/components/ui/input'
import {Label} from '@/components/ui/label'
import {useToast} from '@/hooks/useToast'
import {cn} from '@/lib/utils'
import type {TelemetrySourceId} from '@/lib/telemetry-sources'

type PlatformFilter = 'all' | 'mobile' | 'frontend' | 'backend' | 'desktop-gaming'

const COPIED_STATE_RESET_MS = 2000

const platformFilterTabs: Array<{id: PlatformFilter; label: string; icon: React.ElementType}> = [
  {id: 'all', label: 'All', icon: Layers},
  {id: 'mobile', label: 'Mobile', icon: Smartphone},
  {id: 'frontend', label: 'Frontend', icon: Monitor},
  {id: 'backend', label: 'Backend', icon: Server},
  {id: 'desktop-gaming', label: 'Desktop & Gaming', icon: Gamepad2},
]

interface ServiceSettingsCardProps {
  /** Stable service resource id used by the legacy project API contract. */
  readonly serviceId: string
  /** The service's enabled telemetry sources — gates which config sections show. */
  readonly sourceIds: TelemetrySourceId[]
  /** Called after a successful delete. When omitted, navigates to the overview. */
  readonly onDeleted?: () => void
}

/**
 * Editing surface for a single service, shared by the Setup page and the
 * legacy /projects/$projectId/settings route. Sections are gated by the service's
 * enabled telemetry sources: Sentry slug/CLI/DSN-targets only with the Sentry SDK
 * source; short OTLP/Datadog pointers with those sources. General + Danger always.
 *
 * Loads its own service by id, so callers that switch services should pass a
 * `key={serviceId}` to reset local edits on change.
 */
export function ServiceSettingsCard({serviceId, sourceIds, onDeleted}: ServiceSettingsCardProps) {
  const router = useRouter()
  const navigate = useNavigate()
  const queryClient = useQueryClient()
  const {toast} = useToast()

  const {data: project, isLoading} = useQuery({
    queryKey: ['project', serviceId],
    queryFn: () => api.getProject(serviceId),
    enabled: !!serviceId,
  })

  const [localName, setLocalName] = useState<string | undefined>(undefined)
  const [localFramework, setLocalFramework] = useState<string | undefined>(undefined)
  const [platformFilter, setPlatformFilter] = useState<PlatformFilter>('all')
  const [deleteDialogOpen, setDeleteDialogOpen] = useState(false)
  const [copiedSlug, setCopiedSlug] = useState(false)
  const [copiedConfig, setCopiedConfig] = useState(false)
  const [orgSlug, setOrgSlug] = useState<string | null>(null)
  const copiedSlugResetId = useRef<ReturnType<typeof globalThis.setTimeout> | null>(null)
  const copiedConfigResetId = useRef<ReturnType<typeof globalThis.setTimeout> | null>(null)

  const hasSentry = sourceIds.includes('sentry-sdk')
  const hasOtel = sourceIds.includes('opentelemetry')
  const hasDatadog = sourceIds.includes('datadog-agent')

  useEffect(() => {
    let cancelled = false
    async function fetchOrgSlug() {
      try {
        const user = await api.getCurrentUser()
        if (!cancelled) setOrgSlug(user.organizationSlug || null)
      } catch (error) {
        console.error('Failed to fetch organization slug:', error)
      }
    }
    if (hasSentry) fetchOrgSlug()
    return () => {
      cancelled = true
    }
  }, [hasSentry])

  useEffect(() => {
    return () => {
      if (copiedSlugResetId.current !== null) {
        globalThis.clearTimeout(copiedSlugResetId.current)
      }
      if (copiedConfigResetId.current !== null) {
        globalThis.clearTimeout(copiedConfigResetId.current)
      }
    }
  }, [])

  const filteredPlatforms = useMemo(() => {
    return platforms.filter((platform: PlatformType) => {
      if (platform.alwaysVisible || platformFilter === 'all') return true
      if (platformFilter === 'desktop-gaming') {
        return platform.category === 'desktop' || platform.category === 'gaming'
      }
      return platform.category === platformFilter
    })
  }, [platformFilter])

  const updateServiceMutation = useMutation({
    mutationFn: (updates: {name?: string; framework?: string}) => api.updateProject(serviceId, updates),
    onSuccess: async () => {
      trackEvent('Service Update')
      await queryClient.invalidateQueries({queryKey: ['projects']})
      await queryClient.invalidateQueries({queryKey: ['project', serviceId]})
      await router.invalidate()
      toast({title: 'Service updated', description: 'Service settings were saved successfully.'})
    },
    onError: (err: Error) => {
      toast({title: 'Failed to update service', description: err.message, variant: 'destructive'})
    },
  })

  const deleteServiceMutation = useMutation({
    mutationFn: () => api.deleteProject(serviceId),
    onSuccess: async () => {
      trackEvent('Service Delete')
      await queryClient.invalidateQueries({queryKey: ['projects']})
      toast({title: 'Service deleted', description: `"${project?.name ?? 'Service'}" has been deleted.`})
      if (onDeleted) {
        onDeleted()
      } else {
        navigate({to: '/', search: APP_OVERVIEW_SEARCH})
      }
    },
    onError: (err: Error) => {
      toast({title: 'Failed to delete service', description: err.message, variant: 'destructive'})
    },
  })

  const addTargetMutation = useMutation({
    mutationFn: (target: string) => api.addProjectTarget(serviceId, target),
    onSuccess: async () => {
      await queryClient.invalidateQueries({queryKey: ['projects']})
      await queryClient.invalidateQueries({queryKey: ['project', serviceId]})
      await router.invalidate()
      toast({title: 'Target added', description: 'A new platform target and DSN were created.'})
    },
    onError: (err: Error) => {
      toast({title: 'Failed to add target', description: err.message, variant: 'destructive'})
    },
  })

  if (isLoading || !project) {
    return <div className="text-sm text-muted-foreground">Loading service…</div>
  }

  const backendUrl = import.meta.env.VITE_BACKEND_URL || 'https://api.moneat.io'
  const sentryCliConfig = orgSlug
    ? `[defaults]\nurl=${backendUrl}\norg=${orgSlug}\nproject=${project.slug}`
    : null

  const initialFramework = getPlatformInfo(project.framework)?.id ?? 'other'
  const name = localName ?? project.name
  const framework = localFramework ?? initialFramework
  const trimmedName = name.trim()
  const nameChanged = trimmedName !== project.name
  const frameworkChanged = framework !== initialFramework
  const frameworkInfo = getPlatformInfo(framework)
  const isMultiplatformFramework = !!frameworkInfo?.targets?.length
  const currentTargets = project.keys
    .map((key) => key.platformTarget)
    .filter((target): target is string => Boolean(target))
  const availableTargets = frameworkInfo?.targets?.filter((target) => !currentTargets.includes(target.id)) ?? []

  const handleSave = () => {
    if (!trimmedName) {
      toast({
        title: 'Service name is required',
        description: 'Please provide a service name before saving.',
        variant: 'destructive',
      })
      return
    }

    const updates: {name?: string; framework?: string} = {}
    if (nameChanged) updates.name = trimmedName
    if (frameworkChanged) updates.framework = framework

    if (Object.keys(updates).length === 0) return
    updateServiceMutation.mutate(updates)
  }

  const copySlug = () => {
    navigator.clipboard.writeText(project.slug)
    setCopiedSlug(true)
    if (copiedSlugResetId.current !== null) {
      globalThis.clearTimeout(copiedSlugResetId.current)
    }
    copiedSlugResetId.current = globalThis.setTimeout(() => {
      setCopiedSlug(false)
      copiedSlugResetId.current = null
    }, COPIED_STATE_RESET_MS)
  }

  const copyConfig = () => {
    if (sentryCliConfig) {
      navigator.clipboard.writeText(sentryCliConfig)
      setCopiedConfig(true)
      if (copiedConfigResetId.current !== null) {
        globalThis.clearTimeout(copiedConfigResetId.current)
      }
      copiedConfigResetId.current = globalThis.setTimeout(() => {
        setCopiedConfig(false)
        copiedConfigResetId.current = null
      }, COPIED_STATE_RESET_MS)
    }
  }

  return (
    <div className="space-y-3">
      <SectionCard
        title="General"
        icon={Settings2}
        bodyClassName="space-y-4"
        actions={
          <Button
            size="sm"
            className="h-7 gap-1.5 text-xs"
            onClick={handleSave}
            disabled={!trimmedName || (!nameChanged && !frameworkChanged) || updateServiceMutation.isPending}
          >
            {updateServiceMutation.isPending ? (
              <Loader2 className="h-3.5 w-3.5 animate-spin" />
            ) : (
              <Save className="h-3.5 w-3.5" />
            )}
            Save Changes
          </Button>
        }
      >
        <div className="space-y-1.5">
          <Label htmlFor="service-name" className="text-xs">
            Service name
          </Label>
          <Input
            id="service-name"
            value={name}
            onChange={(e) => setLocalName(e.target.value)}
            placeholder="Checkout API"
            className="h-8"
          />
        </div>

        {hasSentry && (
          <>
            <div className="space-y-1.5">
              <Label htmlFor="service-slug" className="text-xs">
                Service slug
              </Label>
              <div className="flex gap-2">
                <Input id="service-slug" value={project.slug} readOnly className="h-8 bg-muted" />
                <Button type="button" variant="outline" size="icon" className="h-8 w-8 shrink-0" onClick={copySlug}>
                  {copiedSlug ? <Check className="h-3.5 w-3.5" /> : <Copy className="h-3.5 w-3.5" />}
                </Button>
              </div>
              <p className="text-[11px] text-muted-foreground">Used for Sentry CLI and API endpoints</p>
            </div>

            {sentryCliConfig && (
              <div className="space-y-1.5">
                <Label className="text-xs">Sentry CLI Configuration</Label>
                <div className="relative">
                  <pre className="overflow-x-auto rounded-md bg-muted p-3 pr-12 text-xs">
                    <code>{sentryCliConfig}</code>
                  </pre>
                  <Button
                    type="button"
                    variant="ghost"
                    size="icon"
                    className="absolute right-1.5 top-1.5 h-7 w-7"
                    onClick={copyConfig}
                  >
                    {copiedConfig ? <Check className="h-3.5 w-3.5" /> : <Copy className="h-3.5 w-3.5" />}
                  </Button>
                </div>
                <p className="text-[11px] text-muted-foreground">
                  Use this in your sentry.properties file or with sentry-cli commands
                </p>
              </div>
            )}
          </>
        )}

        <div className="space-y-2">
          <div>
            <p className="text-xs font-medium uppercase tracking-wide text-muted-foreground">Platform / Framework</p>
            <p className="mt-0.5 text-[11px] text-muted-foreground">
              Changing platform does not rotate or invalidate existing DSNs.
            </p>
          </div>

          <div className="flex flex-wrap gap-1">
            {platformFilterTabs.map((tab) => {
              const Icon = tab.icon
              return (
                <Button
                  key={tab.id}
                  variant={platformFilter === tab.id ? 'default' : 'outline'}
                  size="sm"
                  onClick={() => setPlatformFilter(tab.id)}
                  className="h-7 gap-1.5 text-xs"
                >
                  <Icon className="h-3.5 w-3.5" />
                  {tab.label}
                </Button>
              )
            })}
          </div>

          <div className="max-h-64 overflow-y-auto rounded-md border border-border p-2">
            <div className="grid grid-cols-2 gap-2 sm:grid-cols-3 md:grid-cols-4">
              {filteredPlatforms.map((platform) => {
                const Icon = platform.icon
                return (
                  <button
                    key={platform.id}
                    type="button"
                    onClick={() => setLocalFramework(platform.id)}
                    className={cn(
                      'rounded-md border p-2 text-left transition-colors',
                      framework === platform.id
                        ? 'border-primary bg-primary/10'
                        : 'border-border hover:border-primary/50'
                    )}
                  >
                    <div className="flex items-center gap-2">
                      <div
                        className="flex h-5 w-5 shrink-0 items-center justify-center rounded"
                        style={{backgroundColor: platform.color}}
                      >
                        <Icon className="h-3 w-3 text-white" />
                      </div>
                      <span className="text-xs font-medium">{platform.name}</span>
                    </div>
                    <p className="mt-1 text-[11px] leading-snug text-muted-foreground">{platform.description}</p>
                  </button>
                )
              })}
            </div>
          </div>
        </div>

        {hasSentry && isMultiplatformFramework && (
          <div className="space-y-2">
            <div>
              <p className="text-xs font-medium uppercase tracking-wide text-muted-foreground">Target Platforms</p>
              <p className="mt-0.5 text-[11px] text-muted-foreground">
                Multiplatform frameworks use one DSN per target.
              </p>
            </div>

            {frameworkChanged ? (
              <div className="rounded-md border border-dashed border-border p-3 text-xs text-muted-foreground">
                Save framework changes first, then add target platforms here.
              </div>
            ) : (
              <>
                <div className="flex flex-wrap gap-2">
                  {currentTargets.length > 0 ? (
                    currentTargets.map((target) => {
                      const targetInfo = getPlatformInfo(target)
                      const TargetIcon = targetInfo?.icon
                      return (
                        <Badge key={target} variant="secondary" className="flex items-center gap-1.5 px-2 py-0.5">
                          {TargetIcon && (
                            <div
                              className="flex h-4 w-4 items-center justify-center rounded"
                              style={{backgroundColor: targetInfo?.color}}
                            >
                              <TargetIcon className="h-3 w-3 text-white" />
                            </div>
                          )}
                          <span>{targetInfo?.name ?? target}</span>
                        </Badge>
                      )
                    })
                  ) : (
                    <p className="text-xs text-muted-foreground">No targets added yet.</p>
                  )}
                </div>

                {availableTargets.length > 0 && (
                  <div className="grid grid-cols-1 gap-2 sm:grid-cols-2 md:grid-cols-3">
                    {availableTargets.map((target) => {
                      const targetInfo = getPlatformInfo(target.id)
                      const TargetIcon = targetInfo?.icon
                      return (
                        <Button
                          key={target.id}
                          type="button"
                          variant="outline"
                          size="sm"
                          className="h-auto justify-start gap-2 py-2"
                          onClick={() => addTargetMutation.mutate(target.id)}
                          disabled={addTargetMutation.isPending}
                        >
                          {TargetIcon && (
                            <div
                              className="flex h-4 w-4 items-center justify-center rounded"
                              style={{backgroundColor: targetInfo?.color}}
                            >
                              <TargetIcon className="h-3 w-3 text-white" />
                            </div>
                          )}
                          <span>Add {target.name}</span>
                        </Button>
                      )
                    })}
                  </div>
                )}
              </>
            )}
          </div>
        )}
      </SectionCard>

      {hasOtel && (
        <SectionCard title="OpenTelemetry" icon={RadioTower} bodyClassName="space-y-2">
          <p className="text-xs text-muted-foreground">
            Send telemetry with the resource attribute <code className="font-mono text-[11px]">service.name</code> set
            to this service.
          </p>
          <Link to="/setup" search={{tab: 'services', service: serviceId}}>
            <Button variant="outline" size="sm" className="h-7 gap-1.5 text-xs">
              <ExternalLink className="h-3.5 w-3.5" />
              View ingestion setup
            </Button>
          </Link>
        </SectionCard>
      )}

      {hasDatadog && (
        <SectionCard title="Datadog Agent" icon={ServerCog} bodyClassName="space-y-2">
          <p className="text-xs text-muted-foreground">
            Point a compatible agent at Moneat and tag it with this service.
          </p>
          <Link to="/setup" search={{tab: 'services', service: serviceId}}>
            <Button variant="outline" size="sm" className="h-7 gap-1.5 text-xs">
              <ExternalLink className="h-3.5 w-3.5" />
              View ingestion setup
            </Button>
          </Link>
        </SectionCard>
      )}

      <SectionCard
        title="Danger Zone"
        icon={AlertTriangle}
        iconTone="danger"
        className="border-destructive/40"
        bodyClassName="flex flex-col gap-3 sm:flex-row sm:items-center sm:justify-between"
      >
        <p className="text-xs text-muted-foreground">
          This action cannot be undone. Events, issues, releases, and DSNs for this service will be removed.
        </p>
        <Button
          variant="destructive"
          size="sm"
          className="h-7 shrink-0 gap-1.5 text-xs"
          onClick={() => setDeleteDialogOpen(true)}
        >
          <Trash2 className="h-3.5 w-3.5" />
          Delete Service
        </Button>
      </SectionCard>

      <Dialog open={deleteDialogOpen} onOpenChange={setDeleteDialogOpen}>
        <DialogContent>
          <DialogHeader>
            <DialogTitle>Delete service?</DialogTitle>
            <DialogDescription>
              This will permanently delete <strong>{project.name}</strong> and all associated data.
            </DialogDescription>
          </DialogHeader>
          <DialogFooter>
            <Button variant="outline" onClick={() => setDeleteDialogOpen(false)} disabled={deleteServiceMutation.isPending}>
              Cancel
            </Button>
            <Button
              variant="destructive"
              onClick={() => deleteServiceMutation.mutate()}
              disabled={deleteServiceMutation.isPending}
            >
              {deleteServiceMutation.isPending ? <Loader2 className="h-4 w-4 animate-spin" /> : <Trash2 className="h-4 w-4" />}
              Delete Service
            </Button>
          </DialogFooter>
        </DialogContent>
      </Dialog>
    </div>
  )
}
