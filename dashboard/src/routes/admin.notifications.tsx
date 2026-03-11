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

import {createFileRoute} from '@tanstack/react-router'
import {useMutation, useQuery} from '@tanstack/react-query'
import {api} from '@/lib/api'
import {useState} from 'react'
import {Card, CardContent, CardDescription, CardHeader, CardTitle} from '@/components/ui/card'
import {Button} from '@/components/ui/button'
import {Alert, AlertDescription} from '@/components/ui/alert'
import {Badge} from '@/components/ui/badge'
import {Input} from '@/components/ui/input'
import {Label} from '@/components/ui/label'
import {useToast} from '@/hooks/useToast'
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
  Phone,
  MessageSquare,
} from 'lucide-react'
import {SectionHeader} from '@/components/AdminComponents'

const SlackLogo = ({ className }: { className?: string }) => (
  <svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 24 24" className={className}>
    <path fill="#E01E5A" d="M5.042 15.165a2.528 2.528 0 0 1-2.52 2.523A2.52 2.52 0 0 1 0 15.165a2.527 2.527 0 0 1 2.522-2.52h2.52v2.52zM6.313 15.165a2.527 2.527 0 0 1 2.521-2.52 2.522 2.522 0 0 1 2.521 2.52v6.313A2.52 2.52 0 0 1 8.834 24a2.528 2.528 0 0 1-2.521-2.522v-6.313z"/>
    <path fill="#36C5F0" d="M8.834 5.042a2.528 2.528 0 0 1-2.521-2.52A2.52 2.52 0 0 1 8.834 0a2.528 2.528 0 0 1 2.521 2.522v2.52h-2.521zM8.834 6.313a2.528 2.528 0 0 1 2.521 2.521 2.522 2.522 0 0 1-2.521 2.521H2.522A2.52 2.52 0 0 1 0 8.834a2.528 2.528 0 0 1 2.522-2.521h6.312z"/>
    <path fill="#2EB67D" d="M18.956 8.834a2.528 2.528 0 0 1 2.522-2.521A2.52 2.52 0 0 1 24 8.834a2.528 2.528 0 0 1-2.522 2.521h-2.522V8.834zM17.688 8.834a2.528 2.528 0 0 1-2.523 2.521 2.522 2.522 0 0 1-2.52-2.521V2.522A2.52 2.52 0 0 1 15.165 0a2.528 2.528 0 0 1 2.523 2.522v6.312z"/>
    <path fill="#ECB22E" d="M15.165 18.956a2.528 2.528 0 0 1 2.523 2.522A2.52 2.52 0 0 1 15.165 24a2.527 2.527 0 0 1-2.52-2.522v-2.522h2.52zM15.165 17.688a2.527 2.527 0 0 1-2.52-2.523 2.52 2.52 0 0 1 2.52-2.52h6.313A2.52 2.52 0 0 1 24 15.165a2.528 2.528 0 0 1-2.522 2.523h-6.313z"/>
  </svg>
)

const DiscordLogo = ({ className }: { className?: string }) => (
  <svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 24 24" className={className} fill="#5865F2">
    <path d="M20.317 4.3698a19.7913 19.7913 0 0 0-4.8851-1.5152.0741.0741 0 0 0-.0785.0371c-.211.3753-.4447.7748-.6096 1.1696-1.8703-.2772-3.7127-.2772-5.526 0-.1719-.4013-.4145-.7943-.6356-1.1696a.0741.0741 0 0 0-.0793-.0376 19.7363 19.7363 0 0 0-4.8859 1.5152.0699.0699 0 0 0-.0321.0277C.5334 9.0458-.319 13.5799.0992 18.0578a.0824.0824 0 0 0 .0312.0561c2.0528 1.5076 4.0413 2.4228 5.9929 3.0294a.0777.0777 0 0 0 .0842-.0276c.4616-.6304.8731-1.2952 1.226-1.9942a.076.076 0 0 0-.0416-.1057c-.6528-.2476-1.2743-.5495-1.8722-.8923a.077.077 0 0 1-.0076-.1277c.1258-.0943.2517-.1923.3718-.2914a.0743.0743 0 0 1 .0776-.0105c3.9278 1.7933 8.18 1.7933 12.0614 0a.0739.0739 0 0 1 .0785.0095c.1202.099.246.1981.3728.2924a.077.077 0 0 1-.0066.1276 12.2986 12.2986 0 0 1-1.873.8914.0766.0766 0 0 0-.0407.1067c.3604.698.7719 1.3628 1.225 1.9932a.076.076 0 0 0 .0842.0286c1.961-.6067 3.9495-1.5219 6.0023-3.0294a.077.077 0 0 0 .0313-.0552c.5004-5.177-.8382-9.6739-3.5485-13.6604a.061.061 0 0 0-.0312-.0286zM8.02 15.3312c-1.1825 0-2.1569-1.0857-2.1569-2.419 0-1.3332.9555-2.4189 2.157-2.4189 1.2108 0 2.1757 1.0952 2.1568 2.419 0 1.3332-.946 2.419-2.1569 2.419zm7.9748 0c-1.1825 0-2.1569-1.0857-2.1569-2.419 0-1.3332.9554-2.4189 2.1569-2.4189 1.2108 0 2.1757 1.0952 2.1568 2.419 0 1.3332-.946 2.419-2.1568 2.419z"/>
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
  | 'dashboard_alert'
  | 'verification'
  | 'password_reset'

type Channel = 'email' | 'slack' | 'discord' | 'both' | 'all'

interface TestNotificationResult {
  success: boolean
  emailSent: boolean
  slackSent: boolean
  discordSent?: boolean
  errors?: string[]
}

const notificationTypes: Array<{
  type: NotificationType
  label: string
  description: string
  icon: React.ComponentType<{ className?: string }>
  supportsEmail: boolean
  supportsSlack: boolean
  supportsDiscord: boolean
  slackLogo?: boolean
}> = [
  {
    type: 'error_alert',
    label: 'Error Alert',
    description: 'New error or exception detected in a project',
    icon: AlertCircle,
    supportsEmail: true,
    supportsSlack: true,
    supportsDiscord: true,
    slackLogo: false,
  },
  {
    type: 'weekly_summary',
    label: 'Weekly Summary',
    description: 'Weekly digest with stats and top issues',
    icon: TrendingUp,
    supportsEmail: true,
    supportsSlack: false,
    supportsDiscord: false,
  },
  {
    type: 'system_up',
    label: 'System Recovered',
    description: 'System monitoring - recovery notification',
    icon: ArrowUp,
    supportsEmail: true,
    supportsSlack: true,
    supportsDiscord: true,
  },
  {
    type: 'system_down',
    label: 'System Down',
    description: 'System monitoring - outage notification',
    icon: ArrowDown,
    supportsEmail: true,
    supportsSlack: true,
    supportsDiscord: true,
  },
  {
    type: 'uptime_alert',
    label: 'Uptime Monitor Alert',
    description: 'Monitor status change notification',
    icon: Activity,
    supportsEmail: false,
    supportsSlack: true,
    supportsDiscord: true,
  },
  {
    type: 'dashboard_alert',
    label: 'Dashboard Widget Alert',
    description: 'Threshold alert from a custom dashboard widget',
    icon: Bell,
    supportsEmail: true,
    supportsSlack: true,
    supportsDiscord: true,
  },
  {
    type: 'verification',
    label: 'Email Verification',
    description: 'Email address verification request',
    icon: Key,
    supportsEmail: true,
    supportsSlack: false,
    supportsDiscord: false,
  },
  {
    type: 'password_reset',
    label: 'Password Reset',
    description: 'Password reset request',
    icon: LockKeyhole,
    supportsEmail: true,
    supportsSlack: false,
    supportsDiscord: false,
  },
]

function AdminNotificationsPage() {
  const { toast } = useToast()
  const [testEmail, setTestEmail] = useState(() => localStorage.getItem('moneat_test_email') || '')
  const [lastResult, setLastResult] = useState<{
    type: NotificationType
    channel: Channel
    result: TestNotificationResult
  } | null>(null)

  const { data: onCallContact } = useQuery({
    queryKey: ['on-call-contact'],
    queryFn: () => api.getOnCallContact(),
  })

  // Save test email to localStorage when it changes
  const handleEmailChange = (email: string) => {
    setTestEmail(email)
    localStorage.setItem('moneat_test_email', email)
  }

  const smsCallMutation = useMutation({
    mutationFn: async ({channel}: {channel: 'sms' | 'call'}) => {
      return api.testSmsCall(channel)
    },
    onSuccess: (_, variables) => {
      const phone = onCallContact?.phoneNumber ?? ''
      toast({
        title: `Test ${variables.channel === 'sms' ? 'SMS' : 'call'} sent!`,
        description: `Test ${variables.channel === 'sms' ? 'SMS message sent' : 'call initiated'} to ${phone}`,
      })
    },
    onError: (error) => {
      toast({
        title: 'Failed to send test',
        description: error instanceof Error ? error.message : 'An error occurred',
        variant: 'destructive',
      })
    },
  })

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
        const sentChannels = [
          result.emailSent ? 'Email' : null,
          result.slackSent ? 'Slack' : null,
          result.discordSent ? 'Discord' : null,
        ].filter(Boolean) as string[]
        toast({
          title: 'Test notification sent!',
          description:
            sentChannels.length > 0
              ? `${sentChannels.join(', ')} notification${sentChannels.length > 1 ? 's' : ''} sent successfully.`
              : 'Notification sent successfully.',
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
        description="Send test notifications to verify your email, Slack, and Discord integrations are working correctly."
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

      {/* SMS/Call Test Section */}
      <Card>
        <CardHeader>
          <CardTitle className="flex items-center gap-2">
            <Phone className="h-5 w-5" />
            On-Call SMS &amp; Voice Call
          </CardTitle>
          <CardDescription>
            Test Twilio SMS and voice call alerts. Requires TWILIO_ACCOUNT_SID, TWILIO_AUTH_TOKEN, and TWILIO_FROM_NUMBER to be configured.
          </CardDescription>
        </CardHeader>
        <CardContent className="space-y-4">
          {onCallContact?.phoneNumber && onCallContact.onCallPhoneOptIn ? (
            <p className="text-sm text-muted-foreground">
              Will send to your configured on-call number: <strong>{onCallContact.phoneNumber}</strong>
            </p>
          ) : (
            <Alert>
              <AlertCircle className="h-4 w-4" />
              <AlertDescription>
                No consented on-call phone number configured. Set up your on-call contact in{' '}
                <strong>Settings → Notifications</strong> before testing.
              </AlertDescription>
            </Alert>
          )}
          <div className="flex gap-2">
            <Badge variant="secondary" className="gap-1.5">
              <MessageSquare className="h-3 w-3" />
              SMS
            </Badge>
            <Badge variant="secondary" className="gap-1.5">
              <Phone className="h-3 w-3" />
              Voice Call
            </Badge>
          </div>
          <div className="flex gap-2 flex-wrap">
            <Button
              size="sm"
              variant="outline"
              onClick={() => smsCallMutation.mutate({channel: 'sms'})}
              disabled={smsCallMutation.isPending || !onCallContact?.phoneNumber || !onCallContact.onCallPhoneOptIn}
            >
              <MessageSquare className="h-4 w-4 mr-2" />
              Test SMS
            </Button>
            <Button
              size="sm"
              variant="outline"
              onClick={() => smsCallMutation.mutate({channel: 'call'})}
              disabled={smsCallMutation.isPending || !onCallContact?.phoneNumber || !onCallContact.onCallPhoneOptIn}
            >
              <Phone className="h-4 w-4 mr-2" />
              Test Call
            </Button>
          </div>
          <p className="text-xs text-muted-foreground">
            SMS/call fallback fires automatically when a user doesn't acknowledge a push/Slack alert within the configured delay per escalation step.
          </p>
        </CardContent>
      </Card>

      {/* Info Alert */}
      <Alert>
        <Bell className="h-4 w-4" />
        <AlertDescription>
          Test notifications are clearly marked as <strong>[TEST]</strong> to avoid confusion. 
          Slack and Discord tests require configured integrations in Settings → Integrations.
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
              {lastResult.result.discordSent && (
                <p className="text-sm">✓ Discord message sent successfully</p>
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
          const combinedChannel: Channel | null =
            notif.supportsEmail && notif.supportsSlack && notif.supportsDiscord
              ? 'all'
              : notif.supportsEmail && notif.supportsSlack
                ? 'both'
                : null
          
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
                    {notif.supportsDiscord && (
                      <Badge variant="secondary" className="gap-1.5">
                        <DiscordLogo className="h-3 w-3" />
                        Discord
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
                  {notif.supportsDiscord && (
                    <Button
                      size="sm"
                      variant="outline"
                      onClick={() => handleTest(notif.type, 'discord')}
                      disabled={testMutation.isPending}
                    >
                      <DiscordLogo className="h-4 w-4 mr-2" />
                      Test Discord
                    </Button>
                  )}
                  {combinedChannel && (
                    <Button
                      size="sm"
                      onClick={() => handleTest(notif.type, combinedChannel)}
                      disabled={testMutation.isPending}
                    >
                      <Bell className="h-4 w-4 mr-2" />
                      {combinedChannel === 'all' ? 'Test All' : 'Test Both'}
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
                Slack and Discord tests require an organization with the respective integration enabled. 
                Configure these in your organization settings or environment variables.
              </p>
            </div>
          </div>
        </CardContent>
      </Card>
    </div>
  )
}
