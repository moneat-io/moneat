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

import {BookOpen, Check, Copy, Loader2, Plus, Trash2, type LucideIcon} from 'lucide-react'
import {type ReactNode, useState} from 'react'
import {useMutation, useQuery, useQueryClient} from '@tanstack/react-query'
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
import {useTimezone} from '@/hooks/useTimezone'
import {formatDate as formatDateUtil} from '@/lib/date-format'

export interface ApiKeyRow {
  id: number
  name: string
  keyPrefix: string
  createdAt: string
  lastUsedAt?: string
}

export interface ApiKeysTabConfig<T extends ApiKeyRow> {
  readonly cardId: string
  readonly cardTitle: string
  readonly cardDescription: ReactNode
  readonly docsHref: string
  readonly icon: LucideIcon
  readonly emptyTitle: string
  readonly emptyDescription: string
  readonly queryKey: string[]
  readonly queryFn: () => Promise<{keys: T[]}>
  readonly queryEnabled?: boolean
  readonly createMutationFn: (name: string) => Promise<{key: string; name: string}>
  readonly deleteMutationFn: (id: number) => Promise<void>
  readonly createSuccessToast: {title: string; description: string}
  readonly revokeSuccessToast: {title: string; description: string}
  readonly createDialogTitle: string
  readonly createDialogDescription: string
  readonly inputId: string
  readonly inputPlaceholder: string
  readonly createdDialogTitle: string
  readonly revokeDialogTitle: string
  readonly revokeDialogDescription: (name: string) => string
  readonly setupInstructions?: ReactNode
}

function formatDate(iso: string | null | undefined, timezone: string): string {
  if (!iso) return '—'
  try {
    return formatDateUtil(new Date(iso), timezone)
  } catch {
    return '—'
  }
}

export function ApiKeysTabBase<T extends ApiKeyRow>(config: Readonly<ApiKeysTabConfig<T>>) {
  const {
    cardId,
    cardTitle,
    cardDescription,
    docsHref,
    icon: Icon,
    emptyTitle,
    emptyDescription,
    queryKey,
    queryFn,
    queryEnabled = true,
    createMutationFn,
    deleteMutationFn,
    createSuccessToast,
    revokeSuccessToast,
    createDialogTitle,
    createDialogDescription,
    inputId,
    inputPlaceholder,
    createdDialogTitle,
    revokeDialogTitle,
    revokeDialogDescription,
    setupInstructions,
  } = config

  const queryClient = useQueryClient()
  const {toast} = useToast()
  const {timezone} = useTimezone()
  const [createOpen, setCreateOpen] = useState(false)
  const [newKeyName, setNewKeyName] = useState('')
  const [createdKey, setCreatedKey] = useState<{key: string; name: string} | null>(null)
  const [revokeKey, setRevokeKey] = useState<T | null>(null)

  const {data: keysData, isPending} = useQuery({
    queryKey,
    queryFn,
    enabled: queryEnabled,
  })

  const keys = keysData?.keys ?? []

  const createMutation = useMutation({
    mutationFn: createMutationFn,
    onSuccess: (data) => {
      queryClient.invalidateQueries({queryKey})
      setCreatedKey({key: data.key, name: data.name})
      setNewKeyName('')
      setCreateOpen(false)
      toast(createSuccessToast)
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
    mutationFn: deleteMutationFn,
    onSuccess: () => {
      queryClient.invalidateQueries({queryKey})
      setRevokeKey(null)
      toast(revokeSuccessToast)
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

  let keysBody: ReactNode
  if (isPending) {
    keysBody = <p className="text-muted-foreground text-sm py-8">Loading keys...</p>
  } else if (keys.length === 0) {
    keysBody = (
      <div className="border rounded-lg p-8 text-center text-muted-foreground">
        <Icon className="h-10 w-10 mx-auto mb-2 opacity-50" />
        <p className="font-medium">{emptyTitle}</p>
        <p className="text-sm mt-1">{emptyDescription}</p>
        <Button variant="outline" className="mt-4" onClick={() => setCreateOpen(true)}>
          <Plus className="h-4 w-4 mr-2" />
          Create key
        </Button>
      </div>
    )
  } else {
    keysBody = (
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
    )
  }

  return (
    <>
      <Card id={cardId}>
        <CardHeader className="flex flex-col gap-4 space-y-0 sm:flex-row sm:items-start sm:justify-between">
          <div>
            <CardTitle className="flex items-center gap-2">
              <Icon className="h-5 w-5" />
              {cardTitle}
            </CardTitle>
            <CardDescription>{cardDescription}</CardDescription>
          </div>
          <div className="flex flex-wrap items-center gap-2">
            <Button variant="outline" asChild>
              <a href={docsHref} target="_blank" rel="noopener noreferrer">
                <BookOpen className="h-4 w-4" />
                Docs
              </a>
            </Button>
            <Button onClick={() => setCreateOpen(true)} disabled={!!createdKey}>
              <Plus className="h-4 w-4 mr-2" />
              New Key
            </Button>
          </div>
        </CardHeader>
        <CardContent>{keysBody}</CardContent>
      </Card>

      {setupInstructions}

      {/* Create key dialog */}
      <Dialog open={createOpen} onOpenChange={(o) => !o && handleCloseCreateDialog()}>
        <DialogContent>
          <DialogHeader>
            <DialogTitle>{createDialogTitle}</DialogTitle>
            <DialogDescription>{createDialogDescription}</DialogDescription>
          </DialogHeader>
          <div className="space-y-4 py-4">
            <div>
              <Label htmlFor={inputId}>Name</Label>
              <Input
                id={inputId}
                value={newKeyName}
                onChange={(e) => setNewKeyName(e.target.value)}
                placeholder={inputPlaceholder}
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
            <DialogTitle>{createdDialogTitle}</DialogTitle>
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
            <DialogTitle>{revokeDialogTitle}</DialogTitle>
            <DialogDescription>
              {revokeKey ? revokeDialogDescription(revokeKey.name) : ''}
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
