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
import {platforms} from './projects'

export const Route = createFileRoute('/docs/sdk-setup')({
  component: SdkSetupPage,
})

function SdkSetupPage() {
  return (
    <DocPage
      title="SDK Setup"
      description="Install and configure Sentry-compatible SDKs for your platform to start capturing errors."
    >
      <DocSection title="Sentry SDK Compatibility">
        <DocParagraph>
          Moneat uses the Sentry protocol for event ingestion, which means you can use any official
          Sentry SDK to send errors to Moneat. Simply point the DSN to your Moneat instance and
          everything works out of the box — no custom SDK needed.
        </DocParagraph>
        <Callout variant="info" title="90+ platforms supported">
          Any platform with a Sentry SDK works with Moneat. This includes all major languages,
          frameworks, and mobile platforms.
        </Callout>
      </DocSection>

      <DocSection title="Finding Your DSN">
        <DocParagraph>
          Your DSN (Data Source Name) is available in your project settings. Navigate to
          <strong className="text-foreground"> Projects → [Your Project] → Settings</strong> to find it.
          The DSN format is:
        </DocParagraph>
        <CodeBlock
          language="text"
          code="https://<public_key>@api.moneat.io/api/<project_id>"
        />
      </DocSection>

      <DocSection title="JavaScript / Node.js">
        <DocSubSection title="Installation">
          <CodeBlock language="bash" code="npm install @sentry/node" />
        </DocSubSection>
        <DocSubSection title="Configuration">
          <CodeBlock
            language="javascript"
            title="instrument.js"
            code={`import * as Sentry from "@sentry/node";

Sentry.init({
  dsn: "https://<public_key>@api.moneat.io/api/<project_id>",

  // Performance monitoring
  tracesSampleRate: 1.0,

  // Set the release version
  release: "my-app@1.0.0",

  // Set the environment
  environment: "production",
});`}
          />
        </DocSubSection>
      </DocSection>

      <DocSection title="React">
        <DocSubSection title="Installation">
          <CodeBlock language="bash" code="npm install @sentry/react" />
        </DocSubSection>
        <DocSubSection title="Configuration">
          <CodeBlock
            language="javascript"
            title="main.tsx"
            code={`import * as Sentry from "@sentry/react";

Sentry.init({
  dsn: "https://<public_key>@api.moneat.io/api/<project_id>",
  integrations: [
    Sentry.browserTracingIntegration(),
    Sentry.replayIntegration(),
  ],
  tracesSampleRate: 1.0,
  replaysSessionSampleRate: 0.1,
  replaysOnErrorSampleRate: 1.0,
});`}
          />
        </DocSubSection>
      </DocSection>

      <DocSection title="Python">
        <DocSubSection title="Installation">
          <CodeBlock language="bash" code="pip install sentry-sdk" />
        </DocSubSection>
        <DocSubSection title="Configuration">
          <CodeBlock
            language="python"
            title="app.py"
            code={`import sentry_sdk

sentry_sdk.init(
    dsn="https://<public_key>@api.moneat.io/api/<project_id>",
    traces_sample_rate=1.0,
    release="my-app@1.0.0",
    environment="production",
)`}
          />
        </DocSubSection>
        <DocParagraph>
          The Python SDK has built-in integrations for Django, Flask, FastAPI, Celery, and more.
          They are auto-discovered — just install <InlineCode>sentry-sdk</InlineCode> and call <InlineCode>init()</InlineCode>.
        </DocParagraph>
      </DocSection>

      <DocSection title="Kotlin / Android">
        <DocSubSection title="Installation">
          <CodeBlock
            language="groovy"
            title="build.gradle.kts"
            code={`dependencies {
    implementation("io.sentry:sentry-android:7.0.0")
}`}
          />
        </DocSubSection>
        <DocSubSection title="Configuration">
          <CodeBlock
            language="xml"
            title="AndroidManifest.xml"
            code={`<application>
  <meta-data
    android:name="io.sentry.dsn"
    android:value="https://<public_key>@api.moneat.io/api/<project_id>" />
  <meta-data
    android:name="io.sentry.traces.sample-rate"
    android:value="1.0" />
</application>`}
          />
        </DocSubSection>
      </DocSection>

      <DocSection title="Swift / iOS">
        <DocSubSection title="Installation">
          <DocParagraph>
            Add the Sentry SDK via Swift Package Manager:
          </DocParagraph>
          <CodeBlock
            language="text"
            code="https://github.com/getsentry/sentry-cocoa.git"
          />
        </DocSubSection>
        <DocSubSection title="Configuration">
          <CodeBlock
            language="swift"
            title="AppDelegate.swift"
            code={`import Sentry

SentrySDK.start { options in
    options.dsn = "https://<public_key>@api.moneat.io/api/<project_id>"
    options.tracesSampleRate = 1.0
    options.environment = "production"
}`}
          />
        </DocSubSection>
      </DocSection>

      <DocSection title="Go">
        <DocSubSection title="Installation">
          <CodeBlock language="bash" code="go get github.com/getsentry/sentry-go" />
        </DocSubSection>
        <DocSubSection title="Configuration">
          <CodeBlock
            language="go"
            title="main.go"
            code={`import "github.com/getsentry/sentry-go"

func main() {
    sentry.Init(sentry.ClientOptions{
        Dsn:              "https://<public_key>@api.moneat.io/api/<project_id>",
        TracesSampleRate: 1.0,
        Release:          "my-app@1.0.0",
        Environment:      "production",
    })
    defer sentry.Flush(2 * time.Second)
}`}
          />
        </DocSubSection>
      </DocSection>

      <DocSection title="Other Platforms">
        <DocParagraph>
          Moneat works with any Sentry SDK. For platforms not listed above, refer to the
          official <a href="https://docs.sentry.io/platforms/" className="text-primary hover:underline" target="_blank" rel="noopener noreferrer">Sentry SDK documentation</a> and
          replace the DSN with your Moneat DSN.
        </DocParagraph>
        <div className="grid grid-cols-2 sm:grid-cols-4 gap-2">
          {platforms
            .filter(p => !['android', 'ios', 'kmp', 'react', 'node', 'python', 'kotlin', 'web', 'other'].includes(p.id))
            .map((platform) => {
              const Icon = platform.icon
              return (
                <div 
                  key={platform.id} 
                  className="flex items-center gap-2 px-3 py-2 rounded-lg border hover:bg-accent/50 transition-colors"
                >
                  <div className="p-2 rounded-lg" style={{ backgroundColor: platform.color }}>
                    <Icon className="h-5 w-5 text-white" />
                  </div>
                  <span className="text-xs font-medium">{platform.name}</span>
                </div>
              )
            })}
        </div>
      </DocSection>

      <DocSection title="Common Options">
        <DocParagraph>
          These SDK options work across all platforms:
        </DocParagraph>
        <ul className="list-disc list-inside space-y-2 text-sm text-muted-foreground">
          <li><InlineCode>dsn</InlineCode> — Your Moneat project DSN (required)</li>
          <li><InlineCode>release</InlineCode> — Your app version for release tracking</li>
          <li><InlineCode>environment</InlineCode> — Deployment environment (production, staging, etc.)</li>
          <li><InlineCode>tracesSampleRate</InlineCode> — Percentage of transactions to capture for performance monitoring (0.0 to 1.0)</li>
          <li><InlineCode>beforeSend</InlineCode> — Callback to modify or filter events before sending</li>
          <li><InlineCode>sampleRate</InlineCode> — Percentage of error events to capture (0.0 to 1.0)</li>
        </ul>
        <Callout variant="tip" title="Start with full sampling">
          Set <InlineCode>tracesSampleRate: 1.0</InlineCode> during development. In production, lower it
          to reduce data volume and costs (e.g., 0.1 for 10% sampling).
        </Callout>
      </DocSection>
    </DocPage>
  )
}
