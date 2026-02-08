import {useEffect, useMemo, useState} from 'react'
import {createFileRoute, Link, redirect, useNavigate, useRouter} from '@tanstack/react-router'
import {useMutation, useQueryClient} from '@tanstack/react-query'
import {AlertTriangle, ArrowLeft, Loader2, Save, Trash2} from 'lucide-react'
import {api} from '@/lib/api'
import {useProject} from '@/contexts/project-context'
import {getPlatformInfo, platforms, type PlatformType} from '@/routes/projects'
import {Badge} from '@/components/ui/badge'
import {Button} from '@/components/ui/button'
import {Card, CardContent, CardDescription, CardHeader, CardTitle} from '@/components/ui/card'
import {Dialog, DialogContent, DialogDescription, DialogFooter, DialogHeader, DialogTitle} from '@/components/ui/dialog'
import {Input} from '@/components/ui/input'
import {Label} from '@/components/ui/label'
import {useToast} from '@/hooks/use-toast'
import {cn} from '@/lib/utils'

type PlatformFilter = 'all' | 'mobile' | 'frontend' | 'backend' | 'desktop-gaming'

const platformFilterTabs: Array<{ id: PlatformFilter; label: string }> = [
  { id: 'all', label: 'All' },
  { id: 'mobile', label: 'Mobile' },
  { id: 'frontend', label: 'Frontend' },
  { id: 'backend', label: 'Backend' },
  { id: 'desktop-gaming', label: 'Desktop & Gaming' },
]

export const Route = createFileRoute('/projects/$projectId/settings')({
  beforeLoad: async () => {
    if (!api.isAuthenticated()) {
      throw redirect({ to: '/login' })
    }
  },
  loader: async ({ params }) => {
    const project = await api.getProject(Number(params.projectId))
    return { project }
  },
  component: ProjectSettingsPage,
})

function ProjectSettingsPage() {
  const { project } = Route.useLoaderData()
  const router = useRouter()
  const navigate = useNavigate()
  const queryClient = useQueryClient()
  const { selectedProjectId, setSelectedProjectId } = useProject()
  const { toast } = useToast()

  const [name, setName] = useState(project.name)
  const [framework, setFramework] = useState(getPlatformInfo(project.framework)?.id ?? 'other')
  const [platformFilter, setPlatformFilter] = useState<PlatformFilter>('all')
  const [deleteDialogOpen, setDeleteDialogOpen] = useState(false)

  useEffect(() => {
    setName(project.name)
    setFramework(getPlatformInfo(project.framework)?.id ?? 'other')
  }, [project.framework, project.id, project.name])

  const initialFramework = getPlatformInfo(project.framework)?.id ?? 'other'
  const trimmedName = name.trim()
  const nameChanged = trimmedName !== project.name
  const frameworkChanged = framework !== initialFramework
  const frameworkInfo = getPlatformInfo(framework)
  const isMultiplatformFramework = !!frameworkInfo?.targets?.length
  const currentTargets = project.keys
    .map(key => key.platformTarget)
    .filter((target): target is string => Boolean(target))
  const availableTargets = frameworkInfo?.targets?.filter(target => !currentTargets.includes(target.id)) ?? []

  const filteredPlatforms = useMemo(() => {
    return platforms.filter((platform: PlatformType) => {
      if (platform.alwaysVisible || platformFilter === 'all') return true
      if (platformFilter === 'desktop-gaming') {
        return platform.category === 'desktop' || platform.category === 'gaming'
      }
      return platform.category === platformFilter
    })
  }, [platformFilter])

  const updateProjectMutation = useMutation({
    mutationFn: (updates: { name?: string; framework?: string }) => api.updateProject(project.id, updates),
    onSuccess: async () => {
      await queryClient.invalidateQueries({ queryKey: ['projects'] })
      await queryClient.invalidateQueries({ queryKey: ['project', project.id] })
      await router.invalidate()
      toast({
        title: 'Project updated',
        description: 'Project settings were saved successfully.',
      })
    },
    onError: (err: Error) => {
      toast({
        title: 'Failed to update project',
        description: err.message,
        variant: 'destructive',
      })
    },
  })

  const deleteProjectMutation = useMutation({
    mutationFn: () => api.deleteProject(project.id),
    onSuccess: async () => {
      await queryClient.invalidateQueries({ queryKey: ['projects'] })
      if (selectedProjectId === project.id) {
        setSelectedProjectId(null)
      }
      toast({
        title: 'Project deleted',
        description: `"${project.name}" has been deleted.`,
      })
      navigate({ to: '/' })
    },
    onError: (err: Error) => {
      toast({
        title: 'Failed to delete project',
        description: err.message,
        variant: 'destructive',
      })
    },
  })

  const addTargetMutation = useMutation({
    mutationFn: (target: string) => api.addProjectTarget(project.id, target),
    onSuccess: async () => {
      await queryClient.invalidateQueries({ queryKey: ['projects'] })
      await router.invalidate()
      toast({
        title: 'Target added',
        description: 'A new platform target and DSN were created.',
      })
    },
    onError: (err: Error) => {
      toast({
        title: 'Failed to add target',
        description: err.message,
        variant: 'destructive',
      })
    },
  })

  const handleSave = () => {
    if (!trimmedName) {
      toast({
        title: 'Project name is required',
        description: 'Please provide a project name before saving.',
        variant: 'destructive',
      })
      return
    }

    const updates: { name?: string; framework?: string } = {}
    if (nameChanged) updates.name = trimmedName
    if (frameworkChanged) updates.framework = framework

    if (Object.keys(updates).length === 0) return
    updateProjectMutation.mutate(updates)
  }

  return (
    <div className="min-h-screen bg-background">
      <div className="p-6 max-w-4xl mx-auto space-y-6">
        <div className="flex items-start justify-between gap-4">
          <div className="space-y-2">
            <Link
              to="/projects/$projectId"
              params={{ projectId: String(project.id) }}
              className="inline-flex items-center gap-1 text-sm text-muted-foreground hover:text-foreground"
            >
              <ArrowLeft className="h-4 w-4" />
              Back to Setup Guide
            </Link>
            <div>
              <h1 className="text-2xl font-bold">Project Settings</h1>
              <p className="text-sm text-muted-foreground">Manage project details and platform configuration.</p>
            </div>
          </div>
          <Button
            onClick={handleSave}
            disabled={!trimmedName || (!nameChanged && !frameworkChanged) || updateProjectMutation.isPending}
          >
            {updateProjectMutation.isPending ? (
              <Loader2 className="h-4 w-4 animate-spin" />
            ) : (
              <Save className="h-4 w-4" />
            )}
            Save Changes
          </Button>
        </div>

        <Card>
          <CardHeader>
            <CardTitle>General</CardTitle>
            <CardDescription>Update your project name and framework.</CardDescription>
          </CardHeader>
          <CardContent className="space-y-6">
            <div className="space-y-2">
              <Label htmlFor="project-name">Project Name</Label>
              <Input
                id="project-name"
                value={name}
                onChange={(e) => setName(e.target.value)}
                placeholder="My Project"
              />
            </div>

            <div className="space-y-3">
              <div>
                <Label className="text-base">Platform / Framework</Label>
                <p className="text-sm text-muted-foreground mt-1">
                  Changing platform does not rotate or invalidate existing DSNs.
                </p>
              </div>

              <div className="flex flex-wrap gap-1">
                {platformFilterTabs.map((tab) => (
                  <Button
                    key={tab.id}
                    variant={platformFilter === tab.id ? 'default' : 'outline'}
                    size="sm"
                    onClick={() => setPlatformFilter(tab.id)}
                  >
                    {tab.label}
                  </Button>
                ))}
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
                            style={{ backgroundColor: platform.color }}
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

            {isMultiplatformFramework && (
              <div className="space-y-3">
                <div>
                  <Label className="text-base">Target Platforms</Label>
                  <p className="text-sm text-muted-foreground mt-1">
                    Multiplatform frameworks use one DSN per target.
                  </p>
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
                                  style={{ backgroundColor: targetInfo?.color }}
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
                                  style={{ backgroundColor: targetInfo?.color }}
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

        <Card className="border-destructive/40">
          <CardHeader>
            <CardTitle className="flex items-center gap-2 text-destructive">
              <AlertTriangle className="h-5 w-5" />
              Danger Zone
            </CardTitle>
            <CardDescription>Delete this project and all related data permanently.</CardDescription>
          </CardHeader>
          <CardContent className="flex flex-col sm:flex-row sm:items-center sm:justify-between gap-3">
            <p className="text-sm text-muted-foreground">
              This action cannot be undone. Events, issues, releases, and DSNs for this project will be removed.
            </p>
            <Button variant="destructive" onClick={() => setDeleteDialogOpen(true)}>
              <Trash2 className="h-4 w-4" />
              Delete Project
            </Button>
          </CardContent>
        </Card>
      </div>

      <Dialog open={deleteDialogOpen} onOpenChange={setDeleteDialogOpen}>
        <DialogContent>
          <DialogHeader>
            <DialogTitle>Delete project?</DialogTitle>
            <DialogDescription>
              This will permanently delete <strong>{project.name}</strong> and all associated data.
            </DialogDescription>
          </DialogHeader>
          <DialogFooter>
            <Button variant="outline" onClick={() => setDeleteDialogOpen(false)} disabled={deleteProjectMutation.isPending}>
              Cancel
            </Button>
            <Button
              variant="destructive"
              onClick={() => deleteProjectMutation.mutate()}
              disabled={deleteProjectMutation.isPending}
            >
              {deleteProjectMutation.isPending ? (
                <Loader2 className="h-4 w-4 animate-spin" />
              ) : (
                <Trash2 className="h-4 w-4" />
              )}
              Delete Project
            </Button>
          </DialogFooter>
        </DialogContent>
      </Dialog>
    </div>
  )
}
