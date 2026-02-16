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

import {createFileRoute} from '@tanstack/react-router'
import {DocPage, DocSection, DocSubSection, DocParagraph} from '@/components/docs/doc-page'
import {CodeBlock, InlineCode} from '@/components/docs/code-block'
import {Callout} from '@/components/docs/callout'

export const Route = createFileRoute('/docs/error-monitoring')({
  component: ErrorMonitoringPage,
})

function ErrorMonitoringPage() {
  return (
    <DocPage
      title="Error Monitoring"
      description="Capture, track, and analyze errors in real-time using Sentry-compatible ingestion."
    >
      <DocSection title="How It Works">
        <DocParagraph>
          Moneat receives error events through a Sentry-compatible ingestion API. When your application encounters
          an error, the SDK packages it into an envelope and sends it to Moneat's ingestion endpoint. Moneat
          processes the event, extracts metadata, generates a fingerprint for grouping, and stores it for analysis.
        </DocParagraph>
      </DocSection>

      <DocSection title="Ingestion Endpoint">
        <DocParagraph>
          Events are sent to the envelope endpoint using the Sentry protocol. The SDK handles this
          automatically — you don't need to call this directly.
        </DocParagraph>
        <CodeBlock
          language="http"
          code={`POST /api/{projectId}/envelope/
Content-Type: application/x-sentry-envelope
X-Sentry-Auth: Sentry sentry_key=<public_key>,sentry_version=7`}
        />
        <DocParagraph>
          The endpoint also supports <InlineCode>gzip</InlineCode> compression for reduced bandwidth. Events are
          processed asynchronously and typically appear in the dashboard within seconds.
        </DocParagraph>
      </DocSection>

      <DocSection title="Event Data">
        <DocParagraph>
          Each error event contains rich context that helps you diagnose issues quickly:
        </DocParagraph>
        <ul className="list-disc list-inside space-y-2 text-sm text-muted-foreground">
          <li><strong className="text-foreground">Exception details</strong> — Type, message, and full stack trace</li>
          <li><strong className="text-foreground">Breadcrumbs</strong> — Trail of events leading up to the error</li>
          <li><strong className="text-foreground">Tags</strong> — Key-value pairs for filtering (e.g., environment, release)</li>
          <li><strong className="text-foreground">User context</strong> — User ID, email, IP address</li>
          <li><strong className="text-foreground">Device/OS info</strong> — Platform, browser, OS version</li>
          <li><strong className="text-foreground">Request data</strong> — URL, method, headers (for web apps)</li>
          <li><strong className="text-foreground">Custom context</strong> — Any additional data you attach via the SDK</li>
        </ul>
      </DocSection>

      <DocSection title="Error Grouping">
        <DocSubSection title="Automatic Fingerprinting">
          <DocParagraph>
            Moneat automatically groups similar errors into issues using fingerprinting. The fingerprint is
            generated from a combination of:
          </DocParagraph>
          <ul className="list-disc list-inside space-y-1 text-sm text-muted-foreground">
            <li>Exception type and message</li>
            <li>Top 3 stack trace frames</li>
            <li>Platform identifier</li>
          </ul>
          <DocParagraph>
            This means the same error occurring multiple times across different users will be grouped into a
            single issue, making it easy to see how widespread a problem is.
          </DocParagraph>
        </DocSubSection>

        <DocSubSection title="Custom Fingerprinting">
          <DocParagraph>
            You can override the default grouping by providing a custom fingerprint in the SDK:
          </DocParagraph>
          <CodeBlock
            language="javascript"
            code={`Sentry.captureException(error, {
  fingerprint: ["custom-group-key"],
});`}
          />
        </DocSubSection>
      </DocSection>

      <DocSection title="Quota & Rate Limiting">
        <DocParagraph>
          Moneat enforces quotas based on your plan tier. When your event quota is exceeded, new events
          are dropped with a <InlineCode>429 Too Many Requests</InlineCode> response. The SDK automatically
          handles rate limiting and will retry when capacity is available.
        </DocParagraph>
        <Callout variant="info" title="Usage tracking">
          Monitor your event usage in the dashboard under Settings &amp; Billing. You can set up budget
          alerts to get notified before reaching your quota.
        </Callout>
      </DocSection>

      <DocSection title="Supported Platforms">
        <DocParagraph>
          Since Moneat is Sentry-compatible, any platform with a Sentry SDK is supported. Popular platforms include:
        </DocParagraph>
        <div className="grid grid-cols-2 sm:grid-cols-3 gap-2 text-sm text-muted-foreground">
          {['JavaScript', 'TypeScript', 'Python', 'Java', 'Kotlin', 'Swift', 'Go', 'Ruby', 'PHP', '.NET', 'Rust', 'Elixir', 'React', 'Vue', 'Angular', 'Next.js', 'Django', 'Flask', 'Spring', 'Android', 'iOS', 'React Native', 'Flutter', 'Node.js'].map((platform) => (
            <div key={platform} className="px-3 py-1.5 rounded bg-muted/50 text-center">{platform}</div>
          ))}
        </div>
      </DocSection>
    </DocPage>
  )
}
