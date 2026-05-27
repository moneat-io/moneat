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

import {createFileRoute, redirect, useNavigate} from '@tanstack/react-router'
import {useMutation, useQuery, useQueryClient} from '@tanstack/react-query'
import {api, type CreateStatusPageRequest} from '@/lib/api'
import {Button} from '@/components/ui/button'
import {Card, CardContent, CardDescription, CardFooter, CardHeader, CardTitle} from '@/components/ui/card'
import {Dialog, DialogContent, DialogDescription, DialogFooter, DialogHeader, DialogTitle} from '@/components/ui/dialog'
import {Input} from '@/components/ui/input'
import {Label} from '@/components/ui/label'
import {Textarea} from '@/components/ui/textarea'
import {Badge} from '@/components/ui/badge'
import {
    Activity,
    AlertTriangle,
    ArrowRight,
    Check,
    Copy,
    ExternalLink,
    Globe,
    Lock,
    Plus,
    Settings,
    Sparkles,
    Unlock
} from 'lucide-react'
import {useState} from 'react'
import {useToast} from '@/hooks/useToast'
import {useTimezone} from '@/hooks/useTimezone'
import {formatDate} from '@/lib/date-format'

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
  const { timezone } = useTimezone()
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
      <div className="px-4 py-4">
        <div className="flex items-center justify-center h-48">
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
        <div className="px-4 lg:px-6 py-3">
          <div className="flex flex-col gap-2 sm:flex-row sm:items-center sm:justify-between">
            <div className="flex items-center gap-2">
              <div className="flex items-center justify-center h-8 w-8 rounded-lg bg-primary/10 text-primary shrink-0">
                <Globe className="h-4 w-4" />
              </div>
              <div className="min-w-0">
                <h1 className="text-lg font-bold tracking-tight">Status Pages</h1>
                <p className="text-xs text-muted-foreground">
                  Public status pages for your services and monitors
                </p>
              </div>
            </div>
            <Button onClick={() => setCreateDialogOpen(true)} size="sm" className="gap-1.5 h-8">
              <Plus className="h-3.5 w-3.5" />
              Create Status Page
            </Button>
          </div>
        </div>
      </div>

      {/* Content */}
      <div className="px-4 lg:px-6 py-3">
        {statusPages.length === 0 ? (
          <Card className="border-dashed border-2">
            <CardContent className="flex flex-col items-center justify-center py-8">
              <div className="bg-primary/10 p-3 rounded-full mb-3">
                <Globe className="h-6 w-6 text-primary" />
              </div>
              <h3 className="text-lg font-semibold mb-1">Create your first status page</h3>
              <p className="text-muted-foreground text-center text-sm mb-4 max-w-lg leading-snug">
                Share uptime information with your users through a branded public status page.
              </p>
              <Button onClick={() => setCreateDialogOpen(true)} size="sm" className="gap-1.5 mb-4">
                <Plus className="h-3.5 w-3.5" />
                Create Status Page
              </Button>
              <div className="grid grid-cols-3 gap-2 max-w-md w-full">
                <div className="flex flex-col items-center text-center gap-1 p-2 rounded-lg bg-muted/30">
                  <Activity className="h-4 w-4 text-blue-500" />
                  <span className="text-[11px] font-medium">Real-time Status</span>
                </div>
                <div className="flex flex-col items-center text-center gap-1 p-2 rounded-lg bg-muted/30">
                  <AlertTriangle className="h-4 w-4 text-orange-500" />
                  <span className="text-[11px] font-medium">Incident Updates</span>
                </div>
                <div className="flex flex-col items-center text-center gap-1 p-2 rounded-lg bg-muted/30">
                  <Sparkles className="h-4 w-4 text-purple-500" />
                  <span className="text-[11px] font-medium">Custom Branding</span>
                </div>
              </div>
            </CardContent>
          </Card>
        ) : (
          <div className="grid gap-3 md:grid-cols-2 xl:grid-cols-3">
            {statusPages.map((page) => (
              <Card
                key={page.id}
                className="group transition-all duration-200 cursor-pointer overflow-hidden"
                onClick={() => navigate({to: '/status-pages/$pageId', params: {pageId: page.id}})}
              >
                {/* Color Bar */}
                <div className="h-1 w-full" style={{backgroundColor: page.primaryColor || '#3B82F6'}} />

                <CardHeader className="pb-1.5 pt-2.5 px-3">
                  <div className="flex items-start justify-between gap-2">
                    <div className="space-y-0.5 min-w-0 flex-1">
                      <CardTitle className="text-sm font-bold flex items-center gap-1.5 truncate">
                        {page.logoUrl && (
                          <img
                            src={page.logoUrl}
                            alt=""
                            className="h-5 w-5 rounded object-cover border bg-muted shrink-0"
                            onError={(e) => (e.currentTarget.style.display = 'none')}
                          />
                        )}
                        <span className="truncate">{page.name}</span>
                      </CardTitle>
                      <CardDescription className="line-clamp-2 text-[11px] min-h-[1.5rem]">
                        {page.description || 'No description provided'}
                      </CardDescription>
                    </div>
                    <Badge
                      variant={page.isPublic ? 'default' : 'secondary'}
                      className={`shrink-0 gap-0.5 text-[10px] px-1.5 py-0 ${page.isPublic ? 'bg-emerald-500/90 hover:bg-emerald-600 text-white' : ''}`}
                    >
                      {page.isPublic ? (
                        <><Unlock className="h-2.5 w-2.5" /> Public</>
                      ) : (
                        <><Lock className="h-2.5 w-2.5" /> Private</>
                      )}
                    </Badge>
                  </div>
                </CardHeader>

                <CardContent className="pb-1.5 px-3 space-y-1.5">
                  {/* URL Row */}
                  <div className="flex items-center gap-1.5 text-[11px] bg-muted/50 p-1.5 rounded-md border group-hover:bg-muted transition-colors">
                    <Globe className="h-3 w-3 text-muted-foreground shrink-0" />
                    <code className="flex-1 truncate text-xs">
                      moneat.io/s/{page.slug}
                    </code>
                    <Button
                      variant="ghost"
                      size="icon"
                      className="h-5 w-5 shrink-0"
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
                  <div className="flex items-center gap-2 text-[11px] text-muted-foreground">
                    <span>Created {formatDate(new Date(page.createdAt), timezone)}</span>
                  </div>
                </CardContent>

                <CardFooter className="pt-1.5 px-3 pb-2 border-t bg-muted/20 flex gap-1.5">
                  <Button
                    variant="default"
                    size="sm"
                    className="flex-1 h-6 text-[11px]"
                    onClick={(e) => {
                      e.stopPropagation()
                      navigate({to: '/status-pages/$pageId', params: {pageId: page.id}})
                    }}
                  >
                    <Settings className="mr-1 h-3 w-3" />
                    Configure
                  </Button>
                  <Button
                    variant="outline"
                    size="sm"
                    className="flex-1 h-6 text-[11px]"
                    onClick={(e) => {
                      e.stopPropagation()
                      window.open(`/s/${page.slug}`, '_blank')
                    }}
                  >
                    <ExternalLink className="mr-1 h-3 w-3" />
                    View Page
                  </Button>
                </CardFooter>
              </Card>
            ))}

            {/* Create New Card */}
            <Card
              className="border-dashed border-2 hover:border-primary/50 hover:bg-accent/50 transition-all duration-200 cursor-pointer group flex flex-col items-center justify-center min-h-[120px]"
              onClick={() => setCreateDialogOpen(true)}
            >
              <CardContent className="flex flex-col items-center justify-center py-4 text-center">
                <div className="h-8 w-8 rounded-full bg-primary/10 flex items-center justify-center mb-1.5 group-hover:bg-primary/20 transition-colors">
                  <Plus className="h-4 w-4 text-primary" />
                </div>
                <p className="font-medium text-xs">Create Status Page</p>
                <p className="text-[11px] text-muted-foreground">Add a new public status page</p>
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
