import {useState} from 'react'
import {AlertCircle, Check, Copy, Server, TerminalSquare} from 'lucide-react'
import {Button} from '@/components/ui/button'

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
          <Check className="h-3 w-3 text-emerald-500" />
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
      <div className="rounded-lg border border-blue-500/20 bg-gradient-to-br from-blue-500/5 to-cyan-500/5 p-4">
        <div className="flex items-start gap-3">
          <div className="mt-0.5 rounded-md bg-blue-500/10 p-2 ring-1 ring-blue-500/20">
            <TerminalSquare className="h-4 w-4 text-blue-500" />
          </div>
          <div className="min-w-0 flex-1">
            <h4 className="text-sm font-semibold">Enable Container Logs</h4>
            <p className="mt-1 text-xs text-muted-foreground">
              To see container logs here, set <code className="rounded bg-muted px-1 py-0.5 font-mono text-[11px]">MONEAT_LOGS=true</code> on your moneat-agent.
            </p>
            <div className="mt-3 rounded-md border bg-card p-2">
              <div className="flex items-center justify-between">
                <code className="text-[11px] text-muted-foreground">-e MONEAT_LOGS=true</code>
                <CopyButton text="-e MONEAT_LOGS=true" />
              </div>
            </div>
            <Button
              asChild
              variant="outline"
              size="sm"
              className="mt-2 h-7 text-[11px]"
            >
              <a
                href="https://github.com/moneat-io/agent"
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
        <div className="mx-auto mb-4 flex h-14 w-14 items-center justify-center rounded-xl bg-gradient-to-br from-blue-500/20 to-cyan-500/20 ring-1 ring-blue-500/30">
          <Server className="h-7 w-7 text-blue-500" />
        </div>
        <h3 className="mb-2 text-lg font-semibold">Enable Container Log Collection</h3>
        <p className="text-sm text-muted-foreground">
          Configure your moneat-agent to stream container logs to the dashboard
        </p>
      </div>

      <div className="rounded-xl border border-blue-500/20 bg-gradient-to-br from-blue-500/5 to-cyan-500/5 p-6">
        <div className="mb-4 flex items-start gap-3">
          <div className="mt-0.5 rounded-md bg-blue-500/10 p-2 ring-1 ring-blue-500/20">
            <TerminalSquare className="h-5 w-5 text-blue-500" />
          </div>
          <div>
            <h4 className="font-semibold">Quick Setup</h4>
            <p className="mt-1 text-sm text-muted-foreground">
              Add the <code className="rounded bg-muted px-1.5 py-0.5 font-mono text-xs">MONEAT_LOGS</code> environment variable to enable log collection
            </p>
          </div>
        </div>

        <div className="space-y-4">
          <div>
            <div className="mb-2 flex items-center gap-2 text-sm font-medium">
              <span className="flex h-5 w-5 items-center justify-center rounded-full bg-blue-500/10 text-xs font-bold text-blue-600">
                1
              </span>
              Update your Docker run command
            </div>
            <div className="ml-7 rounded-lg border bg-card">
              <div className="flex items-center justify-between border-b bg-muted/40 px-3 py-2">
                <span className="font-mono text-xs text-muted-foreground">bash</span>
                <CopyButton text={`docker run -d --name moneat-agent \\
  --restart unless-stopped \\
  -v /var/run/docker.sock:/var/run/docker.sock:ro \\
  -e DOCKER_HOST="unix:///var/run/docker.sock" \\
  -e MONEAT_KEY="<your-agent-key>" \\
  -e MONEAT_URL="https://api.moneat.io" \\
  -e MONEAT_LOGS=true \\
  adrianelder/moneat-agent:latest`} />
              </div>
              <div className="overflow-x-auto p-3">
                <pre className="font-mono text-xs leading-relaxed">
                  <code>
{`docker run -d --name moneat-agent \\
  --restart unless-stopped \\
  -v /var/run/docker.sock:/var/run/docker.sock:ro \\
  -e DOCKER_HOST="unix:///var/run/docker.sock" \\
  -e MONEAT_KEY="<your-agent-key>" \\
  -e MONEAT_URL="https://api.moneat.io" \\
  `}<span className="rounded bg-blue-500/10 px-1 font-bold text-blue-600">{`-e MONEAT_LOGS=true`}</span>{` \\
  adrianelder/moneat-agent:latest`}
                  </code>
                </pre>
              </div>
            </div>
          </div>

          <div>
            <div className="mb-2 flex items-center gap-2 text-sm font-medium">
              <span className="flex h-5 w-5 items-center justify-center rounded-full bg-blue-500/10 text-xs font-bold text-blue-600">
                2
              </span>
              Or update your docker-compose.yml
            </div>
            <div className="ml-7 rounded-lg border bg-card">
              <div className="flex items-center justify-between border-b bg-muted/40 px-3 py-2">
                <span className="font-mono text-xs text-muted-foreground">yaml</span>
                <CopyButton text={`services:
  moneat-agent:
    image: adrianelder/moneat-agent:latest
    restart: unless-stopped
    volumes:
      - /var/run/docker.sock:/var/run/docker.sock:ro
    environment:
      DOCKER_HOST: "unix:///var/run/docker.sock"
      MONEAT_KEY: "<your-agent-key>"
      MONEAT_URL: "https://api.moneat.io"
      MONEAT_LOGS: "true"`} />
              </div>
              <div className="overflow-x-auto p-3">
                <pre className="font-mono text-xs leading-relaxed">
                  <code>
{`services:
  moneat-agent:
    image: adrianelder/moneat-agent:latest
    restart: unless-stopped
    volumes:
      - /var/run/docker.sock:/var/run/docker.sock:ro
    environment:
      DOCKER_HOST: "unix:///var/run/docker.sock"
      MONEAT_KEY: "<your-agent-key>"
      MONEAT_URL: "https://api.moneat.io"
      `}<span className="rounded bg-blue-500/10 px-1 font-bold text-blue-600">{`MONEAT_LOGS: "true"`}</span>
                  </code>
                </pre>
              </div>
            </div>
          </div>
        </div>

        <div className="mt-5 rounded-lg border border-emerald-500/20 bg-emerald-500/5 p-3">
          <div className="flex items-start gap-2">
            <Check className="mt-0.5 h-4 w-4 shrink-0 text-emerald-500" />
            <p className="text-xs text-muted-foreground">
              After restarting the agent, container logs will appear here automatically. You can filter by specific containers using <code className="rounded bg-muted px-1 py-0.5 font-mono text-[11px]">MONEAT_LOG_MODE</code>.
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
              Filter which containers to collect logs from:
            </p>
          </div>
        </div>

        <div className="ml-8 space-y-3 text-xs">
          <div>
            <p className="font-medium text-muted-foreground">Include specific containers:</p>
            <code className="mt-1 block rounded bg-muted p-2 font-mono text-[11px]">
              -e MONEAT_LOG_MODE=include{'\n'}
              -e MONEAT_LOG_CONTAINERS="web,api,worker"
            </code>
          </div>

          <div>
            <p className="font-medium text-muted-foreground">Exclude specific containers:</p>
            <code className="mt-1 block rounded bg-muted p-2 font-mono text-[11px]">
              -e MONEAT_LOG_MODE=exclude{'\n'}
              -e MONEAT_LOG_EXCLUDE="redis,postgres,clickhouse"
            </code>
          </div>

          <div>
            <p className="font-medium text-muted-foreground">Label-driven opt-in:</p>
            <code className="mt-1 block rounded bg-muted p-2 font-mono text-[11px]">
              -e MONEAT_LOG_MODE=label
            </code>
            <p className="mt-1 text-muted-foreground">
              Then add <code className="rounded bg-muted px-1 py-0.5 font-mono">moneat.logs: "true"</code> label to containers
            </p>
          </div>
        </div>
      </div>
    </div>
  )
}
