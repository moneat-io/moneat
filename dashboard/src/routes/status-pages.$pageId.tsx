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
import {useMutation, useQuery, useQueryClient} from '@tanstack/react-query'
import {
    api,
    type CreateIncidentRequest,
    type IncidentUpdate,
    type MonitorAssignment,
    type StatusPageDetail,
    type StatusPageIncident,
    type UpdateStatusPageRequest
} from '@/lib/api'
import {Button} from '@/components/ui/button'
import {Card, CardContent, CardDescription, CardHeader, CardTitle} from '@/components/ui/card'
import {Input} from '@/components/ui/input'
import {Label} from '@/components/ui/label'
import {Textarea} from '@/components/ui/textarea'
import {Tabs, TabsContent, TabsList, TabsTrigger} from '@/components/ui/tabs'
import {Switch} from '@/components/ui/switch'
import {Select, SelectContent, SelectItem, SelectTrigger, SelectValue} from '@/components/ui/select'
import {Dialog, DialogContent, DialogDescription, DialogFooter, DialogHeader, DialogTitle} from '@/components/ui/dialog'
import {Badge} from '@/components/ui/badge'
import {Separator} from '@/components/ui/separator'
import {Tooltip, TooltipContent, TooltipTrigger} from '@/components/ui/tooltip'
import type {LucideIcon} from 'lucide-react'
import {
    Activity,
    AlertCircle,
    AlertTriangle,
    ArrowLeft,
    Check,
    CheckCircle2,
    CircleDot,
    Clock,
    Copy,
    ExternalLink,
    Eye,
    EyeOff,
    Globe,
    Info,
    Layout,
    Link2,
    Lock,
    Palette,
    Plus,
    RefreshCw,
    Save,
    Settings,
    ShieldAlert,
    ShieldCheck,
    Trash2,
    Unlock,
    Wrench,
    XCircle,
    Zap,
} from 'lucide-react'
import {useState} from 'react'
import {useToast} from '@/hooks/use-toast'
import {StatusPagePreview} from '@/components/status-page-preview'
import {useTimezone} from '@/hooks/useTimezone'
import {formatDateTime} from '@/lib/date-format'

export const Route = createFileRoute('/status-pages/$pageId')({
  beforeLoad: () => {
    if (!api.isAuthenticated()) {
      throw redirect({to: '/login'})
    }
  },
  component: StatusPageDetailPage,
})

function StatusPageDetailPage() {
  const {pageId} = Route.useParams()
  const navigate = useNavigate()
  const {toast} = useToast()
  const queryClient = useQueryClient()
  const [activeTab, setActiveTab] = useState('overview')
  const [deleteDialogOpen, setDeleteDialogOpen] = useState(false)
  const [copiedUrl, setCopiedUrl] = useState(false)
  const [showPreview, setShowPreview] = useState(true)

  const {data: statusPage, isLoading} = useQuery({
    queryKey: ['status-page', pageId],
    queryFn: () => api.getStatusPage(pageId),
  })

  const updateMutation = useMutation({
    mutationFn: (data: UpdateStatusPageRequest) => api.updateStatusPage(pageId, data),
    onSuccess: () => {
      queryClient.invalidateQueries({queryKey: ['status-page', pageId]})
      toast({title: 'Status page updated', description: 'Your changes have been saved.'})
    },
    onError: (error: Error) => {
      toast({
        title: 'Failed to update',
        description: error.message,
        variant: 'destructive',
      })
    },
  })

  const deleteMutation = useMutation({
    mutationFn: () => api.deleteStatusPage(pageId),
    onSuccess: () => {
      toast({title: 'Status page deleted'})
      navigate({to: '/status-pages'})
    },
    onError: (error: Error) => {
      toast({
        title: 'Failed to delete',
        description: error.message,
        variant: 'destructive',
      })
    },
  })

  const copyPublicUrl = () => {
    if (!statusPage) return
    const url = `${window.location.origin}/s/${statusPage.slug}`
    navigator.clipboard.writeText(url)
    setCopiedUrl(true)
    setTimeout(() => setCopiedUrl(false), 2000)
    toast({title: 'Link copied'})
  }

  if (isLoading) {
    return (
      <div className="px-6 lg:px-8 py-8">
        <div className="flex items-center justify-center h-64">
          <div className="flex flex-col items-center gap-3">
            <Globe className="h-8 w-8 animate-spin text-muted-foreground" />
            <p className="text-sm text-muted-foreground">Loading status page...</p>
          </div>
        </div>
      </div>
    )
  }

  if (!statusPage) {
    return (
      <div className="px-6 lg:px-8 py-8">
        <Card>
          <CardContent className="py-16 text-center">
            <AlertTriangle className="h-12 w-12 mx-auto text-muted-foreground mb-4" />
            <h3 className="text-lg font-semibold">Status page not found</h3>
            <p className="text-sm text-muted-foreground mt-2">The requested status page could not be found.</p>
            <Button variant="outline" className="mt-4" onClick={() => navigate({to: '/status-pages'})}>
              <ArrowLeft className="mr-2 h-4 w-4" />
              Back to Status Pages
            </Button>
          </CardContent>
        </Card>
      </div>
    )
  }

  return (
    <div>
      {/* Header */}
      <div className="border-b bg-card/50">
        <div className="px-6 lg:px-8 py-6">
          {/* Breadcrumb */}
          <div className="flex items-center gap-2 mb-4 text-sm">
            <Button variant="ghost" size="sm" className="text-muted-foreground hover:text-foreground -ml-2" onClick={() => navigate({to: '/status-pages'})}>
              <ArrowLeft className="mr-1.5 h-3.5 w-3.5" />
              Status Pages
            </Button>
            <span className="text-muted-foreground">/</span>
            <span className="text-foreground font-medium truncate">{statusPage.name}</span>
          </div>

          <div className="flex flex-col gap-4 lg:flex-row lg:items-start lg:justify-between">
            <div className="flex items-start gap-4">
              {/* Page icon with color */}
              <div
                className="flex items-center justify-center h-14 w-14 rounded-xl shrink-0"
                style={{backgroundColor: `${statusPage.primaryColor}20`}}
              >
                <Globe className="h-7 w-7" style={{color: statusPage.primaryColor}} />
              </div>
              <div className="min-w-0">
                <div className="flex items-center gap-3 flex-wrap">
                  <h1 className="text-2xl font-bold tracking-tight">{statusPage.name}</h1>
                  <Badge
                    variant={statusPage.isPublic ? 'default' : 'secondary'}
                    className={`gap-1 ${statusPage.isPublic ? 'bg-emerald-500/90 hover:bg-emerald-600 text-white' : ''}`}
                  >
                    {statusPage.isPublic ? (
                      <><Unlock className="h-3 w-3" /> Public</>
                    ) : (
                      <><Lock className="h-3 w-3" /> Private</>
                    )}
                  </Badge>
                </div>
                <div className="flex items-center gap-2 mt-2">
                  <div className="flex items-center gap-2 text-sm bg-muted/60 px-3 py-1.5 rounded-lg border">
                    <Globe className="h-3.5 w-3.5 text-muted-foreground" />
                    <code className="text-xs">moneat.io/s/{statusPage.slug}</code>
                  </div>
                  <Tooltip>
                    <TooltipTrigger asChild>
                      <Button variant="ghost" size="icon" className="h-8 w-8" onClick={copyPublicUrl}>
                        {copiedUrl ? (
                          <Check className="h-3.5 w-3.5 text-green-600" />
                        ) : (
                          <Copy className="h-3.5 w-3.5" />
                        )}
                      </Button>
                    </TooltipTrigger>
                    <TooltipContent>Copy URL</TooltipContent>
                  </Tooltip>
                  <Tooltip>
                    <TooltipTrigger asChild>
                      <Button variant="ghost" size="icon" className="h-8 w-8" onClick={() => window.open(`/s/${statusPage.slug}`, '_blank')}>
                        <ExternalLink className="h-3.5 w-3.5" />
                      </Button>
                    </TooltipTrigger>
                    <TooltipContent>Open public page</TooltipContent>
                  </Tooltip>
                </div>
              </div>
            </div>
            <Button variant="outline" className="text-destructive hover:text-destructive hover:bg-destructive/10 border-destructive/30" onClick={() => setDeleteDialogOpen(true)}>
              <Trash2 className="mr-2 h-4 w-4" />
              Delete Page
            </Button>
          </div>

          {/* Quick Stats */}
          <div className="grid grid-cols-2 sm:grid-cols-4 gap-3 mt-6">
            <div className="bg-background rounded-lg border p-3 flex items-center gap-3">
              <div className="h-9 w-9 rounded-lg bg-blue-500/10 flex items-center justify-center shrink-0">
                <Activity className="h-4 w-4 text-blue-500" />
              </div>
              <div>
                <p className="text-xl font-bold">{statusPage.monitors.length}</p>
                <p className="text-xs text-muted-foreground">Monitors</p>
              </div>
            </div>
            <div className="bg-background rounded-lg border p-3 flex items-center gap-3">
              <div className="h-9 w-9 rounded-lg bg-orange-500/10 flex items-center justify-center shrink-0">
                <AlertTriangle className="h-4 w-4 text-orange-500" />
              </div>
              <div>
                <p className="text-xl font-bold">0</p>
                <p className="text-xs text-muted-foreground">Active Incidents</p>
              </div>
            </div>
            <div className="bg-background rounded-lg border p-3 flex items-center gap-3">
              <div className="h-9 w-9 rounded-lg bg-purple-500/10 flex items-center justify-center shrink-0">
                <Link2 className="h-4 w-4 text-purple-500" />
              </div>
              <div>
                <p className="text-xl font-bold">{statusPage.customDomains.length}</p>
                <p className="text-xs text-muted-foreground">Custom Domains</p>
              </div>
            </div>
            <div className="bg-background rounded-lg border p-3 flex items-center gap-3">
              <div className="h-9 w-9 rounded-lg bg-emerald-500/10 flex items-center justify-center shrink-0">
                {statusPage.showUptimeHistory ? (
                  <Eye className="h-4 w-4 text-emerald-500" />
                ) : (
                  <EyeOff className="h-4 w-4 text-muted-foreground" />
                )}
              </div>
              <div>
                <p className="text-xl font-bold">{statusPage.historyDays}d</p>
                <p className="text-xs text-muted-foreground">History Window</p>
              </div>
            </div>
          </div>
        </div>
      </div>

      {/* Tabs Content */}
      <div className="px-6 lg:px-8 py-6">
        <Tabs value={activeTab} onValueChange={setActiveTab}>
          <div className="flex items-center justify-between mb-6">
            <TabsList className="h-11">
              <TabsTrigger value="overview" className="gap-2 px-4">
                <Layout className="h-4 w-4" />
                Overview
              </TabsTrigger>
              <TabsTrigger value="monitors" className="gap-2 px-4">
                <Activity className="h-4 w-4" />
                Monitors
                {statusPage.monitors.length > 0 && (
                  <Badge variant="secondary" className="ml-1 h-5 px-1.5 text-[10px]">
                    {statusPage.monitors.length}
                  </Badge>
                )}
              </TabsTrigger>
              <TabsTrigger value="incidents" className="gap-2 px-4">
                <AlertCircle className="h-4 w-4" />
                Incidents
              </TabsTrigger>
              <TabsTrigger value="domains" className="gap-2 px-4">
                <Link2 className="h-4 w-4" />
                Domains
                {statusPage.customDomains.length > 0 && (
                  <Badge variant="secondary" className="ml-1 h-5 px-1.5 text-[10px]">
                    {statusPage.customDomains.length}
                  </Badge>
                )}
              </TabsTrigger>
            </TabsList>
            {activeTab === 'overview' && (
              <Button
                variant="outline"
                size="sm"
                onClick={() => setShowPreview(!showPreview)}
              >
                {showPreview ? (
                  <>
                    <EyeOff className="mr-2 h-4 w-4" />
                    Hide Preview
                  </>
                ) : (
                  <>
                    <Eye className="mr-2 h-4 w-4" />
                    Show Preview
                  </>
                )}
              </Button>
            )}
          </div>

          <TabsContent value="overview">
            <OverviewTab statusPage={statusPage} onUpdate={(data) => updateMutation.mutate(data)} isSaving={updateMutation.isPending} showPreview={showPreview} />
          </TabsContent>

          <TabsContent value="monitors">
            <MonitorsTab pageId={pageId} statusPage={statusPage} />
          </TabsContent>

          <TabsContent value="incidents">
            <IncidentsTab pageId={pageId} />
          </TabsContent>

          <TabsContent value="domains">
            <DomainsTab pageId={pageId} statusPage={statusPage} />
          </TabsContent>
        </Tabs>
      </div>

      {/* Delete Dialog */}
      <Dialog open={deleteDialogOpen} onOpenChange={setDeleteDialogOpen}>
        <DialogContent>
          <DialogHeader>
            <DialogTitle className="flex items-center gap-2 text-destructive">
              <AlertTriangle className="h-5 w-5" />
              Delete Status Page
            </DialogTitle>
            <DialogDescription>
              Are you sure you want to delete <strong>"{statusPage.name}"</strong>? This will remove all associated monitors, incidents, and custom domains. This action cannot be undone.
            </DialogDescription>
          </DialogHeader>
          <DialogFooter>
            <Button variant="outline" onClick={() => setDeleteDialogOpen(false)}>
              Cancel
            </Button>
            <Button variant="destructive" onClick={() => deleteMutation.mutate()} disabled={deleteMutation.isPending}>
              {deleteMutation.isPending ? 'Deleting...' : 'Delete Permanently'}
            </Button>
          </DialogFooter>
        </DialogContent>
      </Dialog>
    </div>
  )
}

// ==========================================
// OVERVIEW TAB
// ==========================================

function OverviewTab({statusPage, onUpdate, isSaving, showPreview}: {statusPage: StatusPageDetail; onUpdate: (data: UpdateStatusPageRequest) => void; isSaving?: boolean; showPreview: boolean}) {
  const [formData, setFormData] = useState({
    name: statusPage.name,
    description: statusPage.description || '',
    logoUrl: statusPage.logoUrl || '',
    faviconUrl: statusPage.faviconUrl || '',
    primaryColor: statusPage.primaryColor,
    darkMode: statusPage.darkMode,
    showUptimeHistory: statusPage.showUptimeHistory,
    historyDays: statusPage.historyDays,
    isPublic: statusPage.isPublic,
  })

  const handleSave = () => {
    onUpdate(formData)
  }

  // Convert statusPage monitors to preview format
  const previewMonitors = statusPage.monitors.map(monitor => ({
    name: monitor.monitorName,
    displayName: monitor.displayName,
    status: 'operational',
    uptimePercentage: 99.95,
    uptimeHistory: Array.from({length: formData.historyDays}, (_, i) => ({
      date: new Date(Date.now() - (formData.historyDays - i) * 24 * 60 * 60 * 1000).toISOString(),
      uptime: 99 + Math.random(),
    })),
  }))

  const hasChanges = JSON.stringify(formData) !== JSON.stringify({
    name: statusPage.name,
    description: statusPage.description || '',
    logoUrl: statusPage.logoUrl || '',
    faviconUrl: statusPage.faviconUrl || '',
    primaryColor: statusPage.primaryColor,
    darkMode: statusPage.darkMode,
    showUptimeHistory: statusPage.showUptimeHistory,
    historyDays: statusPage.historyDays,
    isPublic: statusPage.isPublic,
  })

  return (
    <div className="space-y-6">
      {/* Split View Container */}
      <div className={`grid gap-6 ${showPreview ? 'lg:grid-cols-2' : 'lg:grid-cols-1'}`}>
        {/* Left Column: Form Fields */}
        <div className="space-y-6">
          {/* Basic Information */}
          <Card>
            <CardHeader>
              <CardTitle className="flex items-center gap-2 text-base">
                <div className="h-8 w-8 rounded-lg bg-blue-500/10 flex items-center justify-center">
                  <Layout className="h-4 w-4 text-blue-500" />
                </div>
                Basic Information
              </CardTitle>
              <CardDescription>Configure your status page name and description</CardDescription>
            </CardHeader>
            <CardContent className="space-y-4">
              <div className="space-y-2">
                <Label htmlFor="name">Page Name</Label>
                <Input
                  id="name"
                  value={formData.name}
                  onChange={(e) => setFormData({...formData, name: e.target.value})}
                />
              </div>
              <div className="space-y-2">
                <Label htmlFor="description">Description</Label>
                <Textarea
                  id="description"
                  placeholder="Optional description for your status page"
                  value={formData.description}
                  onChange={(e) => setFormData({...formData, description: e.target.value})}
                  rows={4}
                />
              </div>
            </CardContent>
          </Card>

          {/* Settings */}
          <Card>
            <CardHeader>
              <CardTitle className="flex items-center gap-2 text-base">
                <div className="h-8 w-8 rounded-lg bg-slate-500/10 flex items-center justify-center">
                  <Settings className="h-4 w-4 text-slate-500" />
                </div>
                Settings
              </CardTitle>
              <CardDescription>Configure display options and visibility</CardDescription>
            </CardHeader>
            <CardContent className="space-y-6">
              <div className="flex items-center justify-between">
                <div className="space-y-0.5">
                  <Label htmlFor="isPublic" className="text-base flex items-center gap-2">
                    {formData.isPublic ? <Unlock className="h-4 w-4 text-emerald-500" /> : <Lock className="h-4 w-4 text-muted-foreground" />}
                    Public Access
                  </Label>
                  <p className="text-sm text-muted-foreground">Allow anyone to view this status page</p>
                </div>
                <Switch
                  id="isPublic"
                  checked={formData.isPublic}
                  onCheckedChange={(checked) => setFormData({...formData, isPublic: checked})}
                />
              </div>
              <Separator />
              <div className="flex items-center justify-between">
                <div className="space-y-0.5">
                  <Label htmlFor="showHistory" className="text-base flex items-center gap-2">
                    <Clock className="h-4 w-4 text-blue-500" />
                    Show Uptime History
                  </Label>
                  <p className="text-sm text-muted-foreground">Display uptime bar charts on public page</p>
                </div>
                <Switch
                  id="showHistory"
                  checked={formData.showUptimeHistory}
                  onCheckedChange={(checked) => setFormData({...formData, showUptimeHistory: checked})}
                />
              </div>
              {formData.showUptimeHistory && (
                <div className="space-y-2 pt-2 pl-6 border-l-2 border-blue-500/20">
                  <Label htmlFor="historyDays">History Days</Label>
                  <div className="flex items-center gap-2">
                    <Input
                      id="historyDays"
                      type="number"
                      min="1"
                      max="365"
                      value={formData.historyDays}
                      onChange={(e) => setFormData({...formData, historyDays: parseInt(e.target.value) || 90})}
                      className="w-24"
                    />
                    <span className="text-sm text-muted-foreground">days</span>
                  </div>
                </div>
              )}
            </CardContent>
          </Card>

          {/* Branding */}
          <Card>
            <CardHeader>
              <CardTitle className="flex items-center gap-2 text-base">
                <div className="h-8 w-8 rounded-lg bg-purple-500/10 flex items-center justify-center">
                  <Palette className="h-4 w-4 text-purple-500" />
                </div>
                Branding
              </CardTitle>
              <CardDescription>Customize the look and feel of your status page</CardDescription>
            </CardHeader>
            <CardContent className="space-y-6">
              <div className="space-y-4">
                <div className="space-y-2">
                  <Label htmlFor="logoUrl">Logo URL</Label>
                  <div className="flex gap-2">
                    <Input
                      id="logoUrl"
                      placeholder="https://example.com/logo.png"
                      value={formData.logoUrl}
                      onChange={(e) => setFormData({...formData, logoUrl: e.target.value})}
                    />
                    {formData.logoUrl && (
                      <div className="h-10 w-10 border rounded-lg flex items-center justify-center bg-muted shrink-0 overflow-hidden">
                        <img src={formData.logoUrl} alt="Logo" className="h-full w-full object-contain" onError={(e) => e.currentTarget.style.display = 'none'} />
                      </div>
                    )}
                  </div>
                </div>
                <div className="space-y-2">
                  <Label htmlFor="faviconUrl">Favicon URL</Label>
                  <div className="flex gap-2">
                    <Input
                      id="faviconUrl"
                      placeholder="https://example.com/favicon.ico"
                      value={formData.faviconUrl}
                      onChange={(e) => setFormData({...formData, faviconUrl: e.target.value})}
                    />
                    {formData.faviconUrl && (
                      <div className="h-10 w-10 border rounded-lg flex items-center justify-center bg-muted shrink-0 overflow-hidden">
                        <img src={formData.faviconUrl} alt="Favicon" className="h-6 w-6 object-contain" onError={(e) => e.currentTarget.style.display = 'none'} />
                      </div>
                    )}
                  </div>
                </div>
              </div>

              <Separator />

              <div className="space-y-4">
                <div className="space-y-2">
                  <Label htmlFor="primaryColor">Primary Color</Label>
                  <div className="flex gap-2">
                    <div className="relative">
                      <Input
                        id="primaryColor"
                        type="color"
                        value={formData.primaryColor}
                        onChange={(e) => setFormData({...formData, primaryColor: e.target.value})}
                        className="w-12 h-10 p-1 cursor-pointer"
                      />
                    </div>
                    <Input
                      value={formData.primaryColor}
                      onChange={(e) => setFormData({...formData, primaryColor: e.target.value})}
                      placeholder="#3B82F6"
                      className="flex-1 font-mono"
                    />
                  </div>
                  <p className="text-xs text-muted-foreground">Used for links, buttons, and accents.</p>
                </div>

                <div className="flex items-center justify-between pt-2">
                  <div className="space-y-0.5">
                    <Label htmlFor="darkMode" className="text-base">Dark Mode</Label>
                    <p className="text-sm text-muted-foreground">Use dark theme by default</p>
                  </div>
                  <Switch
                    id="darkMode"
                    checked={formData.darkMode}
                    onCheckedChange={(checked) => setFormData({...formData, darkMode: checked})}
                  />
                </div>
              </div>
            </CardContent>
          </Card>

          {/* Save Button */}
          <div className={`flex items-center justify-between sticky bottom-6 p-4 border rounded-lg shadow-sm transition-colors ${hasChanges ? 'bg-primary/5 border-primary/20' : 'bg-background/80 backdrop-blur-sm'}`}>
            {hasChanges && (
              <p className="text-sm text-muted-foreground flex items-center gap-2">
                <CircleDot className="h-3.5 w-3.5 text-primary" />
                You have unsaved changes
              </p>
            )}
            {!hasChanges && (
              <p className="text-sm text-muted-foreground flex items-center gap-2">
                <Check className="h-3.5 w-3.5 text-emerald-500" />
                All changes saved
              </p>
            )}
            <Button onClick={handleSave} size="lg" disabled={!hasChanges || isSaving}>
              {isSaving ? (
                <>
                  <RefreshCw className="mr-2 h-4 w-4 animate-spin" />
                  Saving...
                </>
              ) : (
                <>
                  <Save className="mr-2 h-4 w-4" />
                  Save Changes
                </>
              )}
            </Button>
          </div>
        </div>

        {/* Right Column: Live Preview */}
        {showPreview && (
          <div className="lg:sticky lg:top-6 lg:self-start">
            <Card>
              <CardHeader className="pb-3">
                <CardTitle className="flex items-center gap-2 text-base">
                  <div className="h-8 w-8 rounded-lg bg-emerald-500/10 flex items-center justify-center">
                    <Eye className="h-4 w-4 text-emerald-500" />
                  </div>
                  Live Preview
                </CardTitle>
                <CardDescription>See your changes in real-time</CardDescription>
              </CardHeader>
              <CardContent className="p-0">
                <div className="border rounded-lg overflow-hidden bg-white mx-4 mb-4">
                  <div className="h-[600px] overflow-y-auto">
                    <StatusPagePreview
                      name={formData.name}
                      description={formData.description}
                      logoUrl={formData.logoUrl}
                      primaryColor={formData.primaryColor}
                      darkMode={formData.darkMode}
                      showUptimeHistory={formData.showUptimeHistory}
                      historyDays={formData.historyDays}
                      monitors={previewMonitors}
                    />
                  </div>
                </div>
              </CardContent>
            </Card>
          </div>
        )}
      </div>
    </div>
  )
}

// ==========================================
// MONITORS TAB
// ==========================================

function MonitorsTab({pageId, statusPage}: {pageId: string; statusPage: StatusPageDetail}) {
  const {toast} = useToast()
  const queryClient = useQueryClient()
  const [addDialogOpen, setAddDialogOpen] = useState(false)
  const [selectedMonitors, setSelectedMonitors] = useState<string[]>([])

  const {data: allMonitors = []} = useQuery({
    queryKey: ['uptime-monitors'],
    queryFn: () => api.getUptimeMonitors(),
  })

  const addMonitorsMutation = useMutation({
    mutationFn: (assignments: MonitorAssignment[]) => api.addMonitorsToStatusPage(pageId, assignments),
    onSuccess: () => {
      queryClient.invalidateQueries({queryKey: ['status-page', pageId]})
      setAddDialogOpen(false)
      setSelectedMonitors([])
      toast({title: 'Monitors updated', description: 'Monitor assignments have been saved.'})
    },
    onError: (error: Error) => {
      toast({
        title: 'Failed to update monitors',
        description: error.message,
        variant: 'destructive',
      })
    },
  })

  const removeMonitorMutation = useMutation({
    mutationFn: (monitorId: string) => api.removeMonitorFromStatusPage(pageId, monitorId),
    onSuccess: () => {
      queryClient.invalidateQueries({queryKey: ['status-page', pageId]})
      toast({title: 'Monitor removed'})
    },
    onError: (error: Error) => {
      toast({
        title: 'Failed to remove monitor',
        description: error.message,
        variant: 'destructive',
      })
    },
  })

  const handleSaveMonitors = () => {
    const existingAssignments: MonitorAssignment[] = statusPage.monitors.map((monitor, index) => ({
      monitorId: monitor.monitorId,
      displayName: monitor.displayName,
      sortOrder: index,
    }))

    const newAssignments: MonitorAssignment[] = selectedMonitors.map((monitorId, index) => ({
      monitorId,
      sortOrder: existingAssignments.length + index,
    }))

    const allAssignments = [...existingAssignments, ...newAssignments]
    addMonitorsMutation.mutate(allAssignments)
  }

  const availableMonitors = allMonitors.filter(
    (m) => !statusPage.monitors.some((sm) => sm.monitorId === m.id)
  )

  return (
    <div className="space-y-6">
      <Card>
        <CardHeader>
          <div className="flex items-center justify-between">
            <div className="flex items-center gap-3">
              <div className="h-10 w-10 rounded-lg bg-blue-500/10 flex items-center justify-center">
                <Activity className="h-5 w-5 text-blue-500" />
              </div>
              <div>
                <CardTitle>Monitors</CardTitle>
                <CardDescription>Select which uptime monitors appear on your status page</CardDescription>
              </div>
            </div>
            <Button onClick={() => setAddDialogOpen(true)}>
              <Plus className="mr-2 h-4 w-4" />
              Add Monitors
            </Button>
          </div>
        </CardHeader>
        <CardContent>
          {statusPage.monitors.length === 0 ? (
            <div className="text-center py-16 border-2 border-dashed rounded-lg">
              <div className="bg-blue-500/10 h-14 w-14 rounded-full flex items-center justify-center mx-auto mb-4">
                <Activity className="h-7 w-7 text-blue-500" />
              </div>
              <h3 className="text-lg font-semibold mb-2">No monitors added</h3>
              <p className="text-muted-foreground mb-6 max-w-sm mx-auto">
                Add uptime monitors to display their status on your public page. Visitors will see real-time availability.
              </p>
              <Button onClick={() => setAddDialogOpen(true)}>
                <Plus className="mr-2 h-4 w-4" />
                Add Monitors
              </Button>
            </div>
          ) : (
            <div className="space-y-2">
              {statusPage.monitors.map((monitor, index) => (
                <div key={monitor.id} className="flex items-center justify-between p-4 border rounded-lg bg-card hover:bg-accent/5 transition-colors group">
                  <div className="flex items-center gap-4 min-w-0">
                    <div className="flex items-center justify-center h-9 w-9 rounded-lg bg-muted text-muted-foreground font-mono text-xs font-bold shrink-0">
                      {index + 1}
                    </div>
                    <div className="min-w-0">
                      <p className="font-medium flex items-center gap-2">
                        <Zap className="h-3.5 w-3.5 text-emerald-500 shrink-0" />
                        <span className="truncate">{monitor.displayName || monitor.monitorName}</span>
                      </p>
                      <p className="text-sm text-muted-foreground flex items-center gap-2 mt-0.5">
                        <span className="truncate">{monitor.url}</span>
                        {monitor.monitorName !== monitor.displayName && monitor.displayName && (
                          <Badge variant="outline" className="text-[10px] px-1.5 py-0 h-4 shrink-0">
                            {monitor.monitorName}
                          </Badge>
                        )}
                      </p>
                    </div>
                  </div>
                  <Button
                    variant="ghost"
                    size="sm"
                    className="opacity-0 group-hover:opacity-100 transition-opacity text-destructive hover:text-destructive hover:bg-destructive/10 shrink-0 ml-2"
                    onClick={() => removeMonitorMutation.mutate(monitor.monitorId)}
                  >
                    <Trash2 className="h-4 w-4 mr-2" />
                    Remove
                  </Button>
                </div>
              ))}
            </div>
          )}
        </CardContent>
      </Card>

      {/* Add Monitors Dialog */}
      <Dialog open={addDialogOpen} onOpenChange={setAddDialogOpen}>
        <DialogContent className="max-w-2xl">
          <DialogHeader>
            <DialogTitle className="flex items-center gap-2">
              <Activity className="h-5 w-5 text-primary" />
              Add Monitors
            </DialogTitle>
            <DialogDescription>
              Select monitors to display on your status page.
              {availableMonitors.length > 0 && (
                <span className="ml-1">{availableMonitors.length} available monitor{availableMonitors.length !== 1 ? 's' : ''}.</span>
              )}
            </DialogDescription>
          </DialogHeader>
          <div className="py-4">
            <div className="space-y-2 max-h-[400px] overflow-y-auto pr-1">
              {allMonitors.map((monitor) => {
                const isSelected = selectedMonitors.includes(monitor.id)
                const isAlreadyAdded = statusPage.monitors.some((sm) => sm.monitorId === monitor.id)
                return (
                  <div
                    key={monitor.id}
                    className={`flex items-center justify-between p-3.5 border rounded-lg transition-all ${
                      isSelected ? 'border-primary bg-primary/5 shadow-sm' : ''
                    } ${isAlreadyAdded ? 'opacity-50' : 'cursor-pointer hover:border-primary/40'}`}
                    onClick={() => {
                      if (!isAlreadyAdded) {
                        setSelectedMonitors((prev) =>
                          isSelected ? prev.filter((id) => id !== monitor.id) : [...prev, monitor.id]
                        )
                      }
                    }}
                  >
                    <div className="flex items-center gap-3">
                      <div className={`h-5 w-5 rounded border-2 flex items-center justify-center shrink-0 transition-colors ${
                        isSelected ? 'bg-primary border-primary' : isAlreadyAdded ? 'bg-muted border-muted' : 'border-muted-foreground/30'
                      }`}>
                        {(isSelected || isAlreadyAdded) && <Check className="h-3 w-3 text-white" />}
                      </div>
                      <div>
                        <p className="font-medium text-sm">{monitor.name}</p>
                        <p className="text-xs text-muted-foreground">{monitor.url}</p>
                      </div>
                    </div>
                    {isAlreadyAdded && (
                      <Badge variant="secondary" className="text-[10px]">Already added</Badge>
                    )}
                  </div>
                )
              })}
              {allMonitors.length === 0 && (
                <div className="text-center py-12 text-muted-foreground">
                  <Activity className="h-8 w-8 mx-auto mb-3 opacity-50" />
                  <p className="font-medium">No uptime monitors found</p>
                  <p className="text-sm mt-1">Create monitors first in the Uptime section.</p>
                </div>
              )}
            </div>
          </div>
          <DialogFooter>
            <Button variant="outline" onClick={() => setAddDialogOpen(false)}>
              Cancel
            </Button>
            <Button onClick={handleSaveMonitors} disabled={selectedMonitors.length === 0 || addMonitorsMutation.isPending}>
              {addMonitorsMutation.isPending ? 'Saving...' : `Add ${selectedMonitors.length} Monitor${selectedMonitors.length !== 1 ? 's' : ''}`}
            </Button>
          </DialogFooter>
        </DialogContent>
      </Dialog>
    </div>
  )
}

// ==========================================
// INCIDENTS TAB
// ==========================================

function IncidentsTab({pageId}: {pageId: string}) {
  const {toast} = useToast()
  const queryClient = useQueryClient()
  const [createDialogOpen, setCreateDialogOpen] = useState(false)
  const [newIncident, setNewIncident] = useState<CreateIncidentRequest>({
    title: '',
    status: 'investigating',
    type: 'incident',
    impact: 'minor',
    message: '',
  })

  const {data: incidents = []} = useQuery({
    queryKey: ['status-page-incidents', pageId],
    queryFn: () => api.getStatusPageIncidents(pageId),
  })

  const createMutation = useMutation({
    mutationFn: (data: CreateIncidentRequest) => api.createIncident(pageId, data),
    onSuccess: () => {
      queryClient.invalidateQueries({queryKey: ['status-page-incidents', pageId]})
      setCreateDialogOpen(false)
      setNewIncident({
        title: '',
        status: 'investigating',
        type: 'incident',
        impact: 'minor',
        message: '',
      })
      toast({title: 'Incident created', description: 'Your incident has been published.'})
    },
    onError: (error: Error) => {
      toast({
        title: 'Failed to create incident',
        description: error.message,
        variant: 'destructive',
      })
    },
  })

  const activeIncidents = incidents.filter((i) => i.status !== 'resolved' && i.status !== 'completed')
  const resolvedIncidents = incidents.filter((i) => i.status === 'resolved' || i.status === 'completed')

  return (
    <div className="space-y-6">
      {/* Incident Header Card */}
      <Card>
        <CardHeader>
          <div className="flex items-center justify-between">
            <div className="flex items-center gap-3">
              <div className="h-10 w-10 rounded-lg bg-orange-500/10 flex items-center justify-center">
                <AlertCircle className="h-5 w-5 text-orange-500" />
              </div>
              <div>
                <CardTitle>Incidents & Maintenance</CardTitle>
                <CardDescription>Manage incidents and scheduled maintenance windows</CardDescription>
              </div>
            </div>
            <Button onClick={() => setCreateDialogOpen(true)}>
              <Plus className="mr-2 h-4 w-4" />
              Create Incident
            </Button>
          </div>
        </CardHeader>
        <CardContent>
          {incidents.length === 0 ? (
            <div className="text-center py-16 border-2 border-dashed rounded-lg">
              <div className="bg-emerald-500/10 h-14 w-14 rounded-full flex items-center justify-center mx-auto mb-4">
                <CheckCircle2 className="h-7 w-7 text-emerald-500" />
              </div>
              <h3 className="text-lg font-semibold mb-2">All systems operational</h3>
              <p className="text-muted-foreground mb-6 max-w-sm mx-auto">
                No incidents have been reported. Create an incident when there's a service disruption or scheduled maintenance.
              </p>
              <div className="flex gap-3 justify-center">
                <Button onClick={() => { setNewIncident(i => ({...i, type: 'incident'})); setCreateDialogOpen(true) }}>
                  <AlertTriangle className="mr-2 h-4 w-4" />
                  Report Incident
                </Button>
                <Button variant="outline" onClick={() => { setNewIncident(i => ({...i, type: 'maintenance', status: 'scheduled'})); setCreateDialogOpen(true) }}>
                  <Wrench className="mr-2 h-4 w-4" />
                  Schedule Maintenance
                </Button>
              </div>
            </div>
          ) : (
            <div className="space-y-8">
              {/* Active Incidents */}
              {activeIncidents.length > 0 && (
                <div>
                  <h3 className="text-sm font-semibold text-muted-foreground uppercase tracking-wider mb-3 flex items-center gap-2">
                    <CircleDot className="h-3.5 w-3.5 text-orange-500" />
                    Active ({activeIncidents.length})
                  </h3>
                  <div className="space-y-3">
                    {activeIncidents.map((incident) => (
                      <IncidentCard key={incident.id} incident={incident} />
                    ))}
                  </div>
                </div>
              )}

              {/* Resolved Incidents */}
              {resolvedIncidents.length > 0 && (
                <div>
                  <h3 className="text-sm font-semibold text-muted-foreground uppercase tracking-wider mb-3 flex items-center gap-2">
                    <CheckCircle2 className="h-3.5 w-3.5 text-emerald-500" />
                    Resolved ({resolvedIncidents.length})
                  </h3>
                  <div className="space-y-3">
                    {resolvedIncidents.map((incident) => (
                      <IncidentCard key={incident.id} incident={incident} />
                    ))}
                  </div>
                </div>
              )}
            </div>
          )}
        </CardContent>
      </Card>

      {/* Create Incident Dialog */}
      <Dialog open={createDialogOpen} onOpenChange={setCreateDialogOpen}>
        <DialogContent className="sm:max-w-[520px]">
          <DialogHeader>
            <DialogTitle className="flex items-center gap-2">
              {newIncident.type === 'maintenance' ? (
                <><Wrench className="h-5 w-5 text-blue-500" /> Schedule Maintenance</>
              ) : (
                <><AlertTriangle className="h-5 w-5 text-orange-500" /> Report Incident</>
              )}
            </DialogTitle>
            <DialogDescription>
              {newIncident.type === 'maintenance'
                ? 'Schedule a maintenance window to inform users ahead of time.'
                : 'Report an incident to communicate service disruptions to your users.'}
            </DialogDescription>
          </DialogHeader>
          <div className="space-y-4 py-4">
            <div className="space-y-2">
              <Label>Type</Label>
              <div className="flex gap-2">
                <Button
                  type="button"
                  variant={newIncident.type === 'incident' ? 'default' : 'outline'}
                  size="sm"
                  className="flex-1"
                  onClick={() => setNewIncident({...newIncident, type: 'incident', status: 'investigating'})}
                >
                  <AlertTriangle className="mr-2 h-3.5 w-3.5" />
                  Incident
                </Button>
                <Button
                  type="button"
                  variant={newIncident.type === 'maintenance' ? 'default' : 'outline'}
                  size="sm"
                  className="flex-1"
                  onClick={() => setNewIncident({...newIncident, type: 'maintenance', status: 'scheduled'})}
                >
                  <Wrench className="mr-2 h-3.5 w-3.5" />
                  Maintenance
                </Button>
              </div>
            </div>
            <div className="space-y-2">
              <Label htmlFor="title">Title</Label>
              <Input
                id="title"
                value={newIncident.title}
                onChange={(e) => setNewIncident({...newIncident, title: e.target.value})}
                placeholder="Brief description of the incident"
              />
            </div>
            <div className="grid grid-cols-2 gap-4">
              <div className="space-y-2">
                <Label>Status</Label>
                <Select
                  value={newIncident.status}
                  onValueChange={(value) => setNewIncident({...newIncident, status: value})}
                >
                  <SelectTrigger>
                    <SelectValue />
                  </SelectTrigger>
                  <SelectContent>
                    <SelectItem value="investigating">Investigating</SelectItem>
                    <SelectItem value="identified">Identified</SelectItem>
                    <SelectItem value="monitoring">Monitoring</SelectItem>
                    <SelectItem value="resolved">Resolved</SelectItem>
                    <SelectItem value="scheduled">Scheduled</SelectItem>
                  </SelectContent>
                </Select>
              </div>
              <div className="space-y-2">
                <Label>Impact</Label>
                <Select
                  value={newIncident.impact}
                  onValueChange={(value) => setNewIncident({...newIncident, impact: value})}
                >
                  <SelectTrigger>
                    <SelectValue />
                  </SelectTrigger>
                  <SelectContent>
                    <SelectItem value="none">None</SelectItem>
                    <SelectItem value="minor">Minor</SelectItem>
                    <SelectItem value="major">Major</SelectItem>
                    <SelectItem value="critical">Critical</SelectItem>
                  </SelectContent>
                </Select>
              </div>
            </div>
            <div className="space-y-2">
              <Label htmlFor="message">Initial Message</Label>
              <Textarea
                id="message"
                value={newIncident.message}
                onChange={(e) => setNewIncident({...newIncident, message: e.target.value})}
                placeholder="Describe what's happening..."
                rows={4}
              />
            </div>
          </div>
          <DialogFooter>
            <Button variant="outline" onClick={() => setCreateDialogOpen(false)}>
              Cancel
            </Button>
            <Button onClick={() => createMutation.mutate(newIncident)} disabled={!newIncident.title || !newIncident.message || createMutation.isPending}>
              {createMutation.isPending ? 'Creating...' : 'Create'}
            </Button>
          </DialogFooter>
        </DialogContent>
      </Dialog>
    </div>
  )
}

function IncidentCard({incident}: {incident: StatusPageIncident}) {
  const { timezone } = useTimezone()
  const statusConfig = getIncidentStatusConfig(incident.status)
  const impactConfig = getIncidentImpactConfig(incident.impact)

  return (
    <Card className="border-l-4 overflow-hidden" style={{borderLeftColor: statusConfig.borderColor}}>
      <div className="p-4">
        <div className="flex items-start justify-between mb-3">
          <div className="flex items-start gap-3">
            <div className={`h-8 w-8 rounded-lg flex items-center justify-center shrink-0 ${statusConfig.iconBg}`}>
              {incident.type === 'maintenance' ? (
                <Wrench className={`h-4 w-4 ${statusConfig.iconColor}`} />
              ) : (
                <statusConfig.icon className={`h-4 w-4 ${statusConfig.iconColor}`} />
              )}
            </div>
            <div>
              <h4 className="font-semibold text-base">{incident.title}</h4>
              <div className="flex flex-wrap items-center gap-2 mt-1.5">
                <Badge variant="outline" className={`${statusConfig.badgeClass} border-transparent text-[11px]`}>
                  {incident.status.replace('_', ' ')}
                </Badge>
                <Badge variant="outline" className={`${impactConfig.badgeClass} border-transparent text-[11px]`}>
                  {impactConfig.icon && <impactConfig.icon className="h-3 w-3 mr-1" />}
                  {incident.impact}
                </Badge>
                <span className="text-xs text-muted-foreground flex items-center gap-1">
                  <Clock className="h-3 w-3" />
                  {formatDateTime(new Date(incident.createdAt), timezone)}
                </span>
              </div>
            </div>
          </div>
        </div>

        {/* Updates Timeline */}
        {incident.updates && incident.updates.length > 0 && (
          <div className="space-y-3 mt-4 pl-4 border-l-2 border-muted/50 ml-4">
            {incident.updates.map((update: IncidentUpdate) => (
              <div key={update.id} className="relative">
                <div className="absolute -left-[21px] top-1.5 h-2.5 w-2.5 rounded-full bg-muted border-2 border-background"></div>
                <p className="text-sm text-foreground/90">{update.message}</p>
                <p className="text-xs text-muted-foreground mt-1 flex items-center gap-1">
                  <Clock className="h-2.5 w-2.5" />
                  {formatDateTime(new Date(update.createdAt), timezone)}
                </p>
              </div>
            ))}
          </div>
        )}
      </div>
    </Card>
  )
}

// ==========================================
// DOMAINS TAB
// ==========================================

function DomainsTab({pageId, statusPage}: {pageId: string; statusPage: StatusPageDetail}) {
  const {toast} = useToast()
  const queryClient = useQueryClient()
  const [addDialogOpen, setAddDialogOpen] = useState(false)
  const [newDomain, setNewDomain] = useState('')

  const addDomainMutation = useMutation({
    mutationFn: (domain: string) => api.addCustomDomain(pageId, domain),
    onSuccess: () => {
      queryClient.invalidateQueries({queryKey: ['status-page', pageId]})
      setAddDialogOpen(false)
      setNewDomain('')
      toast({title: 'Domain added', description: 'Configure DNS records to verify your domain.'})
    },
    onError: (error: Error) => {
      toast({
        title: 'Failed to add domain',
        description: error.message,
        variant: 'destructive',
      })
    },
  })

  const verifyDomainMutation = useMutation({
    mutationFn: (domainId: number) => api.verifyCustomDomain(pageId, domainId),
    onSuccess: (domain) => {
      queryClient.invalidateQueries({queryKey: ['status-page', pageId]})
      toast({
        title: domain.verified ? 'Domain verified!' : 'Verification failed',
        description: domain.verified ? 'Your custom domain is now active.' : 'Please check your DNS records and try again.',
        variant: domain.verified ? 'default' : 'destructive',
      })
    },
  })

  const removeDomainMutation = useMutation({
    mutationFn: (domainId: number) => api.removeCustomDomain(pageId, domainId),
    onSuccess: () => {
      queryClient.invalidateQueries({queryKey: ['status-page', pageId]})
      toast({title: 'Domain removed'})
    },
  })

  return (
    <div className="space-y-6">
      <Card>
        <CardHeader>
          <div className="flex items-center justify-between">
            <div className="flex items-center gap-3">
              <div className="h-10 w-10 rounded-lg bg-purple-500/10 flex items-center justify-center">
                <Link2 className="h-5 w-5 text-purple-500" />
              </div>
              <div>
                <CardTitle>Custom Domains</CardTitle>
                <CardDescription>Use your own domain for your status page</CardDescription>
              </div>
            </div>
            <Button onClick={() => setAddDialogOpen(true)}>
              <Plus className="mr-2 h-4 w-4" />
              Add Domain
            </Button>
          </div>
        </CardHeader>
        <CardContent>
          {statusPage.customDomains.length === 0 ? (
            <div className="text-center py-16 border-2 border-dashed rounded-lg">
              <div className="bg-purple-500/10 h-14 w-14 rounded-full flex items-center justify-center mx-auto mb-4">
                <Link2 className="h-7 w-7 text-purple-500" />
              </div>
              <h3 className="text-lg font-semibold mb-2">No custom domains</h3>
              <p className="text-muted-foreground mb-6 max-w-sm mx-auto">
                Add a custom domain like <code className="text-xs bg-muted px-1.5 py-0.5 rounded">status.yourcompany.com</code> to give your status page a professional look.
              </p>
              <Button onClick={() => setAddDialogOpen(true)}>
                <Plus className="mr-2 h-4 w-4" />
                Add Domain
              </Button>
            </div>
          ) : (
            <div className="space-y-4">
              {statusPage.customDomains.map((domain) => (
                <Card key={domain.id} className={`border-l-4 ${domain.verified ? 'border-l-emerald-500' : 'border-l-yellow-500'}`}>
                  <div className="p-4">
                    <div className="flex items-start justify-between mb-3">
                      <div className="flex items-start gap-3">
                        <div className={`h-9 w-9 rounded-lg flex items-center justify-center shrink-0 ${
                          domain.verified ? 'bg-emerald-500/10' : 'bg-yellow-500/10'
                        }`}>
                          {domain.verified ? (
                            <ShieldCheck className="h-5 w-5 text-emerald-500" />
                          ) : (
                            <ShieldAlert className="h-5 w-5 text-yellow-500" />
                          )}
                        </div>
                        <div>
                          <p className="font-semibold text-base">{domain.domain}</p>
                          <div className="flex items-center gap-2 mt-1">
                            <Badge
                              variant="outline"
                              className={`text-[11px] ${
                                domain.verified
                                  ? 'bg-emerald-500/10 text-emerald-700 dark:text-emerald-400 border-emerald-500/20'
                                  : 'bg-yellow-500/10 text-yellow-700 dark:text-yellow-400 border-yellow-500/20'
                              }`}
                            >
                              {domain.verified ? (
                                <><CheckCircle2 className="h-3 w-3 mr-1" /> Verified</>
                              ) : (
                                <><AlertCircle className="h-3 w-3 mr-1" /> Pending Verification</>
                              )}
                            </Badge>
                            {domain.sslProvisioned && (
                              <Badge variant="outline" className="text-[11px] bg-blue-500/10 text-blue-700 dark:text-blue-400 border-blue-500/20">
                                <Lock className="h-3 w-3 mr-1" /> SSL Active
                              </Badge>
                            )}
                          </div>
                        </div>
                      </div>
                      <div className="flex gap-2 shrink-0">
                        {!domain.verified && (
                          <Button
                            size="sm"
                            variant="outline"
                            onClick={() => verifyDomainMutation.mutate(domain.id)}
                            disabled={verifyDomainMutation.isPending}
                          >
                            <RefreshCw className={`h-3.5 w-3.5 mr-2 ${verifyDomainMutation.isPending ? 'animate-spin' : ''}`} />
                            Verify
                          </Button>
                        )}
                        <Button
                          variant="ghost"
                          size="sm"
                          className="text-destructive hover:text-destructive hover:bg-destructive/10"
                          onClick={() => removeDomainMutation.mutate(domain.id)}
                        >
                          <Trash2 className="h-3.5 w-3.5" />
                        </Button>
                      </div>
                    </div>

                    {/* DNS Instructions */}
                    {!domain.verified && (
                      <div className="bg-muted/50 p-4 rounded-lg border space-y-3 mt-3">
                        <div className="flex items-center gap-2 mb-2">
                          <Info className="h-4 w-4 text-blue-500" />
                          <p className="font-medium text-sm">DNS Configuration Required</p>
                        </div>
                        <div className="grid gap-3 sm:grid-cols-2">
                          <div className="bg-background rounded-lg p-3 border">
                            <p className="text-xs font-medium text-muted-foreground mb-1.5">CNAME Record</p>
                            <code className="text-xs block bg-muted px-2 py-1.5 rounded break-all font-mono">
                              {domain.domain} <span className="text-muted-foreground">&rarr;</span> status.moneat.app
                            </code>
                          </div>
                          <div className="bg-background rounded-lg p-3 border">
                            <p className="text-xs font-medium text-muted-foreground mb-1.5">TXT Verification Record</p>
                            <code className="text-xs block bg-muted px-2 py-1.5 rounded break-all font-mono">
                              _moneat-verify.{domain.domain}
                            </code>
                            <code className="text-xs block bg-muted px-2 py-1.5 rounded break-all font-mono mt-1">
                              moneat-verify={domain.verificationToken}
                            </code>
                          </div>
                        </div>
                      </div>
                    )}
                  </div>
                </Card>
              ))}
            </div>
          )}
        </CardContent>
      </Card>

      {/* Add Domain Dialog */}
      <Dialog open={addDialogOpen} onOpenChange={setAddDialogOpen}>
        <DialogContent>
          <DialogHeader>
            <DialogTitle className="flex items-center gap-2">
              <Link2 className="h-5 w-5 text-primary" />
              Add Custom Domain
            </DialogTitle>
            <DialogDescription>
              Add a custom domain for your status page. You'll need to configure DNS records to verify ownership.
            </DialogDescription>
          </DialogHeader>
          <div className="py-4 space-y-4">
            <div className="space-y-2">
              <Label htmlFor="domain">Domain</Label>
              <Input
                id="domain"
                value={newDomain}
                onChange={(e) => setNewDomain(e.target.value)}
                placeholder="status.yourcompany.com"
              />
              <p className="text-xs text-muted-foreground">
                Enter the subdomain you'd like to use (e.g., status.yourcompany.com)
              </p>
            </div>
          </div>
          <DialogFooter>
            <Button variant="outline" onClick={() => setAddDialogOpen(false)}>
              Cancel
            </Button>
            <Button onClick={() => addDomainMutation.mutate(newDomain)} disabled={!newDomain || addDomainMutation.isPending}>
              {addDomainMutation.isPending ? 'Adding...' : 'Add Domain'}
            </Button>
          </DialogFooter>
        </DialogContent>
      </Dialog>
    </div>
  )
}

// ==========================================
// HELPER FUNCTIONS
// ==========================================

function getIncidentStatusConfig(status: string) {
  const configs: Record<string, {borderColor: string; iconBg: string; iconColor: string; icon: LucideIcon; badgeClass: string}> = {
    investigating: {
      borderColor: '#ef4444',
      iconBg: 'bg-red-500/10',
      iconColor: 'text-red-500',
      icon: AlertTriangle,
      badgeClass: 'bg-red-100 text-red-800 dark:bg-red-900/50 dark:text-red-200',
    },
    identified: {
      borderColor: '#f97316',
      iconBg: 'bg-orange-500/10',
      iconColor: 'text-orange-500',
      icon: AlertCircle,
      badgeClass: 'bg-orange-100 text-orange-800 dark:bg-orange-900/50 dark:text-orange-200',
    },
    monitoring: {
      borderColor: '#3b82f6',
      iconBg: 'bg-blue-500/10',
      iconColor: 'text-blue-500',
      icon: Eye,
      badgeClass: 'bg-blue-100 text-blue-800 dark:bg-blue-900/50 dark:text-blue-200',
    },
    resolved: {
      borderColor: '#10b981',
      iconBg: 'bg-emerald-500/10',
      iconColor: 'text-emerald-500',
      icon: CheckCircle2,
      badgeClass: 'bg-emerald-100 text-emerald-800 dark:bg-emerald-900/50 dark:text-emerald-200',
    },
    scheduled: {
      borderColor: '#8b5cf6',
      iconBg: 'bg-purple-500/10',
      iconColor: 'text-purple-500',
      icon: Clock,
      badgeClass: 'bg-purple-100 text-purple-800 dark:bg-purple-900/50 dark:text-purple-200',
    },
    in_progress: {
      borderColor: '#3b82f6',
      iconBg: 'bg-blue-500/10',
      iconColor: 'text-blue-500',
      icon: Settings,
      badgeClass: 'bg-blue-100 text-blue-800 dark:bg-blue-900/50 dark:text-blue-200',
    },
    completed: {
      borderColor: '#10b981',
      iconBg: 'bg-emerald-500/10',
      iconColor: 'text-emerald-500',
      icon: CheckCircle2,
      badgeClass: 'bg-emerald-100 text-emerald-800 dark:bg-emerald-900/50 dark:text-emerald-200',
    },
  }
  return configs[status] || configs.investigating
}

function getIncidentImpactConfig(impact: string) {
  const configs: Record<string, {badgeClass: string; icon: LucideIcon | null}> = {
    none: {
      badgeClass: 'bg-gray-100 text-gray-800 dark:bg-gray-800 dark:text-gray-200',
      icon: null,
    },
    minor: {
      badgeClass: 'bg-yellow-100 text-yellow-800 dark:bg-yellow-900/50 dark:text-yellow-200',
      icon: AlertTriangle,
    },
    major: {
      badgeClass: 'bg-orange-100 text-orange-800 dark:bg-orange-900/50 dark:text-orange-200',
      icon: AlertTriangle,
    },
    critical: {
      badgeClass: 'bg-red-100 text-red-800 dark:bg-red-900/50 dark:text-red-200',
      icon: XCircle,
    },
  }
  return configs[impact] || configs.none
}
