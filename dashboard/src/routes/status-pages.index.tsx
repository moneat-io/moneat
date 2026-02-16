// Moneat - Mobile-First Error Monitoring Platform
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

import {createFileRoute, redirect, useNavigate} from '@tanstack/react-router'
import {useQuery, useMutation, useQueryClient} from '@tanstack/react-query'
import {api} from '@/lib/api'
import {Button} from '@/components/ui/button'
import {Card, CardContent, CardHeader, CardTitle, CardDescription, CardFooter} from '@/components/ui/card'
import {Dialog, DialogContent, DialogDescription, DialogFooter, DialogHeader, DialogTitle} from '@/components/ui/dialog'
import {Input} from '@/components/ui/input'
import {Label} from '@/components/ui/label'
import {Textarea} from '@/components/ui/textarea'
import {Badge} from '@/components/ui/badge'
import {Globe, Plus, Copy, Check, Settings, Activity, AlertTriangle, Lock, Unlock, ExternalLink, Sparkles, ArrowRight} from 'lucide-react'
import {useState} from 'react'
import {useToast} from '@/hooks/use-toast'
import {type CreateStatusPageRequest} from '@/lib/api'

export const Route = createFileRoute('/status-pages/')({
  beforeLoad: () => {
    if (!api.isAuthenticated()) {
      throw redirect({to: '/login'})
    }
  },
  component: StatusPagesListPage,
})

function StatusPagesListPage() {
  const navigate = useNavigate()
  const {toast} = useToast()
  const queryClient = useQueryClient()
  const [createDialogOpen, setCreateDialogOpen] = useState(false)
  const [copiedSlug, setCopiedSlug] = useState<string | null>(null)
  const [newPage, setNewPage] = useState<CreateStatusPageRequest>({
    name: '',
    slug: '',
    description: '',
  })

  const {data: statusPages = [], isLoading} = useQuery({
    queryKey: ['status-pages'],
    queryFn: () => api.getStatusPages(),
  })

  const createMutation = useMutation({
    mutationFn: (data: CreateStatusPageRequest) => api.createStatusPage(data),
    onSuccess: (page) => {
      queryClient.invalidateQueries({queryKey: ['status-pages']})
      setCreateDialogOpen(false)
      setNewPage({name: '', slug: '', description: ''})
      toast({
        title: 'Status page created',
        description: `${page.name} has been created successfully.`,
      })
      navigate({to: '/status-pages/$pageId', params: {pageId: page.id}})
    },
    onError: (error: Error) => {
      toast({
        title: 'Failed to create status page',
        description: error.message,
        variant: 'destructive',
      })
    },
  })

  const handleNameChange = (name: string) => {
    setNewPage({
      name,
      slug: name.toLowerCase().replace(/[^a-z0-9]+/g, '-').replace(/^-|-$/g, ''),
    })
  }

  const handleCreate = () => {
    if (!newPage.name || !newPage.slug) {
      toast({
        title: 'Missing required fields',
        description: 'Please provide a name for your status page.',
        variant: 'destructive',
      })
      return
    }
    createMutation.mutate(newPage)
  }

  const copyPublicUrl = (slug: string) => {
    const url = `${window.location.origin}/s/${slug}`
    navigator.clipboard.writeText(url)
    setCopiedSlug(slug)
    setTimeout(() => setCopiedSlug(null), 2000)
    toast({
      title: 'Link copied',
      description: 'Public status page URL copied to clipboard.',
    })
  }

  if (isLoading) {
    return (
      <div className="px-6 py-8">
        <div className="flex items-center justify-center h-64">
          <div className="flex flex-col items-center gap-3">
            <Globe className="h-8 w-8 animate-spin text-muted-foreground" />
            <p className="text-sm text-muted-foreground">Loading status pages...</p>
          </div>
        </div>
      </div>
    )
  }

  return (
    <div>
      {/* Page Header */}
      <div className="border-b bg-card/50">
        <div className="px-6 lg:px-8 py-6">
          <div className="flex flex-col gap-4 sm:flex-row sm:items-center sm:justify-between">
            <div className="flex items-center gap-4">
              <div className="flex items-center justify-center h-12 w-12 rounded-xl bg-primary/10 text-primary">
                <Globe className="h-6 w-6" />
              </div>
              <div>
                <h1 className="text-2xl font-bold tracking-tight">Status Pages</h1>
                <p className="text-sm text-muted-foreground mt-0.5">
                  Public status pages for your services and monitors
                </p>
              </div>
            </div>
            <Button onClick={() => setCreateDialogOpen(true)} size="lg">
              <Plus className="mr-2 h-4 w-4" />
              Create Status Page
            </Button>
          </div>
        </div>
      </div>

      {/* Content */}
      <div className="px-6 lg:px-8 py-6">
        {statusPages.length === 0 ? (
          <Card className="border-dashed border-2">
            <CardContent className="flex flex-col items-center justify-center py-20">
              <div className="bg-primary/10 p-5 rounded-full mb-6">
                <Globe className="h-10 w-10 text-primary" />
              </div>
              <h3 className="text-2xl font-semibold mb-3">Create your first status page</h3>
              <p className="text-muted-foreground text-center mb-8 max-w-lg leading-relaxed">
                Share uptime information with your users through a branded public status page. 
                Add monitors, post incidents, customize branding, and use your own domain.
              </p>
              <div className="flex flex-col sm:flex-row gap-3">
                <Button onClick={() => setCreateDialogOpen(true)} size="lg">
                  <Plus className="mr-2 h-4 w-4" />
                  Create Status Page
                </Button>
              </div>
              <div className="mt-10 grid grid-cols-1 sm:grid-cols-3 gap-6 max-w-2xl w-full">
                <div className="flex flex-col items-center text-center gap-2 p-4">
                  <div className="h-10 w-10 rounded-lg bg-blue-500/10 flex items-center justify-center">
                    <Activity className="h-5 w-5 text-blue-500" />
                  </div>
                  <span className="text-sm font-medium">Real-time Status</span>
                  <span className="text-xs text-muted-foreground">Live monitor updates</span>
                </div>
                <div className="flex flex-col items-center text-center gap-2 p-4">
                  <div className="h-10 w-10 rounded-lg bg-orange-500/10 flex items-center justify-center">
                    <AlertTriangle className="h-5 w-5 text-orange-500" />
                  </div>
                  <span className="text-sm font-medium">Incident Updates</span>
                  <span className="text-xs text-muted-foreground">Keep users informed</span>
                </div>
                <div className="flex flex-col items-center text-center gap-2 p-4">
                  <div className="h-10 w-10 rounded-lg bg-purple-500/10 flex items-center justify-center">
                    <Sparkles className="h-5 w-5 text-purple-500" />
                  </div>
                  <span className="text-sm font-medium">Custom Branding</span>
                  <span className="text-xs text-muted-foreground">Logo, colors & domain</span>
                </div>
              </div>
            </CardContent>
          </Card>
        ) : (
          <div className="grid gap-5 md:grid-cols-2 xl:grid-cols-3">
            {statusPages.map((page) => (
              <Card
                key={page.id}
                className="group hover:shadow-lg transition-all duration-200 cursor-pointer overflow-hidden"
                onClick={() => navigate({to: '/status-pages/$pageId', params: {pageId: page.id}})}
              >
                {/* Color Bar */}
                <div className="h-1.5 w-full" style={{backgroundColor: page.primaryColor || '#3B82F6'}} />

                <CardHeader className="pb-3">
                  <div className="flex items-start justify-between gap-3">
                    <div className="space-y-1 min-w-0 flex-1">
                      <CardTitle className="text-lg font-bold flex items-center gap-2 truncate">
                        {page.logoUrl && (
                          <img
                            src={page.logoUrl}
                            alt=""
                            className="h-6 w-6 rounded object-cover border bg-muted shrink-0"
                            onError={(e) => (e.currentTarget.style.display = 'none')}
                          />
                        )}
                        <span className="truncate">{page.name}</span>
                      </CardTitle>
                      <CardDescription className="line-clamp-2 min-h-[2.5rem]">
                        {page.description || 'No description provided'}
                      </CardDescription>
                    </div>
                    <Badge
                      variant={page.isPublic ? 'default' : 'secondary'}
                      className={`shrink-0 gap-1 ${page.isPublic ? 'bg-emerald-500/90 hover:bg-emerald-600 text-white' : ''}`}
                    >
                      {page.isPublic ? (
                        <><Unlock className="h-3 w-3" /> Public</>
                      ) : (
                        <><Lock className="h-3 w-3" /> Private</>
                      )}
                    </Badge>
                  </div>
                </CardHeader>

                <CardContent className="pb-3 space-y-3">
                  {/* URL Row */}
                  <div className="flex items-center gap-2 text-sm bg-muted/50 p-2.5 rounded-lg border group-hover:bg-muted transition-colors">
                    <Globe className="h-3.5 w-3.5 text-muted-foreground shrink-0" />
                    <code className="flex-1 truncate text-xs">
                      moneat.io/s/{page.slug}
                    </code>
                    <Button
                      variant="ghost"
                      size="icon"
                      className="h-6 w-6 shrink-0"
                      onClick={(e) => {
                        e.stopPropagation()
                        copyPublicUrl(page.slug)
                      }}
                    >
                      {copiedSlug === page.slug ? (
                        <Check className="h-3 w-3 text-green-600" />
                      ) : (
                        <Copy className="h-3 w-3" />
                      )}
                    </Button>
                  </div>

                  {/* Meta info */}
                  <div className="flex items-center gap-3 text-xs text-muted-foreground">
                    <span>Created {new Date(page.createdAt).toLocaleDateString()}</span>
                  </div>
                </CardContent>

                <CardFooter className="pt-3 border-t bg-muted/20 flex gap-2">
                  <Button
                    variant="default"
                    size="sm"
                    className="flex-1"
                    onClick={(e) => {
                      e.stopPropagation()
                      navigate({to: '/status-pages/$pageId', params: {pageId: page.id}})
                    }}
                  >
                    <Settings className="mr-2 h-3.5 w-3.5" />
                    Configure
                  </Button>
                  <Button
                    variant="outline"
                    size="sm"
                    className="flex-1"
                    onClick={(e) => {
                      e.stopPropagation()
                      window.open(`/s/${page.slug}`, '_blank')
                    }}
                  >
                    <ExternalLink className="mr-2 h-3.5 w-3.5" />
                    View Page
                  </Button>
                </CardFooter>
              </Card>
            ))}

            {/* Create New Card */}
            <Card
              className="border-dashed border-2 hover:border-primary/50 hover:bg-accent/50 transition-all duration-200 cursor-pointer group flex flex-col items-center justify-center min-h-[260px]"
              onClick={() => setCreateDialogOpen(true)}
            >
              <CardContent className="flex flex-col items-center justify-center py-8 text-center">
                <div className="h-12 w-12 rounded-full bg-primary/10 flex items-center justify-center mb-3 group-hover:bg-primary/20 transition-colors">
                  <Plus className="h-6 w-6 text-primary" />
                </div>
                <p className="font-medium text-sm">Create Status Page</p>
                <p className="text-xs text-muted-foreground mt-1">Add a new public status page</p>
              </CardContent>
            </Card>
          </div>
        )}
      </div>

      {/* Create Dialog */}
      <Dialog open={createDialogOpen} onOpenChange={setCreateDialogOpen}>
        <DialogContent className="sm:max-w-[500px]">
          <DialogHeader>
            <DialogTitle className="flex items-center gap-2">
              <Globe className="h-5 w-5 text-primary" />
              Create Status Page
            </DialogTitle>
            <DialogDescription>
              Create a new public status page for your services and uptime monitors.
            </DialogDescription>
          </DialogHeader>

          <div className="space-y-6 py-4">
            <div className="space-y-2">
              <Label htmlFor="name">Page Name</Label>
              <Input
                id="name"
                placeholder="e.g. Acme Inc. Status"
                value={newPage.name}
                onChange={(e) => handleNameChange(e.target.value)}
              />
            </div>

            <div className="space-y-2">
              <Label htmlFor="slug">URL Slug</Label>
              <div className="flex items-center gap-2">
                <div className="bg-muted px-3 py-2 rounded-md border text-sm text-muted-foreground whitespace-nowrap">
                  moneat.io/s/
                </div>
                <Input
                  id="slug"
                  placeholder="acme-inc"
                  value={newPage.slug}
                  onChange={(e) => setNewPage({...newPage, slug: e.target.value})}
                  className="font-mono"
                />
              </div>
              <p className="text-xs text-muted-foreground">
                This will be the public URL for your status page.
              </p>
            </div>

            <div className="space-y-2">
              <Label htmlFor="description">Description (Optional)</Label>
              <Textarea
                id="description"
                placeholder="Brief description of what this status page covers..."
                value={newPage.description}
                onChange={(e) => setNewPage({...newPage, description: e.target.value})}
                className="resize-none"
                rows={3}
              />
            </div>
          </div>

          <DialogFooter>
            <Button variant="outline" onClick={() => setCreateDialogOpen(false)}>
              Cancel
            </Button>
            <Button onClick={handleCreate} disabled={createMutation.isPending}>
              {createMutation.isPending ? 'Creating...' : 'Create Page'}
              {!createMutation.isPending && <ArrowRight className="ml-2 h-4 w-4" />}
            </Button>
          </DialogFooter>
        </DialogContent>
      </Dialog>
    </div>
  )
}
