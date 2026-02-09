import {useEffect, useState} from 'react'
import {Tabs, TabsContent, TabsList, TabsTrigger} from '@/components/ui/tabs'
import {BookOpen, Check, Copy, Cpu, Globe, Server, Smartphone, TerminalSquare} from 'lucide-react'
import {cn} from '@/lib/utils'
import {applySdkVersionsToSnippet, type SdkVersionMap} from '@/lib/sdk-versions'
import {Prism as SyntaxHighlighter} from 'react-syntax-highlighter'
import {oneDark, oneLight} from 'react-syntax-highlighter/dist/esm/styles/prism'

interface LogSetupGuideProps {
  dsn?: string
  sdkVersions?: SdkVersionMap
}

const LANGUAGE_ALIASES: Record<string, string> = {
  xml: 'markup',
  text: 'plaintext',
}

const sentrySdkSteps = [
  {
    title: 'Install the Sentry SDK',
    code: 'npm install @sentry/node',
    language: 'bash',
  },
  {
    title: 'Enable logs and send messages',
    code: `const Sentry = require('@sentry/node');

Sentry.init({
  dsn: 'YOUR_DSN_HERE',
  enableLogs: true,
});

Sentry.logger.info('A simple log message');
Sentry.logger.error('A %s log message', 'formatted');

// Optional: auto-capture console output as logs
// Sentry.init({
//   dsn: 'YOUR_DSN_HERE',
//   enableLogs: true,
//   integrations: [Sentry.consoleLoggingIntegration({ levels: ['log', 'warn', 'error'] })],
// });`,
    language: 'javascript',
  },
]

function CopyBlock({code, language}: {code: string; language: string}) {
  const [copied, setCopied] = useState(false)
  const [isDark, setIsDark] = useState(true)

  useEffect(() => {
    const root = document.documentElement
    setIsDark(root.classList.contains('dark'))
    const observer = new MutationObserver(() => setIsDark(root.classList.contains('dark')))
    observer.observe(root, {attributes: true, attributeFilter: ['class']})
    return () => observer.disconnect()
  }, [])

  const handleCopy = async () => {
    try {
      await navigator.clipboard.writeText(code)
      setCopied(true)
      setTimeout(() => setCopied(false), 2000)
    } catch {
      // Ignore clipboard errors
    }
  }

  const prismLanguage = LANGUAGE_ALIASES[language] ?? language ?? 'plaintext'
  const style = isDark ? oneDark : oneLight

  return (
    <div className="group relative overflow-hidden rounded-lg border bg-card">
      <div className="flex items-center justify-between border-b bg-muted/40 px-4 py-2">
        <span className="font-mono text-[11px] text-muted-foreground">{language}</span>
        <button
          type="button"
          onClick={handleCopy}
          className="flex items-center gap-1.5 rounded-md px-2 py-1 text-[11px] text-muted-foreground transition-colors hover:bg-muted hover:text-foreground"
        >
          {copied ? (
            <>
              <Check className="h-3 w-3 text-emerald-500" />
              Copied
            </>
          ) : (
            <>
              <Copy className="h-3 w-3" />
              Copy
            </>
          )}
        </button>
      </div>
      <SyntaxHighlighter
        language={prismLanguage}
        style={style}
        customStyle={{
          margin: 0,
          padding: '1rem',
          fontSize: '0.75rem',
          lineHeight: 1.6,
          background: undefined,
        }}
        codeTagProps={{
          style: {
            fontFamily: 'ui-monospace, SFMono-Regular, "SF Mono", Menlo, Consolas, monospace',
          },
        }}
        showLineNumbers={code.split('\n').length > 3}
        lineNumberStyle={{minWidth: '2em', paddingRight: '1em', opacity: 0.5}}
        wrapLongLines
      >
        {code}
      </SyntaxHighlighter>
    </div>
  )
}

const platforms = [
  {
    id: 'python',
    name: 'Python',
    icon: Globe,
    color: 'text-yellow-500',
    bgColor: 'bg-yellow-500/10',
    steps: [
      {
        title: 'Install the OpenTelemetry SDK',
        code: 'pip install opentelemetry-api opentelemetry-sdk opentelemetry-exporter-otlp',
        language: 'bash',
      },
      {
        title: 'Configure the exporter',
        code: `import logging
from opentelemetry import trace
from opentelemetry.sdk._logs import LoggerProvider, LoggingHandler
from opentelemetry.sdk._logs.export import BatchLogRecordProcessor
from opentelemetry.exporter.otlp.proto.http._log_exporter import OTLPLogExporter

# Configure OTLP exporter pointing to Moneat
exporter = OTLPLogExporter(
    endpoint="https://api.moneat.io/v1/logs/otlp",
    headers={"x-moneat-dsn": "YOUR_DSN_HERE"},
)

logger_provider = LoggerProvider()
logger_provider.add_log_record_processor(BatchLogRecordProcessor(exporter))

# Attach to Python logging
handler = LoggingHandler(logger_provider=logger_provider)
logging.getLogger().addHandler(handler)
logging.getLogger().setLevel(logging.INFO)

# Now just use standard Python logging
logger = logging.getLogger(__name__)
logger.info("Hello from Moneat!")`,
        language: 'python',
      },
    ],
  },
  {
    id: 'nodejs',
    name: 'Node.js',
    icon: Server,
    color: 'text-green-500',
    bgColor: 'bg-green-500/10',
    steps: [
      {
        title: 'Install dependencies',
        code: 'npm install @opentelemetry/api @opentelemetry/sdk-logs @opentelemetry/exporter-logs-otlp-http',
        language: 'bash',
      },
      {
        title: 'Set up the log exporter',
        code: `const { LoggerProvider, SimpleLogRecordProcessor } = require('@opentelemetry/sdk-logs');
const { OTLPLogExporter } = require('@opentelemetry/exporter-logs-otlp-http');
const { SeverityNumber } = require('@opentelemetry/api-logs');

const exporter = new OTLPLogExporter({
  url: 'https://api.moneat.io/v1/logs/otlp',
  headers: { 'x-moneat-dsn': 'YOUR_DSN_HERE' },
});

const loggerProvider = new LoggerProvider();
loggerProvider.addLogRecordProcessor(new SimpleLogRecordProcessor(exporter));

const logger = loggerProvider.getLogger('my-app');

// Emit a log
logger.emit({
  severityNumber: SeverityNumber.INFO,
  severityText: 'INFO',
  body: 'Hello from Moneat!',
  attributes: { 'service.name': 'my-node-app' },
});`,
        language: 'javascript',
      },
    ],
  },
  {
    id: 'go',
    name: 'Go',
    icon: Cpu,
    color: 'text-cyan-500',
    bgColor: 'bg-cyan-500/10',
    steps: [
      {
        title: 'Install the OTLP exporter',
        code: `go get go.opentelemetry.io/otel/sdk/log
go get go.opentelemetry.io/otel/exporters/otlp/otlplog/otlploghttp`,
        language: 'bash',
      },
      {
        title: 'Configure and send logs',
        code: `package main

import (
    "context"
    "go.opentelemetry.io/otel/exporters/otlp/otlplog/otlploghttp"
    sdklog "go.opentelemetry.io/otel/sdk/log"
)

func main() {
    ctx := context.Background()

    exporter, _ := otlploghttp.New(ctx,
        otlploghttp.WithEndpoint("api.moneat.io"),
        otlploghttp.WithURLPath("/v1/logs/otlp"),
        otlploghttp.WithHeaders(map[string]string{
            "x-moneat-dsn": "YOUR_DSN_HERE",
        }),
    )

    provider := sdklog.NewLoggerProvider(
        sdklog.WithProcessor(
            sdklog.NewBatchProcessor(exporter),
        ),
    )
    defer provider.Shutdown(ctx)

    logger := provider.Logger("my-go-app")
    // Use the logger to emit log records
}`,
        language: 'go',
      },
    ],
  },
  {
    id: 'java',
    name: 'Java / Kotlin',
    icon: Smartphone,
    color: 'text-orange-500',
    bgColor: 'bg-orange-500/10',
    steps: [
      {
        title: 'Add the OTLP dependency',
        code: `// Gradle (build.gradle.kts)
dependencies {
    implementation("io.opentelemetry:opentelemetry-sdk-logs:1.34.0")
    implementation("io.opentelemetry:opentelemetry-exporter-otlp:1.34.0")
}`,
        language: 'kotlin',
      },
      {
        title: 'Configure the exporter',
        code: `import io.opentelemetry.exporter.otlp.logs.OtlpGrpcLogRecordExporter
import io.opentelemetry.sdk.logs.SdkLoggerProvider
import io.opentelemetry.sdk.logs.export.BatchLogRecordProcessor

val exporter = OtlpGrpcLogRecordExporter.builder()
    .setEndpoint("https://api.moneat.io/v1/logs/otlp")
    .addHeader("x-moneat-dsn", "YOUR_DSN_HERE")
    .build()

val loggerProvider = SdkLoggerProvider.builder()
    .addLogRecordProcessor(BatchLogRecordProcessor.builder(exporter).build())
    .build()`,
        language: 'kotlin',
      },
    ],
  },
  {
    id: 'curl',
    name: 'cURL / HTTP',
    icon: TerminalSquare,
    color: 'text-purple-500',
    bgColor: 'bg-purple-500/10',
    steps: [
      {
        title: 'Send logs directly via the OTLP HTTP endpoint',
        code: `curl -X POST https://api.moneat.io/v1/logs/otlp \\
  -H "Content-Type: application/json" \\
  -H "x-moneat-dsn: YOUR_DSN_HERE" \\
  -d '{
    "resourceLogs": [{
      "resource": {
        "attributes": [
          {"key": "service.name", "value": {"stringValue": "my-service"}},
          {"key": "deployment.environment", "value": {"stringValue": "production"}}
        ]
      },
      "scopeLogs": [{
        "logRecords": [{
          "timeUnixNano": "'$(date +%s)'000000000",
          "severityText": "INFO",
          "body": {"stringValue": "Hello from Moneat!"},
          "attributes": [
            {"key": "user.id", "value": {"stringValue": "123"}}
          ]
        }]
      }]
    }]
  }'`,
        language: 'bash',
      },
    ],
  },
]

const migrationGuides = [
  {
    id: 'datadog',
    name: 'Datadog',
    icon: Server,
    color: 'text-sky-500',
    bgColor: 'bg-sky-500/10',
    rollout: [
      'Start with dual shipping for 7-14 days: keep Datadog as the source of truth while sending the same application logs to Moneat via OTLP.',
      'Map Datadog fields to OpenTelemetry attributes before export: dd.service -> service.name, dd.env -> deployment.environment, dd.version -> service.version.',
      'Preserve trace correlation by forwarding dd.trace_id and dd.span_id as trace_id/span_id attributes so issue and trace pivots continue working.',
      'Validate parity using a fixed query pack (error rate, top services, noisy endpoints) before cutting production alerts and dashboards to Moneat.',
    ],
    cutover: 'Cut over team-by-team. Keep Datadog dual shipping enabled for one release cycle as rollback protection.',
  },
  {
    id: 'graylog',
    name: 'Graylog',
    icon: Globe,
    color: 'text-zinc-500',
    bgColor: 'bg-zinc-500/10',
    rollout: [
      'Keep your current Graylog inputs (GELF/syslog/Beats) and introduce a parallel OTLP path from the same emitters or sidecar collector.',
      'Normalize custom GELF fields to OTLP attributes early (_service, _env, _team, _trace_id) so search behavior stays consistent.',
      'Mirror Graylog streams in Moneat using service/environment filters first, then recreate only high-value pipelines and alerts.',
      'If you rely on extractors, implement equivalent parsing at the shipper/collector layer before data reaches Moneat.',
    ],
    cutover: 'Move retained streams in batches. Keep Graylog ingestion for long-retention compliance until your archive/export policy is verified.',
  },
  {
    id: 'loki',
    name: 'Loki',
    icon: Cpu,
    color: 'text-emerald-500',
    bgColor: 'bg-emerald-500/10',
    rollout: [
      'Continue collecting with Promtail or Grafana Alloy, then dual ship to Moneat through an OTLP-capable collector/exporter.',
      'Translate Loki labels to OTLP attributes: job -> service.name, namespace -> k8s.namespace.name, pod -> k8s.pod.name.',
      'Reduce high-cardinality labels before export (request_id/session_id/user_id) and keep them as searchable attributes only when needed.',
      'Port critical LogQL dashboards by starting with label-equivalent filters, then refine for full-text and structured field search in Moneat.',
    ],
    cutover: 'Cut ingestion over per cluster or environment. Keep Loki read-only until on-call teams validate parity on live incidents.',
  },
]

export function LogSetupGuide({dsn, sdkVersions}: LogSetupGuideProps) {
  return (
    <div className="mx-auto max-w-3xl py-8">
      <div className="mb-8 text-center">
        <div className="mx-auto mb-4 flex h-16 w-16 items-center justify-center rounded-2xl bg-gradient-to-br from-blue-500/20 to-violet-500/20 ring-1 ring-blue-500/30">
          <BookOpen className="h-8 w-8 text-blue-500" />
        </div>
        <h3 className="mb-2 text-xl font-semibold">Get Started with Log Ingestion</h3>
        <p className="text-sm text-muted-foreground leading-relaxed max-w-lg mx-auto">
          Choose your ingestion path: use the Sentry SDK for application logs with built-in
          Sentry context, or use OTLP for collector and platform pipelines.
        </p>
      </div>

      <div className="mb-10 rounded-xl border border-blue-500/20 bg-gradient-to-br from-blue-500/5 to-cyan-500/5 p-5">
        <div className="mb-5 flex items-start gap-3">
          <div className="mt-0.5 rounded-md bg-blue-500/10 p-2 ring-1 ring-blue-500/20">
            <BookOpen className="h-4 w-4 text-blue-500" />
          </div>
          <div>
            <h4 className="text-sm font-semibold text-foreground">Sentry SDK Logs (Recommended)</h4>
            <p className="mt-1 text-sm text-muted-foreground">
              Best for application logs. This keeps logs in the same SDK pipeline as your errors and
              traces, so correlation works out of the box.
            </p>
          </div>
        </div>

        <div className="space-y-5">
          {sentrySdkSteps.map((step, index) => (
            <div key={step.title} className="space-y-3">
              <div className="flex items-center gap-3">
                <div className="flex h-6 w-6 shrink-0 items-center justify-center rounded-full bg-blue-500/10 text-[11px] font-bold text-blue-600 dark:text-blue-400">
                  {index + 1}
                </div>
                <h5 className="text-sm font-medium">{step.title}</h5>
              </div>
              <div className="ml-9">
                <CopyBlock
                  code={applySdkVersionsToSnippet(
                    dsn ? step.code.replace(/YOUR_DSN_HERE/g, dsn) : step.code,
                    sdkVersions
                  )}
                  language={step.language}
                />
              </div>
            </div>
          ))}
        </div>
      </div>

      <div className="mb-4 text-center">
        <h4 className="text-base font-semibold">OTLP / Collector Ingestion</h4>
        <p className="mx-auto mt-1 max-w-2xl text-sm text-muted-foreground">
          Use this path when you already have OpenTelemetry or collector-based pipelines and want to
          ship logs to Moneat.
        </p>
      </div>

      <Tabs defaultValue="python" className="w-full">
        <TabsList className="mx-auto mb-6 flex w-full flex-wrap justify-center gap-1 bg-transparent h-auto p-0">
          {platforms.map((platform) => {
            const Icon = platform.icon
            return (
              <TabsTrigger
                key={platform.id}
                value={platform.id}
                className={cn(
                  'gap-2 rounded-lg border px-4 py-2.5 text-sm data-[state=active]:shadow-sm',
                  'data-[state=active]:border-border data-[state=active]:bg-card',
                  'data-[state=inactive]:border-transparent data-[state=inactive]:bg-transparent'
                )}
              >
                <Icon className={cn('h-4 w-4', platform.color)} />
                {platform.name}
              </TabsTrigger>
            )
          })}
        </TabsList>

        {platforms.map((platform) => (
          <TabsContent key={platform.id} value={platform.id} className="space-y-6">
            {platform.steps.map((step, index) => (
              <div key={index} className="space-y-3">
                <div className="flex items-center gap-3">
                  <div className={cn(
                    'flex h-6 w-6 shrink-0 items-center justify-center rounded-full text-[11px] font-bold',
                    platform.bgColor, platform.color
                  )}>
                    {index + 1}
                  </div>
                  <h4 className="text-sm font-medium">{step.title}</h4>
                </div>
                <div className="ml-9">
                  <CopyBlock
                    code={applySdkVersionsToSnippet(
                      dsn ? step.code.replace(/YOUR_DSN_HERE/g, dsn) : step.code,
                      sdkVersions
                    )}
                    language={step.language}
                  />
                </div>
              </div>
            ))}

            <div className="ml-9 rounded-lg border border-emerald-500/20 bg-emerald-500/5 p-4">
              <div className="flex items-start gap-3">
                <Check className="mt-0.5 h-4 w-4 shrink-0 text-emerald-500" />
                <div className="text-sm text-muted-foreground">
                  <p className="font-medium text-foreground">That&apos;s it!</p>
                  <p className="mt-1">
                    Once your application sends its first log, it will appear here automatically.
                    Logs are indexed and searchable within seconds.
                  </p>
                </div>
              </div>
            </div>
          </TabsContent>
        ))}
      </Tabs>

      <div className="mt-12 border-t pt-8">
        <div className="mb-6 text-center">
          <h4 className="text-base font-semibold">Migrating from Datadog, Graylog, or Loki</h4>
          <p className="mx-auto mt-2 max-w-2xl text-sm text-muted-foreground">
            Use a phased rollout: dual ship first, verify parity with a fixed query set, then cut over by team or environment.
          </p>
        </div>

        <Tabs defaultValue="datadog" className="w-full">
          <TabsList className="mx-auto mb-4 flex w-full flex-wrap justify-center gap-1 bg-transparent h-auto p-0">
            {migrationGuides.map((guide) => {
              const Icon = guide.icon
              return (
                <TabsTrigger
                  key={guide.id}
                  value={guide.id}
                  className={cn(
                    'gap-2 rounded-lg border px-4 py-2.5 text-sm data-[state=active]:shadow-sm',
                    'data-[state=active]:border-border data-[state=active]:bg-card',
                    'data-[state=inactive]:border-transparent data-[state=inactive]:bg-transparent'
                  )}
                >
                  <Icon className={cn('h-4 w-4', guide.color)} />
                  {guide.name}
                </TabsTrigger>
              )
            })}
          </TabsList>

          {migrationGuides.map((guide) => {
            const GuideIcon = guide.icon
            return (
              <TabsContent key={guide.id} value={guide.id} className="space-y-4">
                <div className="rounded-lg border bg-card p-4">
                  <div className="mb-3 flex items-center gap-3">
                    <div className={cn('flex h-7 w-7 items-center justify-center rounded-md', guide.bgColor)}>
                      <GuideIcon className={cn('h-4 w-4', guide.color)} />
                    </div>
                    <p className="text-sm font-medium">{guide.name} migration playbook</p>
                  </div>

                  <ol className="space-y-2 text-sm text-muted-foreground">
                    {guide.rollout.map((step, index) => (
                      <li key={step} className="flex items-start gap-2">
                        <span className="mt-0.5 inline-flex h-5 w-5 shrink-0 items-center justify-center rounded-full bg-muted text-[11px] font-semibold text-foreground">
                          {index + 1}
                        </span>
                        <span>{step}</span>
                      </li>
                    ))}
                  </ol>

                  <p className="mt-4 rounded-md border border-emerald-500/20 bg-emerald-500/5 px-3 py-2 text-xs text-muted-foreground">
                    <span className="font-medium text-foreground">Cutover strategy:</span> {guide.cutover}
                  </p>
                </div>
              </TabsContent>
            )
          })}
        </Tabs>
      </div>
    </div>
  )
}
