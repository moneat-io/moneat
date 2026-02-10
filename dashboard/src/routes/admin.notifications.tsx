import {createFileRoute} from '@tanstack/react-router'
import {useMutation} from '@tanstack/react-query'
import {api} from '@/lib/api'
import {useState, useEffect} from 'react'
import {Card, CardContent, CardDescription, CardHeader, CardTitle} from '@/components/ui/card'
import {Button} from '@/components/ui/button'
import {Alert, AlertDescription} from '@/components/ui/alert'
import {Badge} from '@/components/ui/badge'
import {Input} from '@/components/ui/input'
import {Label} from '@/components/ui/label'
import {useToast} from '@/hooks/use-toast'
import {
  Bell,
  Mail,
  CheckCircle2,
  XCircle,
  AlertCircle,
  TrendingUp,
  ArrowUp,
  ArrowDown,
  Activity,
  Key,
  LockKeyhole,
} from 'lucide-react'
import {SectionHeader} from '@/components/admin-components'

const SlackLogo = ({ className }: { className?: string }) => (
  <svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 24 24" className={className}>
    <path fill="#E01E5A" d="M5.042 15.165a2.528 2.528 0 0 1-2.52 2.523A2.52 2.52 0 0 1 0 15.165a2.527 2.527 0 0 1 2.522-2.52h2.52v2.52zM6.313 15.165a2.527 2.527 0 0 1 2.521-2.52 2.522 2.522 0 0 1 2.521 2.52v6.313A2.52 2.52 0 0 1 8.834 24a2.528 2.528 0 0 1-2.521-2.522v-6.313z"/>
    <path fill="#36C5F0" d="M8.834 5.042a2.528 2.528 0 0 1-2.521-2.52A2.52 2.52 0 0 1 8.834 0a2.528 2.528 0 0 1 2.521 2.522v2.52h-2.521zM8.834 6.313a2.528 2.528 0 0 1 2.521 2.521 2.522 2.522 0 0 1-2.521 2.521H2.522A2.52 2.52 0 0 1 0 8.834a2.528 2.528 0 0 1 2.522-2.521h6.312z"/>
    <path fill="#2EB67D" d="M18.956 8.834a2.528 2.528 0 0 1 2.522-2.521A2.52 2.52 0 0 1 24 8.834a2.528 2.528 0 0 1-2.522 2.521h-2.522V8.834zM17.688 8.834a2.528 2.528 0 0 1-2.523 2.521 2.522 2.522 0 0 1-2.52-2.521V2.522A2.52 2.52 0 0 1 15.165 0a2.528 2.528 0 0 1 2.523 2.522v6.312z"/>
    <path fill="#ECB22E" d="M15.165 18.956a2.528 2.528 0 0 1 2.523 2.522A2.52 2.52 0 0 1 15.165 24a2.527 2.527 0 0 1-2.52-2.522v-2.522h2.52zM15.165 17.688a2.527 2.527 0 0 1-2.52-2.523 2.52 2.52 0 0 1 2.52-2.52h6.313A2.52 2.52 0 0 1 24 15.165a2.528 2.528 0 0 1-2.522 2.523h-6.313z"/>
  </svg>
)

export const Route = createFileRoute('/admin/notifications')({
  component: AdminNotificationsPage,
})

type NotificationType = 
  | 'error_alert'
  | 'weekly_summary'
  | 'system_up'
  | 'system_down'
  | 'uptime_alert'
  | 'verification'
  | 'password_reset'

type Channel = 'email' | 'slack' | 'both'

interface TestNotificationResult {
  success: boolean
  emailSent: boolean
  slackSent: boolean
  errors?: string[]
}

const notificationTypes: Array<{
  type: NotificationType
  label: string
  description: string
  icon: any
  supportsEmail: boolean
  supportsSlack: boolean
  slackLogo?: boolean
}> = [
  {
    type: 'error_alert',
    label: 'Error Alert',
    description: 'New error or exception detected in a project',
    icon: AlertCircle,
    supportsEmail: true,
    supportsSlack: true,
    slackLogo: false,
  },
  {
    type: 'weekly_summary',
    label: 'Weekly Summary',
    description: 'Weekly digest with stats and top issues',
    icon: TrendingUp,
    supportsEmail: true,
    supportsSlack: false,
  },
  {
    type: 'system_up',
    label: 'System Recovered',
    description: 'System monitoring - recovery notification',
    icon: ArrowUp,
    supportsEmail: false,
    supportsSlack: true,
  },
  {
    type: 'system_down',
    label: 'System Down',
    description: 'System monitoring - outage notification',
    icon: ArrowDown,
    supportsEmail: false,
    supportsSlack: true,
  },
  {
    type: 'uptime_alert',
    label: 'Uptime Monitor Alert',
    description: 'Monitor status change notification',
    icon: Activity,
    supportsEmail: false,
    supportsSlack: true,
  },
  {
    type: 'verification',
    label: 'Email Verification',
    description: 'Email address verification request',
    icon: Key,
    supportsEmail: true,
    supportsSlack: false,
  },
  {
    type: 'password_reset',
    label: 'Password Reset',
    description: 'Password reset request',
    icon: LockKeyhole,
    supportsEmail: true,
    supportsSlack: false,
  },
]

function AdminNotificationsPage() {
  const { toast } = useToast()
  const [testEmail, setTestEmail] = useState('')
  const [lastResult, setLastResult] = useState<{
    type: NotificationType
    channel: Channel
    result: TestNotificationResult
  } | null>(null)

  // Load test email from localStorage on mount
  useEffect(() => {
    const saved = localStorage.getItem('moneat_test_email')
    if (saved) {
      setTestEmail(saved)
    }
  }, [])

  // Save test email to localStorage when it changes
  const handleEmailChange = (email: string) => {
    setTestEmail(email)
    localStorage.setItem('moneat_test_email', email)
  }

  const testMutation = useMutation({
    mutationFn: async ({type, channel}: {type: NotificationType; channel: Channel}) => {
      console.log('Testing notification:', type, channel)
      return api.testNotification(type, channel, testEmail || undefined)
    },
    onSuccess: (result, variables) => {
      console.log('Test notification result:', result)
      setLastResult({
        type: variables.type,
        channel: variables.channel,
        result,
      })
      if (result.success) {
        toast({
          title: 'Test notification sent!',
          description: `${result.emailSent ? 'Email' : ''} ${result.emailSent && result.slackSent ? 'and' : ''} ${result.slackSent ? 'Slack' : ''} notification sent successfully.`,
        })
      }
    },
    onError: (error) => {
      console.error('Test notification error:', error)
      toast({
        title: 'Failed to send test notification',
        description: error instanceof Error ? error.message : 'An error occurred',
        variant: 'destructive',
      })
    },
  })

  const handleTest = (type: NotificationType, channel: Channel) => {
    console.log('handleTest called:', type, channel)
    testMutation.mutate({type, channel})
  }

  return (
    <div className="space-y-8">
      <SectionHeader
        title="Test Notifications"
        description="Send test notifications to verify your email and Slack integrations are working correctly."
      />

      {/* Test Email Configuration */}
      <Card>
        <CardHeader>
          <CardTitle>Email Configuration</CardTitle>
          <CardDescription>
            Specify an email address for test notifications (saved in browser storage)
          </CardDescription>
        </CardHeader>
        <CardContent>
          <div className="flex gap-2 max-w-md">
            <div className="flex-1">
              <Label htmlFor="test-email" className="sr-only">Test Email Address</Label>
              <Input
                id="test-email"
                type="email"
                placeholder="your-email@example.com"
                value={testEmail}
                onChange={(e) => handleEmailChange(e.target.value)}
              />
            </div>
            {testEmail && (
              <Button
                variant="ghost"
                size="sm"
                onClick={() => handleEmailChange('')}
              >
                Clear
              </Button>
            )}
          </div>
          <p className="text-xs text-muted-foreground mt-2">
            If not specified, tests will be sent to your account email
          </p>
        </CardContent>
      </Card>

      {/* Info Alert */}
      <Alert>
        <Bell className="h-4 w-4" />
        <AlertDescription>
          Test notifications are clearly marked as <strong>[TEST]</strong> to avoid confusion. 
          Slack tests require a configured integration in Settings → Integrations.
        </AlertDescription>
      </Alert>

      {/* Last Result */}
      {lastResult && (
        <Alert variant={lastResult.result.success ? 'default' : 'destructive'}>
          {lastResult.result.success ? (
            <CheckCircle2 className="h-4 w-4" />
          ) : (
            <XCircle className="h-4 w-4" />
          )}
          <AlertDescription>
            <div className="space-y-1">
              <p className="font-medium">
                {lastResult.result.success ? 'Test notification sent!' : 'Test notification failed'}
              </p>
              {lastResult.result.emailSent && (
                <p className="text-sm">✓ Email sent successfully</p>
              )}
              {lastResult.result.slackSent && (
                <p className="text-sm">✓ Slack message sent successfully</p>
              )}
              {lastResult.result.errors && lastResult.result.errors.length > 0 && (
                <div className="mt-2 space-y-1">
                  {lastResult.result.errors.map((error, i) => (
                    <p key={i} className="text-sm text-destructive">✗ {error}</p>
                  ))}
                </div>
              )}
            </div>
          </AlertDescription>
        </Alert>
      )}

      {/* Notification Types */}
      <div className="grid grid-cols-1 gap-4">
        {notificationTypes.map((notif) => {
          const Icon = notif.icon
          return (
            <Card key={notif.type}>
              <CardHeader>
                <div className="flex items-start justify-between">
                  <div className="flex items-start gap-3">
                    <div className="rounded-lg bg-primary/10 p-2 mt-0.5">
                      <Icon className="h-5 w-5 text-primary" />
                    </div>
                    <div>
                      <CardTitle className="text-base">{notif.label}</CardTitle>
                      <CardDescription className="mt-1">
                        {notif.description}
                      </CardDescription>
                    </div>
                  </div>
                  <div className="flex gap-2 flex-wrap justify-end">
                    {notif.supportsEmail && (
                      <Badge variant="secondary" className="gap-1.5">
                        <Mail className="h-3 w-3" />
                        Email
                      </Badge>
                    )}
                    {notif.supportsSlack && (
                      <Badge variant="secondary" className="gap-1.5">
                        <SlackLogo className="h-3 w-3" />
                        Slack
                      </Badge>
                    )}
                  </div>
                </div>
              </CardHeader>
              <CardContent>
                <div className="flex gap-2 flex-wrap">
                  {notif.supportsEmail && (
                    <Button
                      size="sm"
                      variant="outline"
                      onClick={() => handleTest(notif.type, 'email')}
                      disabled={testMutation.isPending}
                    >
                      <Mail className="h-4 w-4 mr-2" />
                      Test Email
                    </Button>
                  )}
                  {notif.supportsSlack && (
                    <Button
                      size="sm"
                      variant="outline"
                      onClick={() => handleTest(notif.type, 'slack')}
                      disabled={testMutation.isPending}
                    >
                      <SlackLogo className="h-4 w-4 mr-2" />
                      Test Slack
                    </Button>
                  )}
                  {notif.supportsEmail && notif.supportsSlack && (
                    <Button
                      size="sm"
                      onClick={() => handleTest(notif.type, 'both')}
                      disabled={testMutation.isPending}
                    >
                      <Bell className="h-4 w-4 mr-2" />
                      Test Both
                    </Button>
                  )}
                </div>
              </CardContent>
            </Card>
          )
        })}
      </div>

      {/* Configuration Note */}
      <Card className="bg-muted/50 border-dashed">
        <CardContent className="pt-6">
          <div className="flex items-start gap-3">
            <AlertCircle className="h-5 w-5 text-muted-foreground mt-0.5" />
            <div className="space-y-1">
              <p className="text-sm font-medium">Configuration Required</p>
              <p className="text-sm text-muted-foreground">
                Email tests require SMTP configuration in your backend settings. 
                Slack tests require an organization with Slack integration enabled. 
                Configure these in your organization settings or environment variables.
              </p>
            </div>
          </div>
        </CardContent>
      </Card>
    </div>
  )
}
