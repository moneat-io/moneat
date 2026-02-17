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
import {StepList} from '@/components/docs/step-list'

export const Route = createFileRoute('/docs/ai-observability')({
  component: AiObservabilityPage,
})

function AiObservabilityPage() {
  return (
    <DocPage
      title="AI Observability"
      description="Monitor your LLM applications, trace agent executions, and track token usage and costs."
    >
      <DocSection title="Overview">
        <DocParagraph>
          Moneat provides full observability for AI-powered applications. Whether you're building
          chatbots, AI agents, RAG pipelines, or tool-calling workflows, Moneat captures every LLM
          call, traces multi-step agent executions, and tracks token usage and costs across all your
          models and providers.
        </DocParagraph>
        <ul className="list-disc list-inside space-y-1 text-sm text-muted-foreground">
          <li><strong className="text-foreground">Trace LLM calls</strong> — See every generation with full input/output, model, latency, and token counts</li>
          <li><strong className="text-foreground">Agent tracing</strong> — Visualize multi-step agent executions as trace waterfalls with parent/child relationships</li>
          <li><strong className="text-foreground">Cost tracking</strong> — Monitor spend per model, provider, and over time</li>
          <li><strong className="text-foreground">Error monitoring</strong> — Catch LLM failures, timeouts, and rate limits in real-time</li>
          <li><strong className="text-foreground">Model analytics</strong> — Compare performance, cost, and error rates across models</li>
        </ul>
      </DocSection>

      <DocSection title="Getting Started with Sentry SDKs">
        <DocParagraph>
          Moneat is fully compatible with Sentry SDKs. If your application already uses a Sentry SDK
          with AI integrations, you can start sending LLM observability data to Moneat immediately —
          just point your DSN to your Moneat instance.
        </DocParagraph>

        <StepList
          steps={[
            {
              title: 'Choose your platform',
              content: (
                <>
                  Visit the{' '}
                  <a
                    href="https://sentry.io/solutions/ai-observability/"
                    target="_blank"
                    rel="noopener noreferrer"
                    className="text-primary hover:underline font-medium"
                  >
                    Sentry AI Observability page
                  </a>{' '}
                  and select your platform. Sentry supports OpenAI Agents, Vercel AI, LangChain,
                  and many more frameworks out of the box.
                </>
              ),
            },
            {
              title: 'Install the Sentry SDK',
              content: (
                <>
                  Follow the official Sentry setup instructions for your platform to install and configure
                  the SDK with AI integrations enabled.
                </>
              ),
            },
            {
              title: 'Point the DSN to Moneat',
              content: (
                <>
                  Instead of using a Sentry DSN, configure the SDK to point to your Moneat instance.
                  You can find your project DSN in your project settings. It follows this format:
                </>
              ),
            },
          ]}
        />
        <CodeBlock
          language="text"
          code="https://<public_key>@<your-moneat-host>/api/<project_id>"
        />

        <Callout variant="info" title="Automatic detection">
          Moneat automatically detects <InlineCode>ai.*</InlineCode> spans from Sentry SDK transactions
          (such as <InlineCode>ai.chat_completion</InlineCode>, <InlineCode>ai.embedding</InlineCode>,
          and <InlineCode>ai.tool_call</InlineCode>) and records them as LLM generations. No additional
          configuration is required beyond pointing your DSN to Moneat.
        </Callout>
      </DocSection>

      <DocSection title="Direct Ingestion API">
        <DocParagraph>
          If you're not using a Sentry SDK, you can send LLM generation events directly to Moneat's
          ingestion API. This is useful for custom instrumentation or integrating with frameworks
          that don't have a Sentry integration.
        </DocParagraph>

        <DocSubSection title="Endpoint">
          <CodeBlock
            language="text"
            code="POST /api/{projectId}/llm/"
          />
          <DocParagraph>
            Authenticate using either the <InlineCode>X-Sentry-Auth</InlineCode> header or
            the <InlineCode>sentry_key</InlineCode> query parameter with your project's public key.
            The endpoint accepts gzip-compressed payloads via
            the <InlineCode>Content-Encoding: gzip</InlineCode> header.
          </DocParagraph>
        </DocSubSection>

        <DocSubSection title="Payload Format">
          <DocParagraph>
            Send a JSON object containing a <InlineCode>generations</InlineCode> array. Each generation
            represents a single LLM call:
          </DocParagraph>
          <CodeBlock
            language="json"
            title="Example payload"
            code={`{
  "generations": [
    {
      "trace_id": "abc123",
      "span_id": "span1",
      "parent_span_id": "",
      "name": "chat_completion",
      "model": "gpt-4o",
      "provider": "openai",
      "type": "chat",
      "input": [{"role": "user", "content": "Hello"}],
      "output": {"role": "assistant", "content": "Hi there!"},
      "input_tokens": 10,
      "output_tokens": 5,
      "cost_usd": 0.0003,
      "duration_ms": 450,
      "status": "success",
      "timestamp": "2026-02-17T10:00:00Z",
      "tags": {"user_id": "u123"},
      "metadata": {}
    }
  ]
}`}
          />
        </DocSubSection>

        <DocSubSection title="Generation Fields">
          <DocParagraph>
            Each generation object supports the following fields:
          </DocParagraph>
          <div className="overflow-x-auto">
            <table className="w-full text-sm">
              <thead>
                <tr className="border-b text-left">
                  <th className="py-2 pr-4 font-medium">Field</th>
                  <th className="py-2 pr-4 font-medium">Type</th>
                  <th className="py-2 font-medium">Description</th>
                </tr>
              </thead>
              <tbody className="text-muted-foreground">
                <tr className="border-b"><td className="py-2 pr-4"><InlineCode>trace_id</InlineCode></td><td className="py-2 pr-4">string</td><td className="py-2">Groups related generations into a single trace</td></tr>
                <tr className="border-b"><td className="py-2 pr-4"><InlineCode>span_id</InlineCode></td><td className="py-2 pr-4">string</td><td className="py-2">Unique identifier for this generation</td></tr>
                <tr className="border-b"><td className="py-2 pr-4"><InlineCode>parent_span_id</InlineCode></td><td className="py-2 pr-4">string</td><td className="py-2">Parent span for building trace trees</td></tr>
                <tr className="border-b"><td className="py-2 pr-4"><InlineCode>name</InlineCode></td><td className="py-2 pr-4">string</td><td className="py-2">Operation name (e.g. "chat_completion")</td></tr>
                <tr className="border-b"><td className="py-2 pr-4"><InlineCode>model</InlineCode></td><td className="py-2 pr-4">string</td><td className="py-2">Model identifier (e.g. "gpt-4o", "claude-3.5-sonnet")</td></tr>
                <tr className="border-b"><td className="py-2 pr-4"><InlineCode>provider</InlineCode></td><td className="py-2 pr-4">string</td><td className="py-2">Provider name (e.g. "openai", "anthropic")</td></tr>
                <tr className="border-b"><td className="py-2 pr-4"><InlineCode>type</InlineCode></td><td className="py-2 pr-4">string</td><td className="py-2">Generation type (see supported types below)</td></tr>
                <tr className="border-b"><td className="py-2 pr-4"><InlineCode>input</InlineCode></td><td className="py-2 pr-4">JSON</td><td className="py-2">Input messages or prompt</td></tr>
                <tr className="border-b"><td className="py-2 pr-4"><InlineCode>output</InlineCode></td><td className="py-2 pr-4">JSON</td><td className="py-2">Model response or completion</td></tr>
                <tr className="border-b"><td className="py-2 pr-4"><InlineCode>input_tokens</InlineCode></td><td className="py-2 pr-4">integer</td><td className="py-2">Number of input/prompt tokens</td></tr>
                <tr className="border-b"><td className="py-2 pr-4"><InlineCode>output_tokens</InlineCode></td><td className="py-2 pr-4">integer</td><td className="py-2">Number of output/completion tokens</td></tr>
                <tr className="border-b"><td className="py-2 pr-4"><InlineCode>cost_usd</InlineCode></td><td className="py-2 pr-4">number</td><td className="py-2">Cost of this generation in USD</td></tr>
                <tr className="border-b"><td className="py-2 pr-4"><InlineCode>duration_ms</InlineCode></td><td className="py-2 pr-4">number</td><td className="py-2">Duration in milliseconds</td></tr>
                <tr className="border-b"><td className="py-2 pr-4"><InlineCode>status</InlineCode></td><td className="py-2 pr-4">string</td><td className="py-2">"success" or "error"</td></tr>
                <tr className="border-b"><td className="py-2 pr-4"><InlineCode>timestamp</InlineCode></td><td className="py-2 pr-4">string</td><td className="py-2">ISO 8601 timestamp</td></tr>
                <tr className="border-b"><td className="py-2 pr-4"><InlineCode>tags</InlineCode></td><td className="py-2 pr-4">object</td><td className="py-2">Key-value pairs for filtering</td></tr>
                <tr><td className="py-2 pr-4"><InlineCode>metadata</InlineCode></td><td className="py-2 pr-4">JSON</td><td className="py-2">Arbitrary extra data</td></tr>
              </tbody>
            </table>
          </div>
        </DocSubSection>

        <Callout variant="tip" title="Batch ingestion">
          You can send multiple generations in a single request. The endpoint returns the count of
          accepted events in the response.
        </Callout>
      </DocSection>

      <DocSection title="Supported Generation Types">
        <DocParagraph>
          The <InlineCode>type</InlineCode> field categorizes each generation. Moneat supports the
          following types:
        </DocParagraph>
        <ul className="list-disc list-inside space-y-1 text-sm text-muted-foreground">
          <li><strong className="text-foreground">chat</strong> — Chat completion calls (e.g. OpenAI chat completions)</li>
          <li><strong className="text-foreground">completion</strong> — Text completion calls</li>
          <li><strong className="text-foreground">embedding</strong> — Embedding generation calls</li>
          <li><strong className="text-foreground">tool_call</strong> — Tool/function calls made by an LLM</li>
          <li><strong className="text-foreground">agent</strong> — Agent orchestration steps</li>
          <li><strong className="text-foreground">chain</strong> — Chain execution steps (e.g. LangChain chains)</li>
          <li><strong className="text-foreground">retriever</strong> — Retriever calls (e.g. RAG document retrieval)</li>
        </ul>
        <DocParagraph>
          The <InlineCode>model</InlineCode> and <InlineCode>provider</InlineCode> fields accept
          any string value — Moneat does not restrict which models or providers you can use.
        </DocParagraph>
      </DocSection>

      <DocSection title="Dashboard Features">
        <DocSubSection title="Overview Dashboard">
          <DocParagraph>
            The AI overview page (accessible from the <strong className="text-foreground">AI</strong> sidebar item)
            shows key metrics at a glance: total generations, total tokens, total cost, average latency,
            and error rate. Time-series charts show LLM call volume and breakdowns by model.
          </DocParagraph>
        </DocSubSection>

        <DocSubSection title="Generations Browser">
          <DocParagraph>
            Browse all individual LLM calls with filters for model, provider, type, status, and time range.
            Each generation shows its timestamp, model, token usage, cost, and latency. Click any
            generation to view its full input and output content.
          </DocParagraph>
        </DocSubSection>

        <DocSubSection title="Trace Detail">
          <DocParagraph>
            For multi-step agent executions, click a trace to view the full trace waterfall. This shows
            all generations in a trace as a hierarchical timeline, with parent/child relationships
            visualized. Click any span to see its full details, including input/output, token counts,
            and cost.
          </DocParagraph>
        </DocSubSection>
      </DocSection>

      <DocSection title="Cost Tracking">
        <DocParagraph>
          Track your LLM spend across models and providers. Set the <InlineCode>cost_usd</InlineCode> field
          on each generation to record its cost. When using Sentry SDKs with AI integrations, cost
          data is typically populated automatically based on model pricing.
        </DocParagraph>
        <DocParagraph>
          The cost dashboard shows total spend over time with breakdowns by model and provider,
          making it easy to identify which models are driving your costs and optimize accordingly.
        </DocParagraph>
      </DocSection>

      <DocSection title="Data Retention">
        <DocParagraph>
          LLM generation data is stored in ClickHouse with a 90-day retention period. Hourly
          aggregations (token counts, costs, call counts, error rates, and latency percentiles) are
          maintained for efficient dashboard queries. Check your plan details
          under <a href="/docs/billing" className="text-primary hover:underline">Billing &amp; Plans</a> for
          specific retention limits.
        </DocParagraph>
      </DocSection>

      <DocSection title="Next Steps">
        <DocParagraph>
          Explore related documentation to get the most out of Moneat:
        </DocParagraph>
        <ul className="list-disc list-inside space-y-2 text-sm text-muted-foreground">
          <li><a href="/docs/getting-started" className="text-primary hover:underline">Getting Started</a> — Set up your Moneat account and first project</li>
          <li><a href="/docs/sdk-setup" className="text-primary hover:underline">SDK Setup</a> — Platform-specific SDK configuration guides</li>
          <li><a href="/docs/error-monitoring" className="text-primary hover:underline">Error Monitoring</a> — Learn about error capture and issue grouping</li>
          <li><a href="/docs/billing" className="text-primary hover:underline">Billing &amp; Plans</a> — Understand usage quotas and billing</li>
        </ul>
      </DocSection>
    </DocPage>
  )
}
