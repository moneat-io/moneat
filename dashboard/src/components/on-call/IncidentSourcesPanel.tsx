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
import {ExternalLink, Link2, Plus, X} from 'lucide-react'

import {api} from '@/lib/api'
import type {IncidentSourceType, LinkIncidentSourceInput} from '@/lib/api/types'
import {Badge} from '@/components/ui/badge'
import {Button} from '@/components/ui/button'
import {SectionCard} from '@/components/ui/section-card'
import {Input} from '@/components/ui/input'
import {Label} from '@/components/ui/label'
import {
  Dialog,
  DialogContent,
  DialogDescription,
  DialogFooter,
  DialogHeader,
  DialogTitle,
} from '@/components/ui/dialog'
import {
  Select,
  SelectContent,
  SelectItem,
  SelectTrigger,
  SelectValue,
} from '@/components/ui/select'
import {useToast} from '@/hooks/useToast'
import {LINKABLE_SOURCE_TYPES, formatDateTime, sourceTypeLabel} from './incident-modeling'
import {parseHttpUrl} from './safe-url'

interface IncidentSourcesPanelProps {
  incidentId: string
}

export function IncidentSourcesPanel({incidentId}: Readonly<IncidentSourcesPanelProps>) {
  const queryClient = useQueryClient()
  const {toast} = useToast()
  const [linkOpen, setLinkOpen] = useState(false)

  const sourcesKey = ['incident-sources', incidentId]
  const {data: sources, isLoading} = useQuery({
    queryKey: sourcesKey,
    queryFn: () => api.getIncidentSources(incidentId),
  })

  const linkMutation = useMutation({
    mutationFn: (input: LinkIncidentSourceInput) => api.linkIncidentSource(incidentId, input),
    onSuccess: (data) => {
      queryClient.setQueryData(sourcesKey, data)
      setLinkOpen(false)
      toast({title: 'Source linked'})
    },
    onError: (error: Error) => toast({title: 'Error', description: error.message, variant: 'destructive'}),
  })

  const unlinkMutation = useMutation({
    mutationFn: (sourceId: string) => api.unlinkIncidentSource(incidentId, sourceId),
    onSuccess: () => {
      queryClient.invalidateQueries({queryKey: sourcesKey})
      toast({title: 'Source unlinked'})
    },
    onError: (error: Error) => toast({title: 'Error', description: error.message, variant: 'destructive'}),
  })

  const list = sources ?? []

  return (
    <SectionCard
      title="Sources"
      icon={Link2}
      iconTone="accent"
      count={list.length || undefined}
      actions={
        <Button variant="ghost" size="sm" className="h-7 gap-1 px-2 text-xs" onClick={() => setLinkOpen(true)}>
          <Plus className="h-3.5 w-3.5" /> Link
        </Button>
      }
      bodyClassName="space-y-2"
    >
      {isLoading && <p className="text-xs text-muted-foreground">Loading sources…</p>}
      {!isLoading && list.length === 0 && (
        <p className="text-xs text-muted-foreground">
          No sources linked yet. Link a runbook, message, or dashboard for context.
        </p>
      )}
      {list.map((source) => {
        const safeUrl = parseHttpUrl(source.sourceUrl)
        return (
        <div key={source.id} className="flex items-start justify-between gap-2 rounded-lg border p-2.5">
          <div className="min-w-0">
            <div className="flex items-center gap-1.5">
              <Badge variant="neutral" size="sm">
                {sourceTypeLabel(source.sourceType)}
              </Badge>
              <span className="truncate text-sm font-medium">{source.label ?? source.sourceKey}</span>
            </div>
            {safeUrl ? (
              <a
                href={safeUrl}
                target="_blank"
                rel="noopener noreferrer"
                className="mt-0.5 inline-flex items-center gap-1 text-xs text-primary underline underline-offset-2"
              >
                <ExternalLink className="h-3 w-3" />
                <span className="truncate">{safeUrl}</span>
              </a>
            ) : (
              // Render an unsafe or missing URL as inert text — never a link.
              <p className="mt-0.5 truncate text-xs text-muted-foreground">
                {source.sourceUrl || source.sourceKey}
              </p>
            )}
            <p className="mt-0.5 text-[11px] text-muted-foreground">
              Linked {formatDateTime(source.createdAt)}
            </p>
          </div>
          <Button
            variant="ghost"
            size="icon"
            className="h-6 w-6 shrink-0"
            aria-label="Unlink source"
            disabled={unlinkMutation.isPending}
            onClick={() => unlinkMutation.mutate(source.id)}
          >
            <X className="h-4 w-4" />
          </Button>
        </div>
        )
      })}

      {linkOpen && (
        <LinkSourceDialog
          isPending={linkMutation.isPending}
          onClose={() => setLinkOpen(false)}
          onSubmit={(input) => linkMutation.mutate(input)}
        />
      )}
    </SectionCard>
  )
}

interface LinkSourceDialogProps {
  isPending: boolean
  onClose: () => void
  onSubmit: (input: LinkIncidentSourceInput) => void
}

function LinkSourceDialog({isPending, onClose, onSubmit}: Readonly<LinkSourceDialogProps>) {
  const [sourceType, setSourceType] = useState<IncidentSourceType>('URL')
  const [sourceUrl, setSourceUrl] = useState('')
  const [sourceKey, setSourceKey] = useState('')
  const [label, setLabel] = useState('')
  const [urlError, setUrlError] = useState<string | null>(null)

  const isUrl = sourceType === 'URL'
  const canSubmit = isUrl ? sourceUrl.trim().length > 0 : sourceKey.trim().length > 0

  const submit = () => {
    if (!canSubmit) return
    const trimmedUrl = sourceUrl.trim()
    const trimmedKey = sourceKey.trim()
    // Enforce an http(s)-only boundary before the URL is ever stored/rendered.
    const normalizedUrl = trimmedUrl ? parseHttpUrl(trimmedUrl) : null
    if (isUrl && !normalizedUrl) {
      setUrlError('Enter a valid http:// or https:// URL.')
      return
    }
    if (!isUrl && trimmedUrl && !normalizedUrl) {
      setUrlError('Enter a valid http:// or https:// URL, or leave it blank.')
      return
    }
    setUrlError(null)
    onSubmit({
      sourceType,
      sourceKey: trimmedKey || (isUrl ? (normalizedUrl ?? undefined) : undefined),
      sourceUrl: normalizedUrl ?? undefined,
      label: label.trim() || undefined,
    })
  }

  return (
    <Dialog open onOpenChange={(open) => !open && onClose()}>
      <DialogContent>
        <DialogHeader>
          <DialogTitle>Link a source</DialogTitle>
          <DialogDescription>
            Attach a supporting reference so responders can find the evidence quickly.
          </DialogDescription>
        </DialogHeader>
        <div className="grid gap-3 py-2">
          <div className="grid gap-1.5">
            <Label htmlFor="source-type">Type</Label>
            <Select
              value={sourceType}
              onValueChange={(value) => {
                setSourceType(value as IncidentSourceType)
                setUrlError(null)
              }}
            >
              <SelectTrigger id="source-type">
                <SelectValue />
              </SelectTrigger>
              <SelectContent>
                {LINKABLE_SOURCE_TYPES.map((type) => (
                  <SelectItem key={type} value={type}>
                    {sourceTypeLabel(type)}
                  </SelectItem>
                ))}
              </SelectContent>
            </Select>
          </div>

          {isUrl ? (
            <div className="grid gap-1.5">
              <Label htmlFor="source-url">
                URL <span className="text-danger-fg">*</span>
              </Label>
              <Input
                id="source-url"
                type="url"
                value={sourceUrl}
                onChange={(e) => {
                  setSourceUrl(e.target.value)
                  setUrlError(null)
                }}
                placeholder="https://…"
                aria-invalid={urlError ? true : undefined}
              />
              {urlError && <p className="text-xs text-danger-fg">{urlError}</p>}
            </div>
          ) : (
            <>
              <div className="grid gap-1.5">
                <Label htmlFor="source-key">
                  Reference <span className="text-danger-fg">*</span>
                </Label>
                <Input
                  id="source-key"
                  value={sourceKey}
                  onChange={(e) => setSourceKey(e.target.value)}
                  placeholder="Message ID or permalink"
                />
              </div>
              <div className="grid gap-1.5">
                <Label htmlFor="source-url-optional">URL (optional)</Label>
                <Input
                  id="source-url-optional"
                  type="url"
                  value={sourceUrl}
                  onChange={(e) => {
                    setSourceUrl(e.target.value)
                    setUrlError(null)
                  }}
                  placeholder="https://…"
                  aria-invalid={urlError ? true : undefined}
                />
                {urlError && <p className="text-xs text-danger-fg">{urlError}</p>}
              </div>
            </>
          )}

          <div className="grid gap-1.5">
            <Label htmlFor="source-label">Label (optional)</Label>
            <Input
              id="source-label"
              value={label}
              onChange={(e) => setLabel(e.target.value)}
              placeholder="How this appears on the incident"
            />
          </div>
        </div>
        <DialogFooter>
          <Button variant="outline" onClick={onClose} disabled={isPending}>
            Cancel
          </Button>
          <Button onClick={submit} disabled={!canSubmit || isPending}>
            {isPending ? 'Linking…' : 'Link source'}
          </Button>
        </DialogFooter>
      </DialogContent>
    </Dialog>
  )
}
