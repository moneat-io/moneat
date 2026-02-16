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
import {DocPage, DocSection, DocSubSection, DocParagraph} from '@/components/docs/doc-page'
import {CodeBlock, InlineCode} from '@/components/docs/code-block'
import {Callout} from '@/components/docs/callout'

export const Route = createFileRoute('/docs/uptime-monitoring')({
  component: UptimeMonitoringPage,
})

function UptimeMonitoringPage() {
  return (
    <DocPage
      title="Uptime Monitoring"
      description="Monitor your endpoints and services with HTTP checks and heartbeat monitors."
    >
      <DocSection title="Overview">
        <DocParagraph>
          Moneat's uptime monitoring lets you track the availability of your websites, APIs, and
          background services. Get alerted instantly when something goes down, and track uptime
          history and response times over time.
        </DocParagraph>
      </DocSection>

      <DocSection title="Monitor Types">
        <DocSubSection title="HTTP Monitors">
          <DocParagraph>
            HTTP monitors send periodic requests to a URL and check the response. You can configure:
          </DocParagraph>
          <ul className="list-disc list-inside space-y-1 text-sm text-muted-foreground">
            <li><strong className="text-foreground">URL</strong> — The endpoint to check</li>
            <li><strong className="text-foreground">Check interval</strong> — How often to check (e.g., every 1, 5, or 10 minutes)</li>
            <li><strong className="text-foreground">Expected status code</strong> — The HTTP status code that indicates success (default: 200)</li>
            <li><strong className="text-foreground">Timeout</strong> — How long to wait before marking the check as failed</li>
            <li><strong className="text-foreground">HTTP method</strong> — GET, POST, HEAD, etc.</li>
          </ul>
        </DocSubSection>

        <DocSubSection title="Push / Heartbeat Monitors">
          <DocParagraph>
            Heartbeat monitors work in reverse — instead of Moneat checking your service, your service
            sends a "heartbeat" ping to Moneat at regular intervals. If Moneat doesn't receive a ping
            within the expected window, it marks the service as down.
          </DocParagraph>
          <DocParagraph>
            This is ideal for monitoring cron jobs, background workers, and scheduled tasks that run
            on a predictable schedule.
          </DocParagraph>
          <CodeBlock
            title="Heartbeat Ping (cURL)"
            language="bash"
            code={`# Add to the end of your cron job or scheduled task
curl -s https://api.moneat.io/v1/uptime/push/<monitor_token>`}
          />
          <Callout variant="tip" title="Easy integration">
            Just add a <InlineCode>curl</InlineCode> call to the end of your cron job script. If the job fails or
            hangs, the ping won't be sent and Moneat will alert you.
          </Callout>
        </DocSubSection>
      </DocSection>

      <DocSection title="Managing Monitors">
        <DocParagraph>
          Navigate to <strong className="text-foreground">Uptime</strong> in the sidebar to view and manage your monitors.
          From there you can:
        </DocParagraph>
        <ul className="list-disc list-inside space-y-1 text-sm text-muted-foreground">
          <li>Create new HTTP or heartbeat monitors</li>
          <li>View current status and uptime percentage</li>
          <li>See response time history and trends</li>
          <li>Pause and resume monitors temporarily</li>
          <li>Delete monitors you no longer need</li>
        </ul>
      </DocSection>

      <DocSection title="Alerting">
        <DocParagraph>
          When a monitor detects a failure, Moneat can create an incident and notify your team through
          the configured escalation policy. This integrates with the on-call system — so the right
          person gets paged when your service goes down.
        </DocParagraph>
        <Callout variant="info">
          Connect your monitors to escalation policies in the monitor settings to enable automatic
          incident creation on downtime.
        </Callout>
      </DocSection>

      <DocSection title="Status Page Integration">
        <DocParagraph>
          Monitors can be linked to status page components. When a monitor goes down, the corresponding
          component on your public status page is automatically updated to reflect the outage. See
          the <a href="/docs/status-pages" className="text-primary hover:underline">Status Pages</a> documentation
          for more details.
        </DocParagraph>
      </DocSection>
    </DocPage>
  )
}
