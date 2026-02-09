import {Badge} from '@/components/ui/badge'
import {Card, CardContent, CardHeader, CardTitle} from '@/components/ui/card'
import type {LogEntry} from '@/lib/api'

interface LogDetailProps {
  log: LogEntry | null
}

function renderMapRows(map: Record<string, string>) {
  const entries = Object.entries(map)
  if (entries.length === 0) {
    return <p className="text-xs text-muted-foreground">None</p>
  }

  return (
    <div className="space-y-1">
      {entries.map(([key, value]) => (
        <div key={key} className="flex items-start gap-2 text-xs">
          <span className="min-w-[96px] font-mono text-muted-foreground">{key}</span>
          <span className="break-all font-mono">{value}</span>
        </div>
      ))}
    </div>
  )
}

export function LogDetail({ log }: LogDetailProps) {
  if (!log) {
    return (
      <Card className="h-full">
        <CardContent className="p-6 text-sm text-muted-foreground">
          Select a log line to inspect full details.
        </CardContent>
      </Card>
    )
  }

  return (
    <Card className="h-full">
      <CardHeader className="pb-3">
        <CardTitle className="text-base">Log Detail</CardTitle>
        <div className="flex flex-wrap gap-1.5">
          <Badge variant="outline" className="font-mono text-[11px] uppercase">{log.level}</Badge>
          {log.service && <Badge variant="secondary">{log.service}</Badge>}
          {log.environment && <Badge variant="secondary">{log.environment}</Badge>}
          {log.source && <Badge variant="outline">{log.source}</Badge>}
        </div>
      </CardHeader>
      <CardContent className="space-y-4 text-sm">
        <div>
          <p className="mb-1 text-xs uppercase tracking-wide text-muted-foreground">Message</p>
          <pre className="overflow-x-auto rounded-md bg-muted/60 p-3 font-mono text-xs whitespace-pre-wrap break-words">{log.message || '-'}</pre>
        </div>

        <div>
          <p className="mb-1 text-xs uppercase tracking-wide text-muted-foreground">Body</p>
          <pre className="max-h-[180px] overflow-auto rounded-md bg-muted/60 p-3 font-mono text-xs whitespace-pre-wrap break-words">{log.body || '-'}</pre>
        </div>

        <div className="grid gap-3 md:grid-cols-2">
          <div>
            <p className="mb-1 text-xs uppercase tracking-wide text-muted-foreground">Trace</p>
            <p className="font-mono text-xs break-all">{log.traceId || '-'}</p>
          </div>
          <div>
            <p className="mb-1 text-xs uppercase tracking-wide text-muted-foreground">Span</p>
            <p className="font-mono text-xs break-all">{log.spanId || '-'}</p>
          </div>
          <div>
            <p className="mb-1 text-xs uppercase tracking-wide text-muted-foreground">Container</p>
            <p className="font-mono text-xs break-all">{log.containerName || '-'}</p>
          </div>
          <div>
            <p className="mb-1 text-xs uppercase tracking-wide text-muted-foreground">Host</p>
            <p className="font-mono text-xs break-all">{log.host || '-'}</p>
          </div>
        </div>

        <div>
          <p className="mb-1 text-xs uppercase tracking-wide text-muted-foreground">Tags</p>
          {renderMapRows(log.tags)}
        </div>

        <div>
          <p className="mb-1 text-xs uppercase tracking-wide text-muted-foreground">Resource Attributes</p>
          {renderMapRows(log.resourceAttributes)}
        </div>
      </CardContent>
    </Card>
  )
}
