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

import {Link} from '@tanstack/react-router'
import {Tabs, TabsContent, TabsList, TabsTrigger} from '@/components/ui/tabs'
import {BookOpen, Check, Cpu, Globe, Key, Server, Smartphone, TerminalSquare} from 'lucide-react'
import {cn} from '@/lib/utils'
import {applySdkVersionsToSnippet, type SdkVersionMap} from '@/lib/sdk-versions'
import {CopyBlock} from '@/components/ui/copy-block'
import {Button} from '@/components/ui/button'

interface LogSetupGuideProps {
  sdkVersions?: SdkVersionMap
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
# Create a log API key in Settings > Log API Keys
exporter = OTLPLogExporter(
    endpoint="https://api.moneat.io/v1/logs/otlp",
    headers={"Authorization": "Bearer YOUR_LOG_API_KEY"},
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
            "Authorization": "Bearer YOUR_LOG_API_KEY",
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
    .addHeader("Authorization", "Bearer YOUR_LOG_API_KEY")
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
  -H "Authorization: Bearer YOUR_LOG_API_KEY" \\
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

export function LogSetupGuide({sdkVersions}: LogSetupGuideProps) {
  return (
    <div className="mx-auto max-w-3xl py-8">
      <div className="mb-8 text-center">
        <div className="mx-auto mb-4 flex h-16 w-16 items-center justify-center rounded-2xl bg-gradient-to-br from-blue-500/20 to-violet-500/20 ring-1 ring-blue-500/30">
          <BookOpen className="h-8 w-8 text-blue-500" />
        </div>
        <h3 className="mb-2 text-xl font-semibold">Get Started with Log Ingestion</h3>
        <p className="text-sm text-muted-foreground leading-relaxed max-w-lg mx-auto">
          Ship your application logs to Moneat using OpenTelemetry (OTLP). Works with any language and integrates with your existing logging framework.
        </p>
        <Button asChild variant="default" className="mt-4">
          <Link to="/settings" search={{tab: 'api-keys'}}>
            <Key className="h-4 w-4 mr-2" />
            Create Log API Key
          </Link>
        </Button>
      </div>

      <div className="mb-10 rounded-xl border border-blue-500/20 bg-gradient-to-br from-blue-500/5 to-cyan-500/5 p-5">
        <div className="mb-5 flex items-start gap-3">
          <div className="mt-0.5 rounded-md bg-blue-500/10 p-2 ring-1 ring-blue-500/20">
            <BookOpen className="h-4 w-4 text-blue-500" />
          </div>
          <div>
            <h4 className="text-sm font-semibold text-foreground">OpenTelemetry Logs (Recommended)</h4>
            <p className="mt-1 text-sm text-muted-foreground">
              Best for application logs. Uses the OpenTelemetry standard and works with your existing logging framework -- Python logging, Winston, log4j, slog, and more.
            </p>
          </div>
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
                    'gap-2 rounded-lg border px-4 py-2.5 text-sm',
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
                      'bg-blue-500/10 text-blue-600 dark:text-blue-400'
                    )}>
                      {index + 1}
                    </div>
                    <h4 className="text-sm font-medium">{step.title}</h4>
                  </div>
                  <div className="ml-9">
                    <CopyBlock
                      code={applySdkVersionsToSnippet(step.code, sdkVersions)}
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
      </div>

    </div>
  )
}
