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
import {createFileRoute} from '@tanstack/react-router'
import {useMutation, useQuery, useQueryClient} from '@tanstack/react-query'
import {KeyRound, Loader2, Lock, Plus, RotateCw, Trash2} from 'lucide-react'
import {api} from '@/lib/api'
import type {WorkflowConnection} from '@/lib/api'
import {Badge} from '@/components/ui/badge'
import {Button} from '@/components/ui/button'
import {PageHeader} from '@/components/ui/page-header'
import {EmptyState} from '@/components/ui/empty-state'
import {
  Dialog,
  DialogContent,
  DialogDescription,
  DialogFooter,
  DialogHeader,
  DialogTitle,
} from '@/components/ui/dialog'
import {Input} from '@/components/ui/input'
import {Label} from '@/components/ui/label'
import {hasEnterpriseModule, useEnterpriseFeatures} from '@/hooks/useEnterpriseFeatures'
import {useToast} from '@/hooks/useToast'

export const Route = createFileRoute('/workflows/connections')({
  component: ConnectionsPage,
})

function ConnectionsPage() {
  return <ConnectionManager />
}

function EnterpriseUpsell() {
  return (
    <EmptyState
      icon={Lock}
      title="Connections are an Enterprise feature"
      description="The connection vault securely stores third-party credentials for workflow actions. Upgrade to enable it."
      action={<Badge variant="accent">Enterprise</Badge>}
    />
  )
}

function ConnectionManager() {
  const {toast} = useToast()
  const queryClient = useQueryClient()
  const [createOpen, setCreateOpen] = useState(false)
  const [rotateTarget, setRotateTarget] = useState<WorkflowConnection | null>(null)
  const {data: features} = useEnterpriseFeatures()
  const showEnterpriseBadge = features !== undefined && !hasEnterpriseModule(features, 'workflows_advanced')

  const {data: connections = [], isLoading, isError, error} = useQuery({
    queryKey: ['workflow-connections'],
    queryFn: () => api.listWorkflowConnections(),
  })
  const showEnterpriseUpsell = isError && isEnterpriseConnectionsError(error)

  const invalidate = () => queryClient.invalidateQueries({queryKey: ['workflow-connections']})

  const deleteMutation = useMutation({
    mutationFn: (id: string) => api.deleteWorkflowConnection(id),
    onSuccess: () => {
      invalidate()
      toast({title: 'Connection deleted'})
    },
    onError: (error: Error) => {
      toast({title: 'Failed to delete connection', description: error.message, variant: 'destructive'})
    },
  })

  return (
    <div className="connections-page">
      <ConnectionsHeader
        showEnterpriseBadge={showEnterpriseBadge}
        canCreate={!showEnterpriseUpsell}
        onCreate={() => setCreateOpen(true)}
      />
      <div className="px-6 py-4">
        {isLoading ? (
          <div className="flex h-40 items-center justify-center rounded-md border">
            <Loader2 className="h-5 w-5 animate-spin" />
          </div>
        ) : showEnterpriseUpsell ? (
          <EnterpriseUpsell />
        ) : isError ? (
          <ErrorState message={error.message} compact />
        ) : connections.length === 0 ? (
          <EmptyState
            icon={KeyRound}
            title="No connections yet"
            description="Add a connection to use it from workflow actions."
            action={
              <Button size="sm" onClick={() => setCreateOpen(true)} className="gap-1.5">
                <Plus className="h-4 w-4" />
                New connection
              </Button>
            }
          />
        ) : (
          <div className="grid gap-3 sm:grid-cols-2 xl:grid-cols-3">
            {connections.map((connection) => (
              <ConnectionCard
                key={connection.id}
                connection={connection}
                onRotate={() => setRotateTarget(connection)}
                onDelete={() => deleteMutation.mutate(connection.id)}
                deleting={deleteMutation.isPending}
              />
            ))}
          </div>
        )}
      </div>
      <CreateConnectionDialog open={createOpen} onOpenChange={setCreateOpen} onCreated={invalidate} />
      <RotateConnectionDialog
        connection={rotateTarget}
        onOpenChange={(open) => {
          if (!open) setRotateTarget(null)
        }}
        onRotated={invalidate}
      />
    </div>
  )
}

function isEnterpriseConnectionsError(error: Error): boolean {
  const status = (error as Error & {status?: number}).status
  const message = error.message.toLowerCase()
  return (
    status === 403 ||
    message.includes('enterprise') ||
    (status === 400 && message.includes('invalid workflow id'))
  )
}

function ErrorState({
  title = 'Unable to load connections',
  message,
  compact = false,
}: {
  title?: string
  message: string
  compact?: boolean
}) {
  return (
    <div className={compact ? 'rounded-md border bg-card/40 p-4' : 'mx-auto max-w-xl px-4 py-16'}>
      <div className="text-sm font-semibold">{title}</div>
      <p className="mt-1 text-sm text-muted-foreground">{message}</p>
    </div>
  )
}

function ConnectionsHeader({
  showEnterpriseBadge,
  canCreate,
  onCreate,
}: {
  showEnterpriseBadge: boolean
  canCreate: boolean
  onCreate: () => void
}) {
  return (
    <div className="border-b bg-card/50 px-6 py-4">
      <PageHeader
        icon={KeyRound}
        title={
          <span className="flex items-center gap-2">
            Connections
            {showEnterpriseBadge && <Badge variant="accent" size="sm">Enterprise</Badge>}
          </span>
        }
        description="Encrypted credentials for workflow actions. Secrets are entered once and never shown again."
        actions={
          <Button size="sm" onClick={onCreate} disabled={!canCreate} className="gap-1.5">
            <Plus className="h-4 w-4" />
            New connection
          </Button>
        }
      />
    </div>
  )
}

function ConnectionCard({
  connection,
  onRotate,
  onDelete,
  deleting,
}: {
  connection: WorkflowConnection
  onRotate: () => void
  onDelete: () => void
  deleting: boolean
}) {
  const tags = Object.entries(connection.identifier_tags)
  return (
    <div className="rounded-md border bg-background p-3">
      <div className="flex items-start justify-between gap-2">
        <div className="min-w-0">
          <p className="truncate text-sm font-semibold">{connection.name}</p>
          <p className="mt-0.5 text-xs text-muted-foreground">{connection.type}</p>
        </div>
        <Badge variant="secondary" className="shrink-0 font-mono">
          ****{connection.last_four ?? '????'}
        </Badge>
      </div>
      {tags.length > 0 && (
        <div className="mt-2 flex flex-wrap gap-1">
          {tags.map(([key, value]) => (
            <Badge key={key} variant="neutral" size="sm" className="font-mono">
              {key}={value}
            </Badge>
          ))}
        </div>
      )}
      <div className="mt-3 flex gap-2">
        <Button size="sm" variant="outline" onClick={onRotate} className="gap-1.5">
          <RotateCw className="h-3.5 w-3.5" />
          Rotate
        </Button>
        <Button
          size="sm"
          variant="outline"
          disabled={deleting}
          onClick={onDelete}
          className="gap-1.5 text-destructive hover:text-destructive"
        >
          <Trash2 className="h-3.5 w-3.5" />
          Delete
        </Button>
      </div>
    </div>
  )
}

function parseIdentifierTags(raw: string): Record<string, string> {
  const tags: Record<string, string> = {}
  for (const line of raw.split('\n')) {
    const trimmed = line.trim()
    if (!trimmed) continue
    const index = trimmed.indexOf('=')
    if (index <= 0) continue
    const key = trimmed.slice(0, index).trim()
    const value = trimmed.slice(index + 1).trim()
    if (key) tags[key] = value
  }
  return tags
}

function CreateConnectionDialog({
  open,
  onOpenChange,
  onCreated,
}: {
  open: boolean
  onOpenChange: (open: boolean) => void
  onCreated: () => void
}) {
  const {toast} = useToast()
  const [type, setType] = useState('')
  const [name, setName] = useState('')
  const [tagsText, setTagsText] = useState('')
  const [secret, setSecret] = useState('')

  const reset = () => {
    setType('')
    setName('')
    setTagsText('')
    setSecret('')
  }

  const createMutation = useMutation({
    mutationFn: () =>
      api.createWorkflowConnection({
        type: type.trim(),
        name: name.trim(),
        identifier_tags: parseIdentifierTags(tagsText),
        secret,
      }),
    onSuccess: () => {
      onCreated()
      onOpenChange(false)
      reset()
      toast({title: 'Connection created'})
    },
    onError: (error: Error) => {
      toast({title: 'Failed to create connection', description: error.message, variant: 'destructive'})
    },
  })

  const canSubmit = type.trim().length > 0 && name.trim().length > 0 && secret.length > 0

  return (
    <Dialog
      open={open}
      onOpenChange={(next) => {
        onOpenChange(next)
        if (!next) reset()
      }}
    >
      <DialogContent>
        <DialogHeader>
          <DialogTitle>New connection</DialogTitle>
          <DialogDescription>
            The secret is encrypted immediately and never shown again. Store it somewhere safe.
          </DialogDescription>
        </DialogHeader>
        <div className="space-y-3">
          <div className="space-y-1.5">
            <Label htmlFor="connection-type">Type</Label>
            <Input
              id="connection-type"
              value={type}
              placeholder="webhook, incident, ticketing..."
              onChange={(event) => setType(event.target.value)}
            />
          </div>
          <div className="space-y-1.5">
            <Label htmlFor="connection-name">Name</Label>
            <Input
              id="connection-name"
              value={name}
              placeholder="prod-alerts"
              onChange={(event) => setName(event.target.value)}
            />
          </div>
          <div className="space-y-1.5">
            <Label htmlFor="connection-tags">Identifier tags</Label>
            <textarea
              id="connection-tags"
              className="min-h-20 w-full rounded-md border bg-background p-2 text-sm"
              value={tagsText}
              placeholder={'env=prod\nteam=payments'}
              onChange={(event) => setTagsText(event.target.value)}
            />
            <p className="text-[11px] text-muted-foreground">
              One key=value per line. Used to resolve connection groups.
            </p>
          </div>
          <div className="space-y-1.5">
            <Label htmlFor="connection-secret">Secret</Label>
            <Input
              id="connection-secret"
              type="password"
              autoComplete="off"
              value={secret}
              onChange={(event) => setSecret(event.target.value)}
            />
          </div>
        </div>
        <DialogFooter className="gap-2 pt-5">
          <Button type="button" variant="outline" onClick={() => onOpenChange(false)}>
            Cancel
          </Button>
          <Button
            type="button"
            disabled={!canSubmit || createMutation.isPending}
            onClick={() => createMutation.mutate()}
            className="gap-1.5"
          >
            {createMutation.isPending ? <Loader2 className="h-3.5 w-3.5 animate-spin" /> : null}
            Create
          </Button>
        </DialogFooter>
      </DialogContent>
    </Dialog>
  )
}

function RotateConnectionDialog({
  connection,
  onOpenChange,
  onRotated,
}: {
  connection: WorkflowConnection | null
  onOpenChange: (open: boolean) => void
  onRotated: () => void
}) {
  const {toast} = useToast()
  const [secret, setSecret] = useState('')

  const rotateMutation = useMutation({
    mutationFn: (id: string) => api.rotateWorkflowConnection(id, {secret}),
    onSuccess: () => {
      onRotated()
      onOpenChange(false)
      setSecret('')
      toast({title: 'Connection rotated'})
    },
    onError: (error: Error) => {
      toast({title: 'Failed to rotate connection', description: error.message, variant: 'destructive'})
    },
  })

  return (
    <Dialog
      open={connection !== null}
      onOpenChange={(next) => {
        onOpenChange(next)
        if (!next) setSecret('')
      }}
    >
      <DialogContent>
        <DialogHeader>
          <DialogTitle>Rotate secret</DialogTitle>
          <DialogDescription>
            Replace the stored secret for <span className="font-semibold">{connection?.name}</span>. The previous
            secret is discarded.
          </DialogDescription>
        </DialogHeader>
        <div className="space-y-1.5">
          <Label htmlFor="rotate-secret">New secret</Label>
          <Input
            id="rotate-secret"
            type="password"
            autoComplete="off"
            value={secret}
            onChange={(event) => setSecret(event.target.value)}
          />
        </div>
        <DialogFooter className="gap-2 pt-5">
          <Button type="button" variant="outline" onClick={() => onOpenChange(false)}>
            Cancel
          </Button>
          <Button
            type="button"
            disabled={secret.length === 0 || rotateMutation.isPending || connection === null}
            onClick={() => connection && rotateMutation.mutate(connection.id)}
            className="gap-1.5"
          >
            {rotateMutation.isPending ? <Loader2 className="h-3.5 w-3.5 animate-spin" /> : null}
            Rotate
          </Button>
        </DialogFooter>
      </DialogContent>
    </Dialog>
  )
}
