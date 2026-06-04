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

import {useEffect, useMemo, useState} from 'react'
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
  Save,
  Server,
  Smartphone,
  Trash2,
} from 'lucide-react'
import {api} from '@/lib/api'
import {trackEvent} from '@/lib/analytics'
import {APP_OVERVIEW_SEARCH} from '@/lib/overview-route'
import {getPlatformInfo, platforms, type PlatformType} from '@/routes/projects'
import {Badge} from '@/components/ui/badge'
import {Button} from '@/components/ui/button'
import {Card, CardContent, CardDescription, CardHeader, CardTitle} from '@/components/ui/card'
import {Dialog, DialogContent, DialogDescription, DialogFooter, DialogHeader, DialogTitle} from '@/components/ui/dialog'
import {Input} from '@/components/ui/input'
import {Label} from '@/components/ui/label'
import {useToast} from '@/hooks/useToast'
import {cn} from '@/lib/utils'
import type {TelemetrySourceId} from '@/lib/telemetry-sources'

type PlatformFilter = 'all' | 'mobile' | 'frontend' | 'backend' | 'desktop-gaming'

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
 * Editing surface for a single service, shared by the Configuration page and the
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

  const [localName, setName] = useState<string | undefined>(undefined)
  const [localFramework, setFramework] = useState<string | undefined>(undefined)
  const [platformFilter, setPlatformFilter] = useState<PlatformFilter>('all')
  const [deleteDialogOpen, setDeleteDialogOpen] = useState(false)
  const [copiedSlug, setCopiedSlug] = useState(false)
  const [copiedConfig, setCopiedConfig] = useState(false)
  const [orgSlug, setOrgSlug] = useState<string | null>(null)

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
    setTimeout(() => setCopiedSlug(false), 2000)
  }

  const copyConfig = () => {
    if (sentryCliConfig) {
      navigator.clipboard.writeText(sentryCliConfig)
      setCopiedConfig(true)
      setTimeout(() => setCopiedConfig(false), 2000)
    }
  }

  return (
    <div className="space-y-6">
      <Card>
        <CardHeader className="flex-row items-start justify-between gap-4 space-y-0">
          <div className="space-y-1.5">
            <CardTitle>General</CardTitle>
            <CardDescription>Update your service name and platform.</CardDescription>
          </div>
          <Button
            onClick={handleSave}
            disabled={!trimmedName || (!nameChanged && !frameworkChanged) || updateServiceMutation.isPending}
          >
            {updateServiceMutation.isPending ? <Loader2 className="h-4 w-4 animate-spin" /> : <Save className="h-4 w-4" />}
            Save Changes
          </Button>
        </CardHeader>
        <CardContent className="space-y-6">
          <div className="space-y-2">
            <Label htmlFor="service-name">Service name</Label>
            <Input
              id="service-name"
              value={name}
              onChange={(e) => setName(e.target.value)}
              placeholder="Checkout API"
            />
          </div>

          {hasSentry && (
            <>
              <div className="space-y-2">
                <Label htmlFor="service-slug">Service slug</Label>
                <div className="flex gap-2">
                  <Input id="service-slug" value={project.slug} readOnly className="bg-muted" />
                  <Button type="button" variant="outline" size="icon" onClick={copySlug}>
                    {copiedSlug ? <Check className="h-4 w-4" /> : <Copy className="h-4 w-4" />}
                  </Button>
                </div>
                <p className="text-xs text-muted-foreground">Used for Sentry CLI and API endpoints</p>
              </div>

              {sentryCliConfig && (
                <div className="space-y-2">
                  <Label>Sentry CLI Configuration</Label>
                  <div className="relative">
                    <pre className="bg-muted rounded-lg p-4 text-xs overflow-x-auto pr-12">
                      <code>{sentryCliConfig}</code>
                    </pre>
                    <Button
                      type="button"
                      variant="ghost"
                      size="icon"
                      className="absolute top-2 right-2"
                      onClick={copyConfig}
                    >
                      {copiedConfig ? <Check className="h-4 w-4" /> : <Copy className="h-4 w-4" />}
                    </Button>
                  </div>
                  <p className="text-xs text-muted-foreground">
                    Use this in your sentry.properties file or with sentry-cli commands
                  </p>
                </div>
              )}
            </>
          )}

          <div className="space-y-3">
            <div>
              <Label className="text-base">Platform / Framework</Label>
              <p className="text-sm text-muted-foreground mt-1">
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
                    className="gap-2"
                  >
                    <Icon className="h-4 w-4" />
                    {tab.label}
                  </Button>
                )
              })}
            </div>

            <div className="max-h-96 overflow-y-auto rounded-lg border border-border p-2 pr-1">
              <div className="grid grid-cols-2 sm:grid-cols-3 md:grid-cols-4 gap-2">
                {filteredPlatforms.map((platform) => {
                  const Icon = platform.icon
                  return (
                    <button
                      key={platform.id}
                      type="button"
                      onClick={() => setFramework(platform.id)}
                      className={cn(
                        'border-2 rounded-lg p-3 text-left transition-colors',
                        framework === platform.id
                          ? 'border-primary bg-primary/10'
                          : 'border-border hover:border-primary/50'
                      )}
                    >
                      <div className="flex items-center gap-2">
                        <div
                          className="w-6 h-6 rounded flex items-center justify-center flex-shrink-0"
                          style={{backgroundColor: platform.color}}
                        >
                          <Icon className="h-4 w-4 text-white" />
                        </div>
                        <span className="text-sm font-medium">{platform.name}</span>
                      </div>
                      <p className="text-xs text-muted-foreground mt-2">{platform.description}</p>
                    </button>
                  )
                })}
              </div>
            </div>
          </div>

          {hasSentry && isMultiplatformFramework && (
            <div className="space-y-3">
              <div>
                <Label className="text-base">Target Platforms</Label>
                <p className="text-sm text-muted-foreground mt-1">Multiplatform frameworks use one DSN per target.</p>
              </div>

              {frameworkChanged ? (
                <div className="rounded-lg border border-dashed border-border p-3 text-sm text-muted-foreground">
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
                          <Badge key={target} variant="secondary" className="flex items-center gap-1.5 px-2.5 py-1">
                            {TargetIcon && (
                              <div
                                className="w-4 h-4 rounded flex items-center justify-center"
                                style={{backgroundColor: targetInfo?.color}}
                              >
                                <TargetIcon className="w-3 h-3 text-white" />
                              </div>
                            )}
                            <span>{targetInfo?.name ?? target}</span>
                          </Badge>
                        )
                      })
                    ) : (
                      <p className="text-sm text-muted-foreground">No targets added yet.</p>
                    )}
                  </div>

                  {availableTargets.length > 0 && (
                    <div className="grid grid-cols-1 sm:grid-cols-2 md:grid-cols-3 gap-2">
                      {availableTargets.map((target) => {
                        const targetInfo = getPlatformInfo(target.id)
                        const TargetIcon = targetInfo?.icon
                        return (
                          <Button
                            key={target.id}
                            type="button"
                            variant="outline"
                            className="justify-start h-auto py-2.5"
                            onClick={() => addTargetMutation.mutate(target.id)}
                            disabled={addTargetMutation.isPending}
                          >
                            {TargetIcon && (
                              <div
                                className="w-5 h-5 rounded flex items-center justify-center"
                                style={{backgroundColor: targetInfo?.color}}
                              >
                                <TargetIcon className="w-3.5 h-3.5 text-white" />
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
        </CardContent>
      </Card>

      {hasOtel && (
        <Card>
          <CardHeader>
            <CardTitle>OpenTelemetry</CardTitle>
            <CardDescription>
              Send telemetry with the resource attribute <code className="font-mono text-xs">service.name</code> set to
              this service.
            </CardDescription>
          </CardHeader>
          <CardContent>
            <Link to="/projects/$projectId" params={{projectId: serviceId}}>
              <Button variant="outline" size="sm">
                <ExternalLink className="h-4 w-4" />
                View ingestion setup
              </Button>
            </Link>
          </CardContent>
        </Card>
      )}

      {hasDatadog && (
        <Card>
          <CardHeader>
            <CardTitle>Datadog Agent</CardTitle>
            <CardDescription>Point a compatible agent at Moneat and tag it with this service.</CardDescription>
          </CardHeader>
          <CardContent>
            <Link to="/projects/$projectId" params={{projectId: serviceId}}>
              <Button variant="outline" size="sm">
                <ExternalLink className="h-4 w-4" />
                View ingestion setup
              </Button>
            </Link>
          </CardContent>
        </Card>
      )}

      <Card className="border-destructive/40">
        <CardHeader>
          <CardTitle className="flex items-center gap-2 text-destructive">
            <AlertTriangle className="h-5 w-5" />
            Danger Zone
          </CardTitle>
          <CardDescription>Delete this service and all related data permanently.</CardDescription>
        </CardHeader>
        <CardContent className="flex flex-col sm:flex-row sm:items-center sm:justify-between gap-3">
          <p className="text-sm text-muted-foreground">
            This action cannot be undone. Events, issues, releases, and DSNs for this service will be removed.
          </p>
          <Button variant="destructive" onClick={() => setDeleteDialogOpen(true)}>
            <Trash2 className="h-4 w-4" />
            Delete Service
          </Button>
        </CardContent>
      </Card>

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
