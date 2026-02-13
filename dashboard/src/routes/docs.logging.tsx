import {createFileRoute} from '@tanstack/react-router'
import {DocPage, DocSection, DocSubSection, DocParagraph} from '@/components/docs/doc-page'
import {CodeBlock, InlineCode} from '@/components/docs/code-block'
import {Callout} from '@/components/docs/callout'

export const Route = createFileRoute('/docs/logging')({
  component: LoggingPage,
})

function LoggingPage() {
  return (
    <DocPage
      title="Structured Logging"
      description="Ingest, search, and stream logs using OpenTelemetry and real-time tailing."
    >
      <DocSection title="Overview">
        <DocParagraph>
          Moneat supports structured log ingestion via the OpenTelemetry Logs Protocol (OTLP). Send
          structured logs from your applications and search, filter, and tail them in real-time
          through the dashboard.
        </DocParagraph>
      </DocSection>

      <DocSection title="Ingestion">
        <DocSubSection title="OTLP Endpoint">
          <DocParagraph>
            Send logs to Moneat using the OTLP HTTP endpoint. Configure your OpenTelemetry SDK or
            collector to export logs to:
          </DocParagraph>
          <CodeBlock
            language="text"
            code="POST https://api.moneat.io/v1/logs/otlp"
          />
        </DocSubSection>

        <DocSubSection title="SDK Ingestion">
          <DocParagraph>
            You can also send logs through the Sentry SDK's ingestion endpoint. Logs sent as part
            of a Sentry envelope are automatically processed and stored:
          </DocParagraph>
          <CodeBlock
            language="text"
            code="POST /api/{projectId}/logs/"
          />
        </DocSubSection>
      </DocSection>

      <DocSection title="Searching Logs">
        <DocParagraph>
          Navigate to your project's <strong className="text-foreground">Logs</strong> tab to search and filter logs.
          The search interface supports:
        </DocParagraph>
        <ul className="list-disc list-inside space-y-1 text-sm text-muted-foreground">
          <li><strong className="text-foreground">Full-text search</strong> — Search across log messages</li>
          <li><strong className="text-foreground">Level filtering</strong> — Filter by log level (debug, info, warn, error, fatal)</li>
          <li><strong className="text-foreground">Time range</strong> — Narrow results to a specific time window</li>
          <li><strong className="text-foreground">Attribute filtering</strong> — Filter by structured log attributes</li>
        </ul>
        <Callout variant="tip" title="Pro tip">
          Use structured log attributes (like <InlineCode>user_id</InlineCode>, <InlineCode>request_id</InlineCode>,
          or <InlineCode>service</InlineCode>) to quickly narrow down relevant log entries.
        </Callout>
      </DocSection>

      <DocSection title="Real-Time Tailing">
        <DocParagraph>
          Moneat supports real-time log tailing via WebSocket. In the dashboard, click the
          "Live Tail" button to stream logs as they arrive. You can apply filters to the live
          stream to focus on specific log levels or attributes.
        </DocParagraph>
        <Callout variant="info">
          Live tailing uses a WebSocket connection at <InlineCode>/v1/logs/tail</InlineCode>.
          The connection stays open and streams new log entries matching your filters in real-time.
        </Callout>
      </DocSection>

      <DocSection title="Log Retention">
        <DocParagraph>
          Logs are stored in ClickHouse for high-performance querying. Retention periods depend on
          your plan tier. Check your plan details under <a href="/docs/billing" className="text-primary hover:underline">Billing &amp; Plans</a> for
          specific retention limits.
        </DocParagraph>
      </DocSection>
    </DocPage>
  )
}
