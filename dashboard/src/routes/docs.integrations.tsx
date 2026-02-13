import {createFileRoute} from '@tanstack/react-router'
import {DocPage, DocSection, DocSubSection, DocParagraph} from '@/components/docs/doc-page'
import {Callout} from '@/components/docs/callout'
import {StepList} from '@/components/docs/step-list'

export const Route = createFileRoute('/docs/integrations')({
  component: IntegrationsPage,
})

function IntegrationsPage() {
  return (
    <DocPage
      title="Integrations"
      description="Connect Slack, Discord, and webhooks to receive notifications where your team works."
    >
      <DocSection title="Overview">
        <DocParagraph>
          Moneat integrates with your team's communication tools so you're notified about errors,
          incidents, and uptime issues in real-time. Integrations are configured at the organization
          level under <strong className="text-foreground">Settings</strong>.
        </DocParagraph>
      </DocSection>

      <DocSection title="Slack">
        <DocSubSection title="Setting Up Slack">
          <StepList
            steps={[
              {
                title: 'Connect your workspace',
                content: 'Go to Settings → Integrations → Slack and click "Connect Slack". You\'ll be redirected to Slack to authorize the Moneat app.',
              },
              {
                title: 'Select a notification channel',
                content: 'Choose which Slack channel should receive error and incident notifications. You can change this at any time.',
              },
              {
                title: 'Test the connection',
                content: 'Click "Test" to send a test notification to your selected channel and verify everything is working.',
              },
            ]}
          />
        </DocSubSection>

        <DocSubSection title="Slack Features">
          <ul className="list-disc list-inside space-y-2 text-sm text-muted-foreground">
            <li><strong className="text-foreground">Error notifications</strong> — New and reopened issues are posted to your channel</li>
            <li><strong className="text-foreground">Incident alerts</strong> — On-call incidents are sent as DMs with interactive acknowledge/resolve buttons</li>
            <li><strong className="text-foreground">Usergroup sync</strong> — Link on-call schedules to Slack usergroups so @mentions always reach the current responder</li>
          </ul>
        </DocSubSection>

        <Callout variant="tip" title="Interactive buttons">
          Slack notifications for on-call incidents include interactive buttons. Responders can
          acknowledge and resolve incidents directly from Slack without opening the dashboard.
        </Callout>
      </DocSection>

      <DocSection title="Discord">
        <DocParagraph>
          Connect Discord to receive error and incident notifications in your server channels.
          The setup process is similar to Slack — authorize the Moneat bot, select a channel, and
          you're ready to go.
        </DocParagraph>
      </DocSection>

      <DocSection title="Webhooks">
        <DocParagraph>
          For custom integrations, you can configure webhook endpoints. Moneat will send HTTP
          POST requests with event data to your webhook URL when errors or incidents occur.
          This lets you integrate with any service that accepts webhooks.
        </DocParagraph>
      </DocSection>

      <DocSection title="Managing Integrations">
        <DocParagraph>
          All integrations can be managed from <strong className="text-foreground">Settings → Integrations</strong>.
          From there you can:
        </DocParagraph>
        <ul className="list-disc list-inside space-y-1 text-sm text-muted-foreground">
          <li>View connected integrations and their status</li>
          <li>Update notification channel preferences</li>
          <li>Test integration connectivity</li>
          <li>Disconnect integrations you no longer need</li>
        </ul>
      </DocSection>
    </DocPage>
  )
}
