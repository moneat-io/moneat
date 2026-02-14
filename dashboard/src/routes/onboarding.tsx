import {createFileRoute, redirect, useNavigate} from '@tanstack/react-router'
import {useState, useEffect} from 'react'
import {api} from '@/lib/api'
import {Button} from '@/components/ui/button'
import {Input} from '@/components/ui/input'
import {Label} from '@/components/ui/label'
import {Card, CardContent, CardDescription, CardHeader, CardTitle} from '@/components/ui/card'
import {Logo} from '@/components/logo'
import {Select, SelectContent, SelectItem, SelectTrigger, SelectValue} from '@/components/ui/select'
import {Copy, Check, AlertCircle, Loader2} from 'lucide-react'

export const Route = createFileRoute('/onboarding')({
  beforeLoad: () => {
    if (!api.isAuthenticated()) {
      throw redirect({ to: '/login' })
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

function OnboardingPage() {
  const navigate = useNavigate()
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
      const utmParams = utmParamsStr ? JSON.parse(utmParamsStr) : {}
      
      await api.completeOnboarding(
        organizationName, 
        companySize, 
        slug, 
        referralSource,
        utmParams.utmSource,
        utmParams.utmMedium,
        utmParams.utmCampaign,
        utmParams.utmContent,
        utmParams.utmTerm
      )
      
      // Clean up UTM params after successful onboarding
      localStorage.removeItem('utm_params')
      
      navigate({ to: '/' })
    } catch (err) {
      setError('Failed to complete onboarding. Please try again.')
      setLoading(false)
    }
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
                'Complete Setup'
              )}
            </Button>
          </form>
        </CardContent>
      </Card>
    </div>
  )
}
