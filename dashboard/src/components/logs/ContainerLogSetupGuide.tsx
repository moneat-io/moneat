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

import {useState} from 'react'
import {AlertCircle, Check, Copy, Server, TerminalSquare} from 'lucide-react'
import {Button} from '@/components/ui/button'

const BACKEND_URL = import.meta.env.VITE_BACKEND_URL || 'https://api.moneat.io'
const INGEST_URL = BACKEND_URL.replace(/\/$/, '') + '/dd'

interface ContainerLogSetupGuideProps {
  compact?: boolean
}

function CopyButton({text}: {text: string}) {
  const [copied, setCopied] = useState(false)

  const handleCopy = async () => {
    try {
      await navigator.clipboard.writeText(text)
      setCopied(true)
      setTimeout(() => setCopied(false), 2000)
    } catch {
      // Ignore clipboard errors
    }
  }

  return (
    <Button
      variant="ghost"
      size="sm"
      onClick={handleCopy}
      className="h-6 gap-1 px-2 text-xs"
    >
      {copied ? (
        <>
          <Check className="h-3 w-3 text-success-fg" />
          Copied
        </>
      ) : (
        <>
          <Copy className="h-3 w-3" />
          Copy
        </>
      )}
    </Button>
  )
}

export function ContainerLogSetupGuide({compact = false}: ContainerLogSetupGuideProps) {
  if (compact) {
    return (
      <div className="rounded-lg border border-primary/20 bg-primary/5 p-4">
        <div className="flex items-start gap-3">
          <div className="mt-0.5 rounded-md bg-primary/10 p-2 ring-1 ring-primary/20">
            <TerminalSquare className="h-4 w-4 text-primary" />
          </div>
          <div className="min-w-0 flex-1">
            <h4 className="text-sm font-semibold">Enable Container Logs</h4>
            <p className="mt-1 text-xs text-muted-foreground">
              To see container logs here, set <code className="rounded bg-muted px-1 py-0.5 font-mono text-[11px]">DD_LOGS_ENABLED=true</code> on your Datadog agent.
            </p>
            <div className="mt-3 rounded-md border bg-card p-2">
              <div className="flex items-center justify-between">
                <code className="text-[11px] text-muted-foreground">-e DD_LOGS_ENABLED=true</code>
                <CopyButton text="-e DD_LOGS_ENABLED=true" />
              </div>
            </div>
            <Button
              asChild
              variant="outline"
              size="sm"
              className="mt-2 h-7 text-[11px]"
            >
              <a
                href="https://docs.datadoghq.com/containers/docker/log/"
                target="_blank"
                rel="noopener noreferrer"
              >
                View full documentation
              </a>
            </Button>
          </div>
        </div>
      </div>
    )
  }

  return (
    <div className="mx-auto max-w-2xl space-y-6">
      <div className="text-center">
        <div className="mx-auto mb-4 flex h-14 w-14 items-center justify-center rounded-xl bg-primary/10 ring-1 ring-primary/30">
          <Server className="h-7 w-7 text-primary" />
        </div>
        <h3 className="mb-2 text-lg font-semibold">Enable Container Log Collection</h3>
        <p className="text-sm text-muted-foreground">
          Configure your Datadog agent to stream container logs to the dashboard
        </p>
      </div>

      <div className="rounded-xl border border-primary/20 bg-primary/5 p-6">
        <div className="mb-4 flex items-start gap-3">
          <div className="mt-0.5 rounded-md bg-primary/10 p-2 ring-1 ring-primary/20">
            <TerminalSquare className="h-5 w-5 text-primary" />
          </div>
          <div>
            <h4 className="font-semibold">Quick Setup</h4>
            <p className="mt-1 text-sm text-muted-foreground">
              Add the <code className="rounded bg-muted px-1.5 py-0.5 font-mono text-xs">DD_LOGS_ENABLED</code> and <code className="rounded bg-muted px-1.5 py-0.5 font-mono text-xs">DD_LOGS_CONFIG_CONTAINER_COLLECT_ALL</code> environment variables to enable log collection
            </p>
          </div>
        </div>

        <div className="space-y-4">
          <div>
            <div className="mb-2 flex items-center gap-2 text-sm font-medium">
              <span className="flex h-5 w-5 items-center justify-center rounded-full bg-primary/10 text-xs font-bold text-primary">
                1
              </span>
              Update your Docker run command
            </div>
            <div className="ml-7 rounded-lg border bg-card">
              <div className="flex items-center justify-between border-b bg-muted/40 px-3 py-2">
                <span className="font-mono text-xs text-muted-foreground">bash</span>
                <CopyButton text={`docker run -d --name datadog-agent \\
  --restart unless-stopped \\
  -v /var/run/docker.sock:/var/run/docker.sock:ro \\
  -v /opt/datadog-agent/run:/opt/datadog-agent/run:rw \\
  -e DD_API_KEY="<your-api-key>" \\
  -e DD_DD_URL="${INGEST_URL}" \\
  -e DD_LOGS_ENABLED=true \\
  -e DD_LOGS_CONFIG_CONTAINER_COLLECT_ALL=true \\
  datadog/agent:latest`} />
              </div>
              <div className="overflow-x-auto p-3">
                <pre className="font-mono text-xs leading-relaxed">
                  <code>
{`docker run -d --name datadog-agent \\
  --restart unless-stopped \\
  -v /var/run/docker.sock:/var/run/docker.sock:ro \\
  -v /opt/datadog-agent/run:/opt/datadog-agent/run:rw \\
  -e DD_API_KEY="<your-api-key>" \\
  -e DD_DD_URL="${INGEST_URL}" \\
  `}<span className="rounded bg-primary/10 px-1 font-bold text-primary">{`-e DD_LOGS_ENABLED=true`}</span>{` \\
  `}<span className="rounded bg-primary/10 px-1 font-bold text-primary">{`-e DD_LOGS_CONFIG_CONTAINER_COLLECT_ALL=true`}</span>{` \\
  datadog/agent:latest`}
                  </code>
                </pre>
              </div>
            </div>
          </div>

          <div>
            <div className="mb-2 flex items-center gap-2 text-sm font-medium">
              <span className="flex h-5 w-5 items-center justify-center rounded-full bg-primary/10 text-xs font-bold text-primary">
                2
              </span>
              Or update your docker-compose.yml
            </div>
            <div className="ml-7 rounded-lg border bg-card">
              <div className="flex items-center justify-between border-b bg-muted/40 px-3 py-2">
                <span className="font-mono text-xs text-muted-foreground">yaml</span>
                <CopyButton text={`services:
  datadog-agent:
    image: datadog/agent:latest
    restart: unless-stopped
    volumes:
      - /var/run/docker.sock:/var/run/docker.sock:ro
      - /opt/datadog-agent/run:/opt/datadog-agent/run:rw
    environment:
      DD_API_KEY: "<your-api-key>"
      DD_DD_URL: "${INGEST_URL}"
      DD_LOGS_ENABLED: "true"
      DD_LOGS_CONFIG_CONTAINER_COLLECT_ALL: "true"`} />
              </div>
              <div className="overflow-x-auto p-3">
                <pre className="font-mono text-xs leading-relaxed">
                  <code>
{`services:
  datadog-agent:
    image: datadog/agent:latest
    restart: unless-stopped
    volumes:
      - /var/run/docker.sock:/var/run/docker.sock:ro
      - /opt/datadog-agent/run:/opt/datadog-agent/run:rw
    environment:
      DD_API_KEY: "<your-api-key>"
      DD_DD_URL: "${INGEST_URL}"
      `}<span className="rounded bg-primary/10 px-1 font-bold text-primary">{`DD_LOGS_ENABLED: "true"`}</span>{`
      `}<span className="rounded bg-primary/10 px-1 font-bold text-primary">{`DD_LOGS_CONFIG_CONTAINER_COLLECT_ALL: "true"`}</span>
                  </code>
                </pre>
              </div>
            </div>
          </div>
        </div>

        <div className="mt-5 rounded-lg border border-success-border bg-success-bg p-3">
          <div className="flex items-start gap-2">
            <Check className="mt-0.5 h-4 w-4 shrink-0 text-success-fg" />
            <p className="text-xs text-muted-foreground">
              After restarting the agent, container logs will appear here automatically. You can use Docker labels to control which containers are collected.
            </p>
          </div>
        </div>
      </div>

      <div className="rounded-lg border bg-card p-4">
        <div className="mb-3 flex items-start gap-3">
          <AlertCircle className="mt-0.5 h-5 w-5 text-muted-foreground" />
          <div>
            <h4 className="text-sm font-semibold">Advanced Configuration</h4>
            <p className="mt-1 text-xs text-muted-foreground">
              Filter which containers to collect logs from using Docker labels:
            </p>
          </div>
        </div>

        <div className="ml-8 space-y-3 text-xs">
          <div>
            <p className="font-medium text-muted-foreground">Exclude specific containers:</p>
            <code className="mt-1 block rounded bg-muted p-2 font-mono text-[11px]">
              # Add label to containers you want to exclude{'\n'}
              com.datadoghq.ad.logs: '[{"{"}\"source\": \"container\", \"service\": \"excluded\"{"}"}]'
            </code>
          </div>

          <div>
            <p className="font-medium text-muted-foreground">Label-driven opt-in (disable collect all first):</p>
            <code className="mt-1 block rounded bg-muted p-2 font-mono text-[11px]">
              DD_LOGS_CONFIG_CONTAINER_COLLECT_ALL=false
            </code>
            <p className="mt-1 text-muted-foreground">
              Then add <code className="rounded bg-muted px-1 py-0.5 font-mono">com.datadoghq.ad.logs</code> label to containers you want to collect
            </p>
          </div>
        </div>
      </div>
    </div>
  )
}
