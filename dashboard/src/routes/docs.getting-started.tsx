import {createFileRoute} from '@tanstack/react-router'
import {DocPage, DocSection, DocParagraph} from '@/components/docs/doc-page'
import {CodeBlock} from '@/components/docs/code-block'
import {Callout} from '@/components/docs/callout'
import {StepList} from '@/components/docs/step-list'

export const Route = createFileRoute('/docs/getting-started')({
  component: GettingStartedPage,
})

function GettingStartedPage() {
  return (
    <DocPage
      title="Getting Started"
      description="Create your account, set up a project, and capture your first error in minutes."
    >
      <DocSection title="Create Your Account">
        <StepList
          steps={[
            {
              title: 'Sign up',
              content: (
                <>
                  Visit <a href="https://moneat.io/signup" className="text-primary hover:underline font-medium">moneat.io/signup</a> to
                  create your account. You can sign up with email/password, GitHub, or Apple.
                </>
              ),
            },
            {
              title: 'Complete onboarding',
              content: 'After signing up, follow the onboarding flow to create your organization and first project.',
            },
            {
              title: 'Get your DSN',
              content: (
                <>
                  Once your project is created, you'll receive a DSN (Data Source Name). This is the connection
                  string your SDK uses to send events to Moneat. It looks like:
                </>
              ),
            },
          ]}
        />
        <CodeBlock
          language="text"
          code="https://<public_key>@api.moneat.io/api/<project_id>"
        />
      </DocSection>

      <DocSection title="Install an SDK">
        <DocParagraph>
          Moneat is fully compatible with Sentry SDKs. Use the official Sentry SDK for your platform — over 90
          platforms are supported including JavaScript, Python, Java, Kotlin, Swift, Go, Ruby, and more.
        </DocParagraph>

        <CodeBlock
          title="JavaScript / Node.js"
          language="bash"
          code="npm install @sentry/node"
        />

        <CodeBlock
          title="Python"
          language="bash"
          code="pip install sentry-sdk"
        />

        <CodeBlock
          title="Kotlin / Android"
          language="groovy"
          code={`// build.gradle.kts
implementation("io.sentry:sentry-android:7.0.0")`}
        />
      </DocSection>

      <DocSection title="Configure the SDK">
        <DocParagraph>
          Point the SDK to your Moneat instance by setting the DSN from your project settings.
        </DocParagraph>

        <CodeBlock
          title="JavaScript"
          language="javascript"
          code={`import * as Sentry from "@sentry/node";

Sentry.init({
  dsn: "https://<public_key>@api.moneat.io/api/<project_id>",
  tracesSampleRate: 1.0,
});`}
        />

        <CodeBlock
          title="Python"
          language="python"
          code={`import sentry_sdk

sentry_sdk.init(
    dsn="https://<public_key>@api.moneat.io/api/<project_id>",
    traces_sample_rate=1.0,
)`}
        />
      </DocSection>

      <DocSection title="Capture Your First Error">
        <DocParagraph>
          Once the SDK is initialized, errors are automatically captured. You can also manually capture exceptions
          and messages.
        </DocParagraph>

        <CodeBlock
          title="JavaScript"
          language="javascript"
          code={`// Automatic - unhandled errors are captured
throw new Error("Something went wrong!");

// Manual capture
Sentry.captureException(new Error("Manual error"));
Sentry.captureMessage("Something noteworthy happened");`}
        />

        <Callout variant="tip" title="Verify your setup">
          After initializing the SDK, trigger a test error and check the Moneat dashboard. Your error
          should appear within seconds under the Issues tab.
        </Callout>
      </DocSection>

      <DocSection title="Next Steps">
        <DocParagraph>
          Now that you're capturing errors, explore these features to get the most out of Moneat:
        </DocParagraph>
        <ul className="list-disc list-inside space-y-2 text-sm text-muted-foreground">
          <li><a href="/docs/error-monitoring" className="text-primary hover:underline">Error Monitoring</a> — Learn about event ingestion, grouping, and context</li>
          <li><a href="/docs/issue-tracking" className="text-primary hover:underline">Issue Tracking</a> — Triage and resolve issues efficiently</li>
          <li><a href="/docs/on-call" className="text-primary hover:underline">On-Call & Incidents</a> — Set up alerting and escalation policies</li>
          <li><a href="/docs/sdk-setup" className="text-primary hover:underline">SDK Setup</a> — Platform-specific SDK configuration guides</li>
          <li><a href="/docs/integrations" className="text-primary hover:underline">Integrations</a> — Connect Slack, Discord, and more</li>
        </ul>
      </DocSection>
    </DocPage>
  )
}
