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
import {useMutation, useQuery, useQueryClient} from '@tanstack/react-query'
import {api, type OtlpApiKey} from '@/lib/api'
import {Button} from '@/components/ui/button'
import {Input} from '@/components/ui/input'
import {Label} from '@/components/ui/label'
import {Card, CardContent, CardDescription, CardHeader, CardTitle} from '@/components/ui/card'
import {Table, TableBody, TableCell, TableHead, TableHeader, TableRow} from '@/components/ui/table'
import {
  Dialog,
  DialogContent,
  DialogDescription,
  DialogFooter,
  DialogHeader,
  DialogTitle,
} from '@/components/ui/dialog'
import {useToast} from '@/hooks/useToast'
import {Check, Copy, Loader2, Plus, ScrollText, Trash2} from 'lucide-react'
import {useTimezone} from '@/hooks/useTimezone'
import {formatDate as formatDateUtil} from '@/lib/date-format'

const BACKEND_URL = import.meta.env.VITE_BACKEND_URL || 'https://api.moneat.io'

function formatDate(iso: string | null | undefined, timezone: string): string {
  if (!iso) return '—'
  try {
    return formatDateUtil(new Date(iso), timezone)
  } catch {
    return '—'
  }
}

export function OtlpApiKeysTab() {
  const queryClient = useQueryClient()
  const {toast} = useToast()
  const {timezone} = useTimezone()
  const [createOpen, setCreateOpen] = useState(false)
  const [newKeyName, setNewKeyName] = useState('')
  const [createdKey, setCreatedKey] = useState<{key: string; name: string} | null>(null)
  const [revokeKey, setRevokeKey] = useState<OtlpApiKey | null>(null)

  const {data: keysData, isLoading} = useQuery({
    queryKey: ['otlpApiKeys'],
    queryFn: () => api.getOtlpApiKeys(),
    enabled: api.isAuthenticated(),
  })

  const keys = keysData?.keys ?? []

  const createMutation = useMutation({
    mutationFn: (name: string) => api.createOtlpApiKey(name),
    onSuccess: (data) => {
      queryClient.invalidateQueries({queryKey: ['otlpApiKeys']})
      setCreatedKey({key: data.key, name: data.name})
      setNewKeyName('')
      setCreateOpen(false)
      toast({title: 'OTLP API key created', description: 'Copy the key now—it won\'t be shown again.'})
    },
    onError: (err: Error) => {
      toast({
        title: 'Failed to create key',
        description: err.message,
        variant: 'destructive',
      })
    },
  })

  const deleteMutation = useMutation({
    mutationFn: (id: number) => api.deleteOtlpApiKey(id),
    onSuccess: () => {
      queryClient.invalidateQueries({queryKey: ['otlpApiKeys']})
      setRevokeKey(null)
      toast({title: 'Key revoked', description: 'The OTLP API key has been revoked.'})
    },
    onError: (err: Error) => {
      toast({
        title: 'Failed to revoke key',
        description: err.message,
        variant: 'destructive',
      })
    },
  })

  const handleCopyKey = async (value: string) => {
    try {
      await navigator.clipboard.writeText(value)
      toast({title: 'Copied', description: 'Key copied to clipboard.'})
    } catch {
      toast({
        title: 'Copy failed',
        description: 'Could not copy to clipboard. Try selecting and copying manually.',
        variant: 'destructive',
      })
    }
  }

  const handleCloseCreateDialog = () => {
    setCreateOpen(false)
    setNewKeyName('')
    setCreatedKey(null)
    createMutation.reset()
  }

  return (
    <>
      <Card id="otlp-api-keys">
        <CardHeader className="flex flex-row items-start justify-between space-y-0 gap-4">
          <div>
            <CardTitle className="flex items-center gap-2">
              <ScrollText className="h-5 w-5" />
              OTLP API Keys
            </CardTitle>
            <CardDescription>
              Create org-level API keys for OpenTelemetry ingestion (logs, traces, and metrics).
              Use these keys with OTLP exporters or the ingest API. Keys are shown in full only once
              when created.
            </CardDescription>
          </div>
          <Button onClick={() => setCreateOpen(true)} disabled={!!createdKey}>
            <Plus className="h-4 w-4 mr-2" />
            New Key
          </Button>
        </CardHeader>
        <CardContent>
          {isLoading ? (
            <p className="text-muted-foreground text-sm py-8">Loading keys...</p>
          ) : keys.length === 0 ? (
            <div className="border rounded-lg p-8 text-center text-muted-foreground">
              <ScrollText className="h-10 w-10 mx-auto mb-2 opacity-50" />
              <p className="font-medium">No OTLP API keys yet</p>
              <p className="text-sm mt-1">
                Create a key to send logs, traces, and metrics via OpenTelemetry.
              </p>
              <Button variant="outline" className="mt-4" onClick={() => setCreateOpen(true)}>
                <Plus className="h-4 w-4 mr-2" />
                Create key
              </Button>
            </div>
          ) : (
            <Table>
              <TableHeader>
                <TableRow>
                  <TableHead>Name</TableHead>
                  <TableHead>Prefix</TableHead>
                  <TableHead>Last Used</TableHead>
                  <TableHead>Created</TableHead>
                  <TableHead className="w-[80px]"></TableHead>
                </TableRow>
              </TableHeader>
              <TableBody>
                {keys.map((key) => (
                  <TableRow key={key.id}>
                    <TableCell className="font-medium">{key.name}</TableCell>
                    <TableCell className="font-mono text-sm text-muted-foreground">
                      {key.keyPrefix}…
                    </TableCell>
                    <TableCell className="text-muted-foreground text-sm">
                      {formatDate(key.lastUsedAt, timezone)}
                    </TableCell>
                    <TableCell className="text-muted-foreground text-sm">
                      {formatDate(key.createdAt, timezone)}
                    </TableCell>
                    <TableCell>
                      <Button
                        variant="ghost"
                        size="sm"
                        className="text-destructive hover:text-destructive"
                        onClick={() => setRevokeKey(key)}
                        aria-label={`Revoke API key ${key.name}`}
                        title="Revoke API key"
                      >
                        <Trash2 className="h-4 w-4" />
                      </Button>
                    </TableCell>
                  </TableRow>
                ))}
              </TableBody>
            </Table>
          )}
        </CardContent>
      </Card>

      {/* Setup instructions */}
      <Card>
        <CardHeader>
          <CardTitle className="text-base">Setup Instructions</CardTitle>
          <CardDescription>
            Configure your OpenTelemetry SDK or Collector to send telemetry data to Moneat.
          </CardDescription>
        </CardHeader>
        <CardContent className="space-y-4">
          <div>
            <p className="text-sm font-medium mb-1">OTLP endpoints</p>
            <code className="block text-xs bg-muted px-3 py-2 rounded-md break-all">
              Logs: {BACKEND_URL.replace(/\/$/, '')}/v1/logs/otlp
            </code>
            <code className="block text-xs bg-muted px-3 py-2 rounded-md break-all mt-1">
              Traces: {BACKEND_URL.replace(/\/$/, '')}/v1/traces/otlp
            </code>
            <code className="block text-xs bg-muted px-3 py-2 rounded-md break-all mt-1">
              Metrics: {BACKEND_URL.replace(/\/$/, '')}/v1/metrics/otlp
            </code>
          </div>
          <div>
            <p className="text-sm font-medium mb-1">Authentication</p>
            <p className="text-sm text-muted-foreground">
              Set the <code className="bg-muted px-1 rounded">Authorization</code> header to{' '}
              <code className="bg-muted px-1 rounded">Bearer YOUR_OTLP_API_KEY</code>
            </p>
          </div>
          <p className="text-sm text-muted-foreground">
            For OpenTelemetry SDKs, configure the OTLP exporter with the endpoint URL and the
            Authorization header containing your OTLP API key.
          </p>
        </CardContent>
      </Card>

      {/* Create key dialog */}
      <Dialog open={createOpen} onOpenChange={(o) => !o && handleCloseCreateDialog()}>
        <DialogContent>
          <DialogHeader>
            <DialogTitle>Create OTLP API Key</DialogTitle>
            <DialogDescription>
              Give this key a name to identify it (e.g. &quot;Production OTLP&quot;). The full key
              will be shown once and cannot be retrieved later.
            </DialogDescription>
          </DialogHeader>
          <div className="space-y-4 py-4">
            <div>
              <Label htmlFor="key-name">Name</Label>
              <Input
                id="key-name"
                value={newKeyName}
                onChange={(e) => setNewKeyName(e.target.value)}
                placeholder="e.g. Production OTLP"
                className="mt-2"
              />
            </div>
          </div>
          <DialogFooter>
            <Button variant="outline" onClick={handleCloseCreateDialog}>
              Cancel
            </Button>
            <Button
              onClick={() => createMutation.mutate(newKeyName.trim())}
              disabled={!newKeyName.trim() || createMutation.isPending}
            >
              {createMutation.isPending ? (
                <Loader2 className="h-4 w-4 animate-spin mr-2" />
              ) : null}
              Create
            </Button>
          </DialogFooter>
        </DialogContent>
      </Dialog>

      {/* Show key once dialog */}
      <Dialog open={!!createdKey} onOpenChange={(o) => !o && handleCloseCreateDialog()}>
        <DialogContent>
          <DialogHeader>
            <DialogTitle>OTLP API Key Created</DialogTitle>
            <DialogDescription>
              Copy your key now. It won&apos;t be shown again. Store it securely.
            </DialogDescription>
          </DialogHeader>
          {createdKey && (
            <div className="space-y-4 py-4">
              <div className="flex items-center gap-2">
                <code className="flex-1 bg-muted px-3 py-2 rounded-md text-sm break-all font-mono">
                  {createdKey.key}
                </code>
                <Button
                  variant="outline"
                  size="icon"
                  onClick={() => handleCopyKey(createdKey.key)}
                  className="shrink-0"
                  aria-label="Copy API key"
                  title="Copy API key"
                >
                  <Copy className="h-4 w-4" />
                </Button>
              </div>
            </div>
          )}
          <DialogFooter>
            <Button onClick={handleCloseCreateDialog}>
              <Check className="h-4 w-4 mr-2" />
              I&apos;ve copied the key
            </Button>
          </DialogFooter>
        </DialogContent>
      </Dialog>

      {/* Revoke confirmation */}
      <Dialog open={!!revokeKey} onOpenChange={(o) => !o && setRevokeKey(null)}>
        <DialogContent>
          <DialogHeader>
            <DialogTitle>Revoke OTLP API Key</DialogTitle>
            <DialogDescription>
              Are you sure you want to revoke &quot;{revokeKey?.name}&quot;? Any clients using this
              key will no longer be able to send data.
            </DialogDescription>
          </DialogHeader>
          <DialogFooter>
            <Button variant="outline" onClick={() => setRevokeKey(null)}>
              Cancel
            </Button>
            <Button
              variant="destructive"
              onClick={() => revokeKey && deleteMutation.mutate(revokeKey.id)}
              disabled={deleteMutation.isPending}
            >
              {deleteMutation.isPending ? (
                <Loader2 className="h-4 w-4 animate-spin mr-2" />
              ) : null}
              Revoke
            </Button>
          </DialogFooter>
        </DialogContent>
      </Dialog>
    </>
  )
}
