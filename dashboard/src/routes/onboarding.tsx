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
import {useState, useEffect} from 'react'
import {useMutation, useQueryClient} from '@tanstack/react-query'
import {api} from '@/lib/api'
import {trackEvent} from '@/lib/analytics'
import {useProject} from '@/contexts/project-context'
import {platforms, type PlatformType} from '@/routes/projects'
import {Button} from '@/components/ui/button'
import {Input} from '@/components/ui/input'
import {Label} from '@/components/ui/label'
import {Card, CardContent, CardDescription, CardHeader, CardTitle} from '@/components/ui/card'
import {Logo} from '@/components/logo'
import {Select, SelectContent, SelectItem, SelectTrigger, SelectValue} from '@/components/ui/select'
import {cn} from '@/lib/utils'
import {Copy, Check, AlertCircle, Loader2, ArrowRight} from 'lucide-react'

export const Route = createFileRoute('/onboarding')({
  beforeLoad: ({ location }) => {
    if (!api.isAuthenticated()) {
      throw redirect({ to: '/login', search: { redirect: location.href } })
    }
  },
  component: OnboardingPage,
})

const COMPANY_SIZES = [
  'Just me',
  '2-10',
  '11-50',
  '51-200',
  '201-500',
  '500+'
]

const REFERRAL_SOURCES = [
  'Google Search',
  'Social Media (Twitter, LinkedIn, etc.)',
  'Friend or Colleague',
  'Blog Post or Article',
  'Conference or Event',
  'Product Hunt',
  'GitHub',
  'Other'
]

// Generate slug from organization name
function generateSlug(name: string): string {
  return name
    .toLowerCase()
    .replace(/[^a-z0-9]+/g, '-')
    .replace(/^-+|-+$/g, '')
    .substring(0, 100)
}

type OnboardingStep = 'org' | 'project'
type PlatformFilter = 'all' | 'mobile' | 'frontend' | 'backend' | 'desktop-gaming'

const platformFilterTabs: Array<{ id: PlatformFilter; label: string }> = [
  { id: 'all', label: 'All' },
  { id: 'mobile', label: 'Mobile' },
  { id: 'frontend', label: 'Frontend' },
  { id: 'backend', label: 'Backend' },
  { id: 'desktop-gaming', label: 'Desktop & Gaming' },
]

function OnboardingPage() {
  const navigate = useNavigate()
  const queryClient = useQueryClient()
  const { setSelectedProjectId } = useProject()
  const [step, setStep] = useState<OnboardingStep>('org')

  // Org step state
  const [organizationName, setOrganizationName] = useState('')
  const [companySize, setCompanySize] = useState('')
  const [referralSource, setReferralSource] = useState('')
  const [slug, setSlug] = useState('')
  const [customSlug, setCustomSlug] = useState(false)
  const [error, setError] = useState('')
  const [loading, setLoading] = useState(false)
  const [copied, setCopied] = useState(false)
  const [checkingSlug, setCheckingSlug] = useState(false)
  const [slugAvailable, setSlugAvailable] = useState<boolean | null>(null)

  // Project step state
  const [projectName, setProjectName] = useState('')
  const [selectedPlatform, setSelectedPlatform] = useState<string | null>(null)
  const [selectedTargets, setSelectedTargets] = useState<string[]>([])
  const [platformFilter, setPlatformFilter] = useState<PlatformFilter>('all')
  const [projectError, setProjectError] = useState('')

  // Auto-generate slug from org name
  useEffect(() => {
    if (!customSlug && organizationName) {
      const generated = generateSlug(organizationName)
      setSlug(generated)
    }
  }, [organizationName, customSlug])

  // Check slug availability with debouncing
  useEffect(() => {
    if (!slug) {
      setSlugAvailable(null)
      return
    }

    const timeoutId = setTimeout(async () => {
      setCheckingSlug(true)
      try {
        const result = await api.checkSlugAvailability(slug)
        setSlugAvailable(result.available)
      } catch {
        setSlugAvailable(null)
      } finally {
        setCheckingSlug(false)
      }
    }, 500)

    return () => clearTimeout(timeoutId)
  }, [slug])

  const handleSlugChange = (value: string) => {
    // Sanitize input: lowercase, replace non-alphanumeric with hyphens
    const sanitized = value
      .toLowerCase()
      .replace(/[^a-z0-9-]/g, '-')
      .replace(/^-+|-+$/g, '')
      .substring(0, 100)
    setSlug(sanitized)
    setCustomSlug(true)
  }

  const copySlug = () => {
    navigator.clipboard.writeText(slug)
    setCopied(true)
    setTimeout(() => setCopied(false), 2000)
  }

  const handleSubmit = async (e: React.FormEvent) => {
    e.preventDefault()
    setError('')
    
    if (!organizationName.trim()) {
      setError('Please enter an organization name')
      return
    }
    
    if (!companySize) {
      setError('Please select a company size')
      return
    }

    if (!referralSource) {
      setError('Please select how you heard about us')
      return
    }

    if (!slug) {
      setError('Organization slug is required')
      return
    }

    if (slugAvailable === false) {
      setError('This slug is already taken. Please choose a different one.')
      return
    }

    setLoading(true)
    try {
      // Retrieve UTM parameters from localStorage
      const utmParamsStr = localStorage.getItem('utm_params')
      let utmParams: Record<string, string | undefined> = {}
      if (utmParamsStr) {
        try {
          utmParams = JSON.parse(utmParamsStr) as Record<string, string | undefined>
        } catch {
          localStorage.removeItem('utm_params')
        }
      }
      
      await api.completeOnboarding({
        organizationName,
        companySize,
        slug,
        referralSource,
        utmSource: utmParams.utmSource,
        utmMedium: utmParams.utmMedium,
        utmCampaign: utmParams.utmCampaign,
        utmContent: utmParams.utmContent,
        utmTerm: utmParams.utmTerm,
      })
      
      // Clean up UTM params after successful onboarding
      localStorage.removeItem('utm_params')
      trackEvent('Onboarding Complete', { company_size: companySize })
      
      setStep('project')
    } catch {
      setError('Failed to complete onboarding. Please try again.')
      setLoading(false)
    }
  }

  const createProjectMutation = useMutation({
    mutationFn: (data: { name: string; framework: string; targets?: string[] }) =>
      api.createProject(data.name, data.framework, data.targets),
    onSuccess: (project) => {
      trackEvent('Onboarding Project Create', { framework: project.framework || 'none' })
      queryClient.invalidateQueries({ queryKey: ['projects'] })
      setSelectedProjectId(project.id)
      navigate({ to: `/projects/${project.id}` })
    },
    onError: (error: Error) => {
      if (error.message.includes('already exists')) {
        setProjectError('A project with this name already exists. Please choose a different name.')
      } else {
        setProjectError(error.message || 'Failed to create project. Please try again.')
      }
    },
  })

  const handlePlatformSelect = (platformId: string) => {
    setSelectedPlatform(platformId)
    const platform = platforms.find(p => p.id === platformId)
    if (platform?.targets && platform.defaultTargets) {
      setSelectedTargets(platform.defaultTargets)
    } else {
      setSelectedTargets([])
    }
  }

  const toggleTarget = (targetId: string) => {
    setSelectedTargets(prev =>
      prev.includes(targetId) ? prev.filter(t => t !== targetId) : [...prev, targetId]
    )
  }

  const handleCreateProject = () => {
    if (projectName && selectedPlatform) {
      setProjectError('')
      const platform = platforms.find(p => p.id === selectedPlatform)
      const targets = platform?.targets && selectedTargets.length > 0 ? selectedTargets : undefined
      createProjectMutation.mutate({
        name: projectName,
        framework: selectedPlatform,
        targets,
      })
    }
  }

  const filteredPlatforms = platforms.filter((platform: PlatformType) => {
    if (platform.alwaysVisible || platformFilter === 'all') return true
    if (platformFilter === 'desktop-gaming') {
      return platform.category === 'desktop' || platform.category === 'gaming'
    }
    return platform.category === platformFilter
  })

  if (step === 'project') {
    return (
      <div className="flex min-h-screen items-center justify-center px-4 bg-background">
        <Card className="w-full max-w-2xl">
          <CardHeader className="text-center space-y-4">
            <div className="flex justify-center">
              <Logo className="h-10" />
            </div>
            <div>
              <CardTitle className="text-2xl">Create Your First Project</CardTitle>
              <CardDescription className="mt-1">Set up a project to start tracking errors and monitoring your applications.</CardDescription>
            </div>
          </CardHeader>
          <CardContent>
            <div className="space-y-4">
              {projectError && (
                <div className="flex items-center gap-2 p-3 text-sm text-destructive bg-destructive/10 rounded-md">
                  <AlertCircle className="h-4 w-4" />
                  {projectError}
                </div>
              )}

              <div>
                <Label htmlFor="projectName" className="mb-2 block">Project Name</Label>
                <Input
                  id="projectName"
                  placeholder="My awesome app"
                  value={projectName}
                  onChange={(e) => setProjectName(e.target.value)}
                  autoFocus
                />
              </div>

              <div>
                <Label className="mb-3 block">Select Platform</Label>
                <div className="mb-3 flex flex-wrap gap-2">
                  {platformFilterTabs.map((tab) => (
                    <Button
                      key={tab.id}
                      type="button"
                      size="sm"
                      variant={platformFilter === tab.id ? 'default' : 'outline'}
                      onClick={() => setPlatformFilter(tab.id)}
                    >
                      {tab.label}
                    </Button>
                  ))}
                </div>
                <div className="max-h-64 overflow-y-auto rounded-lg border p-3 pr-2">
                  <div className="grid grid-cols-2 sm:grid-cols-3 md:grid-cols-4 gap-2">
                    {filteredPlatforms.map((platform: PlatformType) => {
                      const Icon = platform.icon
                      return (
                        <button
                          key={platform.id}
                          onClick={() => handlePlatformSelect(platform.id)}
                          className={cn(
                            'relative flex flex-col items-center gap-1.5 p-3 rounded-lg border-2 transition-all',
                            selectedPlatform === platform.id
                              ? 'border-primary bg-primary/5 shadow-md'
                              : 'border-border hover:border-primary/50 hover:bg-accent'
                          )}
                        >
                          <div className="p-2 rounded-lg" style={{ backgroundColor: platform.color }}>
                            <Icon className="h-5 w-5 text-white" />
                          </div>
                          <span className="text-xs font-medium text-center leading-tight">{platform.name}</span>
                        </button>
                      )
                    })}
                  </div>
                </div>
              </div>

              {/* Target selection for multi-platform frameworks */}
              {selectedPlatform && platforms.find(p => p.id === selectedPlatform)?.targets && (
                <div>
                  <Label className="mb-3 block">Select Target Platforms</Label>
                  <div className="rounded-lg border p-4">
                    <div className="grid grid-cols-2 sm:grid-cols-3 gap-2">
                      {platforms.find(p => p.id === selectedPlatform)?.targets?.map(target => (
                        <button
                          key={target.id}
                          type="button"
                          onClick={() => toggleTarget(target.id)}
                          className={cn(
                            'flex items-center gap-2 px-3 py-2 rounded-lg border-2 transition-all text-sm font-medium',
                            selectedTargets.includes(target.id)
                              ? 'border-primary bg-primary/5'
                              : 'border-border hover:border-primary/50 hover:bg-accent'
                          )}
                        >
                          <div className={cn(
                            'w-4 h-4 rounded border-2 flex items-center justify-center',
                            selectedTargets.includes(target.id) ? 'bg-primary border-primary' : 'border-border'
                          )}>
                            {selectedTargets.includes(target.id) && (
                              <Check className="w-3 h-3 text-white" />
                            )}
                          </div>
                          {target.name}
                        </button>
                      ))}
                    </div>
                    {selectedTargets.length === 0 && (
                      <p className="text-sm text-destructive mt-2">Please select at least one target platform</p>
                    )}
                  </div>
                </div>
              )}

              <div className="flex gap-2 pt-2">
                <Button
                  onClick={handleCreateProject}
                  disabled={
                    !projectName ||
                    !selectedPlatform ||
                    (platforms.find(p => p.id === selectedPlatform)?.targets && selectedTargets.length === 0) ||
                    createProjectMutation.isPending
                  }
                >
                  {createProjectMutation.isPending ? (
                    <>
                      <Loader2 className="mr-2 h-4 w-4 animate-spin" />
                      Creating...
                    </>
                  ) : (
                    <>
                      Create Project
                      <ArrowRight className="ml-2 h-4 w-4" />
                    </>
                  )}
                </Button>
                <Button
                  variant="ghost"
                  onClick={() => navigate({ to: '/' })}
                  disabled={createProjectMutation.isPending}
                >
                  Skip for now
                </Button>
              </div>
            </div>
          </CardContent>
        </Card>
      </div>
    )
  }

  return (
    <div className="flex min-h-screen items-center justify-center px-4 bg-background">
      <Card className="w-full max-w-md">
        <CardHeader className="text-center space-y-4">
          <div className="flex justify-center">
            <Logo className="h-10" />
          </div>
          <div>
            <CardTitle className="text-2xl">Welcome!</CardTitle>
            <CardDescription className="mt-1">Let's set up your organization</CardDescription>
          </div>
        </CardHeader>
        <CardContent>
          <form onSubmit={handleSubmit} className="space-y-4">
            {error && (
              <div className="flex items-center gap-2 p-3 text-sm text-destructive bg-destructive/10 rounded-md">
                <AlertCircle className="h-4 w-4" />
                {error}
              </div>
            )}
            
            <div className="space-y-2">
              <Label htmlFor="organizationName">Organization Name</Label>
              <Input
                id="organizationName"
                type="text"
                placeholder="Acme Inc."
                value={organizationName}
                onChange={(e) => setOrganizationName(e.target.value)}
                required
              />
            </div>

            <div className="space-y-2">
              <Label htmlFor="slug">Organization Slug</Label>
              <div className="flex gap-2">
                <div className="relative flex-1">
                  <Input
                    id="slug"
                    type="text"
                    placeholder="acme-inc"
                    value={slug}
                    onChange={(e) => handleSlugChange(e.target.value)}
                    className={
                      slugAvailable === false 
                        ? 'pr-8 border-destructive focus-visible:ring-destructive' 
                        : slugAvailable === true 
                        ? 'pr-8 border-green-600 focus-visible:ring-green-600' 
                        : 'pr-8'
                    }
                    required
                  />
                  {checkingSlug && (
                    <Loader2 className="absolute right-2 top-1/2 -translate-y-1/2 h-4 w-4 animate-spin text-muted-foreground" />
                  )}
                  {!checkingSlug && slugAvailable === true && (
                    <Check className="absolute right-2 top-1/2 -translate-y-1/2 h-4 w-4 text-green-600" />
                  )}
                  {!checkingSlug && slugAvailable === false && (
                    <AlertCircle className="absolute right-2 top-1/2 -translate-y-1/2 h-4 w-4 text-destructive" />
                  )}
                </div>
                <Button 
                  type="button" 
                  variant="outline" 
                  size="icon"
                  onClick={copySlug}
                  disabled={!slug}
                >
                  {copied ? <Check className="h-4 w-4" /> : <Copy className="h-4 w-4" />}
                </Button>
              </div>
              <p className="text-xs text-muted-foreground">
                This slug is used for Sentry CLI symbol uploads and API endpoints
              </p>
              {slugAvailable === false && (
                <p className="text-xs text-destructive">This slug is already taken</p>
              )}
            </div>
            
            <div className="space-y-2">
              <Label htmlFor="companySize">Company Size</Label>
              <Select value={companySize} onValueChange={setCompanySize} required>
                <SelectTrigger id="companySize">
                  <SelectValue placeholder="Select company size" />
                </SelectTrigger>
                <SelectContent>
                  {COMPANY_SIZES.map((size) => (
                    <SelectItem key={size} value={size}>
                      {size}
                    </SelectItem>
                  ))}
                </SelectContent>
              </Select>
            </div>

            <div className="space-y-2">
              <Label htmlFor="referralSource">How did you hear about us?</Label>
              <Select value={referralSource} onValueChange={setReferralSource} required>
                <SelectTrigger id="referralSource">
                  <SelectValue placeholder="Select an option" />
                </SelectTrigger>
                <SelectContent>
                  {REFERRAL_SOURCES.map((source) => (
                    <SelectItem key={source} value={source}>
                      {source}
                    </SelectItem>
                  ))}
                </SelectContent>
              </Select>
            </div>
            
            <Button 
              type="submit" 
              className="w-full" 
              disabled={loading || checkingSlug || slugAvailable === false}
            >
              {loading ? (
                <>
                  <Loader2 className="mr-2 h-4 w-4 animate-spin" />
                  Setting up...
                </>
              ) : (
                <>
                  Continue
                  <ArrowRight className="ml-2 h-4 w-4" />
                </>
              )}
            </Button>
          </form>
        </CardContent>
      </Card>
    </div>
  )
}
