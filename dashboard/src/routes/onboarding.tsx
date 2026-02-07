import {createFileRoute, redirect, useNavigate} from '@tanstack/react-router'
import {useState} from 'react'
import {api} from '@/lib/api'
import {Button} from '@/components/ui/button'
import {Input} from '@/components/ui/input'
import {Label} from '@/components/ui/label'
import {Card, CardContent, CardDescription, CardHeader, CardTitle} from '@/components/ui/card'
import {Logo} from '@/components/logo'
import {Select, SelectContent, SelectItem, SelectTrigger, SelectValue} from '@/components/ui/select'

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

function OnboardingPage() {
  const navigate = useNavigate()
  const [organizationName, setOrganizationName] = useState('')
  const [companySize, setCompanySize] = useState('')
  const [error, setError] = useState('')
  const [loading, setLoading] = useState(false)

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

    setLoading(true)
    try {
      await api.completeOnboarding(organizationName, companySize)
      navigate({ to: '/' })
    } catch (err) {
      setError('Failed to complete onboarding. Please try again.')
      setLoading(false)
    }
  }

  return (
    <div className="flex min-h-screen items-center justify-center px-4">
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
            {error && <div className="text-sm text-destructive">{error}</div>}
            
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
            
            <Button type="submit" className="w-full" disabled={loading}>
              {loading ? 'Setting up...' : 'Complete Setup'}
            </Button>
          </form>
        </CardContent>
      </Card>
    </div>
  )
}
