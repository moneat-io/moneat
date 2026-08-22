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
import {
  ArrowDown,
  ArrowUp,
  Clock,
  Download,
  Filter,
  History,
  Link as LinkIcon,
  MessageSquarePlus,
  MoreHorizontal,
  Pencil,
  RotateCcw,
  Trash2,
} from 'lucide-react'

import {api} from '@/lib/api'
import type {
  EditIncidentTimelineInput,
  IncidentFieldValue,
  IncidentTimelineEntry,
  IncidentTimelineFilterInput,
  IncidentTimelineProvenance,
  IncidentTimelineVisibility,
} from '@/lib/api/types'
import {Badge} from '@/components/ui/badge'
import {Button} from '@/components/ui/button'
import {SectionCard} from '@/components/ui/section-card'
import {EmptyState} from '@/components/ui/empty-state'
import {Input} from '@/components/ui/input'
import {Label} from '@/components/ui/label'
import {Textarea} from '@/components/ui/textarea'
import {Switch} from '@/components/ui/switch'
import {
  Select,
  SelectContent,
  SelectItem,
  SelectTrigger,
  SelectValue,
} from '@/components/ui/select'
import {
  Dialog,
  DialogContent,
  DialogDescription,
  DialogFooter,
  DialogHeader,
  DialogTitle,
} from '@/components/ui/dialog'
import {
  DropdownMenu,
  DropdownMenuCheckboxItem,
  DropdownMenuContent,
  DropdownMenuItem,
  DropdownMenuLabel,
  DropdownMenuSeparator,
  DropdownMenuTrigger,
} from '@/components/ui/dropdown-menu'
import {useToast} from '@/hooks/useToast'
import {cn} from '@/lib/utils'
import {
  PROVENANCE_META,
  TIMELINE_PROVENANCES,
  TIMELINE_VISIBILITIES,
  TIMELINE_VISIBILITY_META,
  buildTimelineEditPayload,
  formatDateTime,
  humanizeKey,
  shortResourceId,
  summarizeTimelineDetails,
  timeAgo,
  timelineEventStyle,
} from './incident-modeling'
import {parseHttpUrl} from './safe-url'

interface IncidentTimelinePanelProps {
  incidentId: string
}

function detailString(value: IncidentFieldValue | undefined): string | null {
  if (typeof value === 'string') return value
  if (typeof value === 'number' || typeof value === 'boolean') return String(value)
  return null
}

export function IncidentTimelinePanel({incidentId}: Readonly<IncidentTimelinePanelProps>) {
  const queryClient = useQueryClient()
  const {toast} = useToast()

  const [showFilters, setShowFilters] = useState(false)
  const [eventTypes, setEventTypes] = useState<string[]>([])
  const [provenance, setProvenance] = useState<IncidentTimelineProvenance[]>([])
  const [visibility, setVisibility] = useState<IncidentTimelineVisibility[]>([])
  const [includeDeleted, setIncludeDeleted] = useState(false)
  const [revisionsFor, setRevisionsFor] = useState<IncidentTimelineEntry | null>(null)
  const [annotateFor, setAnnotateFor] = useState<IncidentTimelineEntry | null>(null)
  const [editFor, setEditFor] = useState<IncidentTimelineEntry | null>(null)
  const [removeFor, setRemoveFor] = useState<{entry: IncidentTimelineEntry; restore: boolean} | null>(
    null
  )

  const filters: IncidentTimelineFilterInput = {
    eventType: eventTypes.length ? eventTypes : undefined,
    provenance: provenance.length ? provenance : undefined,
    visibility: visibility.length ? visibility : undefined,
    includeDeleted,
  }

  const timelineKey = ['incident-canonical-timeline', incidentId, filters] as const
  const {data: entries, isLoading} = useQuery({
    queryKey: timelineKey,
    queryFn: () => api.getOnCallIncidentTimeline(incidentId, filters),
  })
  const membersQuery = useQuery({
    queryKey: ['org-members'],
    queryFn: () => api.getOrgMembers(),
  })
  const memberName = (userId?: string): string | null => {
    if (!userId) return null
    const member = membersQuery.data?.members.find((m) => m.userId === userId)
    return member ? member.name ?? member.email : null
  }
  // Resolve a detail user id to a display name, falling back to a short opaque id.
  const resolveUserName = (userId?: string): string => {
    if (!userId) return 'a responder'
    return memberName(userId) ?? shortResourceId(userId)
  }

  // Enumerate available event types from the type-unfiltered list so the filter
  // menu stays stable as the visible list narrows.
  const typeOptionsQuery = useQuery({
    queryKey: ['incident-timeline-event-types', incidentId, includeDeleted],
    queryFn: () => api.getOnCallIncidentTimeline(incidentId, {includeDeleted}),
  })
  const seenTypes = [...new Set((typeOptionsQuery.data ?? []).map((entry) => entry.eventType))].sort(
    (left, right) => left.localeCompare(right)
  )

  const invalidate = () => queryClient.invalidateQueries({queryKey: ['incident-canonical-timeline', incidentId]})
  const onError = (error: Error) =>
    toast({title: 'Error', description: error.message, variant: 'destructive'})

  const reorderMutation = useMutation({
    mutationFn: (orderedIds: string[]) => api.reorderIncidentTimeline(incidentId, orderedIds),
    onSuccess: () => invalidate(),
    onError,
  })
  const deleteMutation = useMutation({
    mutationFn: ({eventId, reason}: {eventId: string; reason?: string}) =>
      api.deleteIncidentTimelineEvent(incidentId, eventId, reason),
    onSuccess: () => {
      invalidate()
      setRemoveFor(null)
      toast({title: 'Event removed', description: 'Original evidence is retained in the revision history.'})
    },
    onError,
  })
  const restoreMutation = useMutation({
    mutationFn: ({eventId, reason}: {eventId: string; reason?: string}) =>
      api.restoreIncidentTimelineEvent(incidentId, eventId, reason),
    onSuccess: () => {
      invalidate()
      setRemoveFor(null)
      toast({title: 'Event restored'})
    },
    onError,
  })

  const list = entries ?? []
  const activeFilterCount =
    eventTypes.length + provenance.length + visibility.length + (includeDeleted ? 1 : 0)
  // Reordering must submit every active event exactly once, so it is only
  // offered when the visible list is the full, unfiltered active set.
  const canReorder = activeFilterCount === 0 && list.every((entry) => !entry.deletedAt)

  const moveEntry = (index: number, direction: -1 | 1) => {
    const target = index + direction
    if (target < 0 || target >= list.length) return
    const ids = list.map((entry) => entry.id)
    ;[ids[index], ids[target]] = [ids[target], ids[index]]
    reorderMutation.mutate(ids)
  }

  const exportTimeline = async () => {
    try {
      const data = await api.exportOnCallIncidentTimeline(incidentId)
      const blob = new Blob([JSON.stringify(data, null, 2)], {type: 'application/json'})
      const url = URL.createObjectURL(blob)
      const anchor = document.createElement('a')
      anchor.href = url
      anchor.download = `incident-${incidentId}-timeline.json`
      // Firefox (and some others) only trigger a download when the anchor is in
      // the document, so attach it before clicking and remove it afterwards.
      document.body.appendChild(anchor)
      anchor.click()
      anchor.remove()
      URL.revokeObjectURL(url)
    } catch (error) {
      onError(error as Error)
    }
  }

  const headerActions = (
    <>
      <Button
        variant={showFilters ? 'secondary' : 'ghost'}
        size="sm"
        className="h-7 gap-1 px-2 text-xs"
        onClick={() => setShowFilters((open) => !open)}
      >
        <Filter className="h-3.5 w-3.5" /> Filters
        {activeFilterCount > 0 && (
          <span className="rounded-full bg-primary/15 px-1 text-[10px] text-primary">
            {activeFilterCount}
          </span>
        )}
      </Button>
      <Button variant="ghost" size="sm" className="h-7 gap-1 px-2 text-xs" onClick={exportTimeline}>
        <Download className="h-3.5 w-3.5" /> Export JSON
      </Button>
    </>
  )

  return (
    <SectionCard title="Timeline" icon={Clock} iconTone="muted" actions={headerActions} bodyClassName="space-y-3">
      {showFilters && (
        <TimelineFilterBar
          seenTypes={seenTypes}
          eventTypes={eventTypes}
          setEventTypes={setEventTypes}
          provenance={provenance}
          setProvenance={setProvenance}
          visibility={visibility}
          setVisibility={setVisibility}
          includeDeleted={includeDeleted}
          setIncludeDeleted={setIncludeDeleted}
          onClear={() => {
            setEventTypes([])
            setProvenance([])
            setVisibility([])
            setIncludeDeleted(false)
          }}
        />
      )}

      {isLoading && <p className="text-xs text-muted-foreground">Loading timeline…</p>}

      {!isLoading && list.length === 0 && (
        <EmptyState
          icon={Clock}
          title="No timeline events"
          description={
            activeFilterCount > 0
              ? 'No events match the current filters.'
              : 'Events recorded on this incident will appear here.'
          }
        />
      )}

      {list.length > 0 && (
        <div className="relative">
          {list.map((entry, index) => (
            <TimelineRow
              key={entry.id}
              entry={entry}
              isLast={index === list.length - 1}
              actorName={memberName(entry.actorUserId)}
              resolveUser={resolveUserName}
              canReorder={canReorder}
              canMoveUp={index > 0}
              canMoveDown={index < list.length - 1}
              busy={reorderMutation.isPending}
              onMoveUp={() => moveEntry(index, -1)}
              onMoveDown={() => moveEntry(index, 1)}
              onAnnotate={() => setAnnotateFor(entry)}
              onEdit={() => setEditFor(entry)}
              onRemove={() => setRemoveFor({entry, restore: false})}
              onRestore={() => setRemoveFor({entry, restore: true})}
              onRevisions={() => setRevisionsFor(entry)}
            />
          ))}
        </div>
      )}

      {annotateFor && (
        <AnnotateDialog
          incidentId={incidentId}
          entry={annotateFor}
          onClose={() => setAnnotateFor(null)}
          onSaved={() => {
            invalidate()
            setAnnotateFor(null)
          }}
        />
      )}
      {editFor && (
        <EditEventDialog
          incidentId={incidentId}
          entry={editFor}
          onClose={() => setEditFor(null)}
          onSaved={() => {
            invalidate()
            setEditFor(null)
          }}
        />
      )}
      {removeFor && (
        <RemoveDialog
          restore={removeFor.restore}
          isPending={deleteMutation.isPending || restoreMutation.isPending}
          onClose={() => setRemoveFor(null)}
          onConfirm={(reason) =>
            removeFor.restore
              ? restoreMutation.mutate({eventId: removeFor.entry.id, reason})
              : deleteMutation.mutate({eventId: removeFor.entry.id, reason})
          }
        />
      )}
      {revisionsFor && (
        <RevisionsDialog
          incidentId={incidentId}
          entry={revisionsFor}
          memberName={memberName}
          onClose={() => setRevisionsFor(null)}
        />
      )}
    </SectionCard>
  )
}

interface TimelineRowProps {
  entry: IncidentTimelineEntry
  isLast: boolean
  actorName: string | null
  resolveUser: (id?: string) => string
  canReorder: boolean
  canMoveUp: boolean
  canMoveDown: boolean
  busy: boolean
  onMoveUp: () => void
  onMoveDown: () => void
  onAnnotate: () => void
  onEdit: () => void
  onRemove: () => void
  onRestore: () => void
  onRevisions: () => void
}

function TimelineRow({
  entry,
  isLast,
  actorName,
  resolveUser,
  canReorder,
  canMoveUp,
  canMoveDown,
  busy,
  onMoveUp,
  onMoveDown,
  onAnnotate,
  onEdit,
  onRemove,
  onRestore,
  onRevisions,
}: Readonly<TimelineRowProps>) {
  const style = timelineEventStyle(entry.eventType)
  const Icon = style.icon
  const deleted = Boolean(entry.deletedAt)
  const note = detailString(entry.details?.note)
  const observedDiffers = Boolean(entry.observedAt) && entry.observedAt !== entry.originalOccurredAt
  const provenanceMeta = PROVENANCE_META[entry.provenance] ?? {
    label: humanizeKey(entry.provenance ?? ''),
    variant: 'neutral' as const,
  }
  const visibilityMeta = TIMELINE_VISIBILITY_META[entry.visibility] ?? {
    label: humanizeKey(entry.visibility ?? ''),
    variant: 'neutral' as const,
  }
  const safeSourceUrl = parseHttpUrl(entry.sourceUrl)
  const detailSummary = summarizeTimelineDetails(entry.eventType, entry.details, resolveUser)

  return (
    <div className={cn('relative flex gap-3 pb-6 last:pb-0', deleted && 'opacity-60')}>
      {!isLast && <div className="absolute left-[15px] top-8 bottom-0 w-px bg-border" />}
      <div className={cn('z-10 flex h-8 w-8 shrink-0 items-center justify-center rounded-full', style.bgColor)}>
        <Icon className={cn('h-4 w-4', style.color)} />
      </div>
      <div className="min-w-0 flex-1 pt-0.5">
        <div className="flex items-start justify-between gap-2">
          <div className="flex min-w-0 flex-wrap items-center gap-1.5">
            <span className={cn('text-sm font-medium', deleted && 'line-through')}>{style.label}</span>
            <Badge variant={provenanceMeta.variant} size="sm">
              {provenanceMeta.label}
            </Badge>
            <Badge variant={visibilityMeta.variant} size="sm">
              {visibilityMeta.label}
            </Badge>
            {entry.editedAt && !deleted && (
              <span className="text-[11px] text-muted-foreground">· edited</span>
            )}
            {deleted && (
              <Badge variant="warning" size="sm">
                Removed
              </Badge>
            )}
          </div>
          <div className="flex shrink-0 items-center gap-1">
            <span className="text-xs text-muted-foreground" title={formatDateTime(entry.originalOccurredAt)}>
              {timeAgo(entry.originalOccurredAt)}
            </span>
            <RowMenu
              deleted={deleted}
              canReorder={canReorder}
              canMoveUp={canMoveUp}
              canMoveDown={canMoveDown}
              busy={busy}
              onMoveUp={onMoveUp}
              onMoveDown={onMoveDown}
              onAnnotate={onAnnotate}
              onEdit={onEdit}
              onRemove={onRemove}
              onRestore={onRestore}
              onRevisions={onRevisions}
            />
          </div>
        </div>

        <div className="mt-0.5 flex flex-wrap items-center gap-x-2 gap-y-0.5 text-[11px] text-muted-foreground">
          {actorName && <span>by {actorName}</span>}
          <span title={formatDateTime(entry.originalOccurredAt)}>
            occurred {formatDateTime(entry.originalOccurredAt)}
          </span>
          {observedDiffers && <span>· observed {formatDateTime(entry.observedAt)}</span>}
        </div>

        {detailSummary.summary && (
          <p className="mt-1 text-xs text-foreground">{detailSummary.summary}</p>
        )}

        {detailSummary.fallback.length > 0 && (
          <details className="mt-1 text-xs">
            <summary className="cursor-pointer text-muted-foreground hover:text-foreground">Details</summary>
            <dl className="mt-1 grid grid-cols-[auto_1fr] gap-x-3 gap-y-0.5">
              {detailSummary.fallback.map(([key, value]) => (
                <div key={key} className="contents">
                  <dt className="text-muted-foreground">{key}</dt>
                  <dd className="break-words font-mono text-[11px] text-foreground">{value}</dd>
                </div>
              ))}
            </dl>
          </details>
        )}

        {entry.sourceType && (
          <p className="mt-1 text-xs">
            <LinkIcon className="mr-1 inline h-3 w-3 text-muted-foreground" />
            {safeSourceUrl ? (
              <a
                href={safeSourceUrl}
                className="text-primary underline underline-offset-2"
              >
                {entry.sourceReference ?? safeSourceUrl}
              </a>
            ) : (
              // Unsafe or missing URL: render the reference as inert text, never a link.
              <span className="text-muted-foreground">
                {humanizeKey(entry.sourceType)}
                {entry.sourceReference ? `: ${entry.sourceReference}` : ''}
              </span>
            )}
          </p>
        )}

        {note && (
          <p className="mt-1.5 rounded-lg bg-muted/50 p-2.5 text-xs italic text-muted-foreground">
            &quot;{note}&quot;
          </p>
        )}

        {entry.annotation && (
          <p className="mt-1.5 rounded-lg border-l-2 border-info-border bg-info-bg/50 p-2.5 text-xs text-foreground">
            <span className="font-medium">Annotation: </span>
            {entry.annotation}
          </p>
        )}
      </div>
    </div>
  )
}

interface RowMenuProps {
  deleted: boolean
  canReorder: boolean
  canMoveUp: boolean
  canMoveDown: boolean
  busy: boolean
  onMoveUp: () => void
  onMoveDown: () => void
  onAnnotate: () => void
  onEdit: () => void
  onRemove: () => void
  onRestore: () => void
  onRevisions: () => void
}

function RowMenu({
  deleted,
  canReorder,
  canMoveUp,
  canMoveDown,
  busy,
  onMoveUp,
  onMoveDown,
  onAnnotate,
  onEdit,
  onRemove,
  onRestore,
  onRevisions,
}: Readonly<RowMenuProps>) {
  return (
    <DropdownMenu>
      <DropdownMenuTrigger asChild>
        <Button variant="ghost" size="icon" className="h-6 w-6" aria-label="Event actions">
          <MoreHorizontal className="h-4 w-4" />
        </Button>
      </DropdownMenuTrigger>
      <DropdownMenuContent align="end" className="w-44">
        <DropdownMenuLabel>Evidence</DropdownMenuLabel>
        {!deleted && (
          <>
            <DropdownMenuItem onSelect={onAnnotate}>
              <MessageSquarePlus className="mr-2 h-4 w-4" /> Annotate
            </DropdownMenuItem>
            <DropdownMenuItem onSelect={onEdit}>
              <Pencil className="mr-2 h-4 w-4" /> Edit
            </DropdownMenuItem>
          </>
        )}
        {canReorder && !deleted && (
          <>
            <DropdownMenuItem disabled={!canMoveUp || busy} onSelect={onMoveUp}>
              <ArrowUp className="mr-2 h-4 w-4" /> Move up
            </DropdownMenuItem>
            <DropdownMenuItem disabled={!canMoveDown || busy} onSelect={onMoveDown}>
              <ArrowDown className="mr-2 h-4 w-4" /> Move down
            </DropdownMenuItem>
          </>
        )}
        <DropdownMenuItem onSelect={onRevisions}>
          <History className="mr-2 h-4 w-4" /> Revision history
        </DropdownMenuItem>
        <DropdownMenuSeparator />
        {deleted ? (
          <DropdownMenuItem onSelect={onRestore}>
            <RotateCcw className="mr-2 h-4 w-4" /> Restore
          </DropdownMenuItem>
        ) : (
          <DropdownMenuItem onSelect={onRemove} className="text-danger-fg">
            <Trash2 className="mr-2 h-4 w-4" /> Remove
          </DropdownMenuItem>
        )}
      </DropdownMenuContent>
    </DropdownMenu>
  )
}

interface TimelineFilterBarProps {
  seenTypes: string[]
  eventTypes: string[]
  setEventTypes: (value: string[]) => void
  provenance: IncidentTimelineProvenance[]
  setProvenance: (value: IncidentTimelineProvenance[]) => void
  visibility: IncidentTimelineVisibility[]
  setVisibility: (value: IncidentTimelineVisibility[]) => void
  includeDeleted: boolean
  setIncludeDeleted: (value: boolean) => void
  onClear: () => void
}

function TimelineFilterBar(props: Readonly<TimelineFilterBarProps>) {
  const toggle = <T,>(list: T[], value: T): T[] =>
    list.includes(value) ? list.filter((item) => item !== value) : [...list, value]

  return (
    <div className="flex flex-wrap items-center gap-2 rounded-lg border bg-muted/30 p-2">
      <MultiFilterMenu
        label="Event type"
        empty={props.seenTypes.length === 0}
        selected={props.eventTypes}
        options={props.seenTypes.map((type) => ({value: type, label: humanizeKey(type)}))}
        onToggle={(value) => props.setEventTypes(toggle(props.eventTypes, value))}
      />
      <MultiFilterMenu
        label="Provenance"
        selected={props.provenance}
        options={TIMELINE_PROVENANCES.map((value) => ({value, label: PROVENANCE_META[value].label}))}
        onToggle={(value) =>
          props.setProvenance(toggle(props.provenance, value as IncidentTimelineProvenance))
        }
      />
      <MultiFilterMenu
        label="Visibility"
        selected={props.visibility}
        options={TIMELINE_VISIBILITIES.map((value) => ({
          value,
          label: TIMELINE_VISIBILITY_META[value].label,
        }))}
        onToggle={(value) =>
          props.setVisibility(toggle(props.visibility, value as IncidentTimelineVisibility))
        }
      />
      <label className="ml-1 flex items-center gap-1.5 text-xs text-muted-foreground">
        <Switch checked={props.includeDeleted} onCheckedChange={props.setIncludeDeleted} />
        Show removed
      </label>
      <Button
        variant="ghost"
        size="sm"
        className="ml-auto h-7 px-2 text-xs"
        onClick={props.onClear}
      >
        Clear
      </Button>
    </div>
  )
}

interface MultiFilterMenuProps {
  label: string
  selected: string[]
  options: {value: string; label: string}[]
  onToggle: (value: string) => void
  empty?: boolean
}

function MultiFilterMenu({
  label,
  selected,
  options,
  onToggle,
  empty,
}: Readonly<MultiFilterMenuProps>) {
  return (
    <DropdownMenu>
      <DropdownMenuTrigger asChild>
        <Button variant="outline" size="sm" className="h-7 gap-1 px-2 text-xs" disabled={empty}>
          {label}
          {selected.length > 0 && (
            <span className="rounded-full bg-primary/15 px-1 text-[10px] text-primary">
              {selected.length}
            </span>
          )}
        </Button>
      </DropdownMenuTrigger>
      <DropdownMenuContent align="start" className="max-h-64 w-48 overflow-y-auto">
        {options.map((option) => (
          <DropdownMenuCheckboxItem
            key={option.value}
            checked={selected.includes(option.value)}
            onCheckedChange={() => onToggle(option.value)}
            onSelect={(event) => event.preventDefault()}
          >
            {option.label}
          </DropdownMenuCheckboxItem>
        ))}
      </DropdownMenuContent>
    </DropdownMenu>
  )
}

interface AnnotateDialogProps {
  incidentId: string
  entry: IncidentTimelineEntry
  onClose: () => void
  onSaved: () => void
}

function AnnotateDialog({incidentId, entry, onClose, onSaved}: Readonly<AnnotateDialogProps>) {
  const {toast} = useToast()
  const [annotation, setAnnotation] = useState(entry.annotation ?? '')
  const [reason, setReason] = useState('')
  const mutation = useMutation({
    mutationFn: () =>
      api.annotateIncidentTimelineEvent(incidentId, entry.id, {
        annotation: annotation.trim() || undefined,
        reason: reason.trim() || undefined,
      }),
    onSuccess: onSaved,
    onError: (error: Error) => toast({title: 'Error', description: error.message, variant: 'destructive'}),
  })

  return (
    <Dialog open onOpenChange={(open) => !open && onClose()}>
      <DialogContent>
        <DialogHeader>
          <DialogTitle>Annotate event</DialogTitle>
          <DialogDescription>
            Add context without changing the original recorded event.
          </DialogDescription>
        </DialogHeader>
        <div className="grid gap-3 py-2">
          <div className="grid gap-1.5">
            <Label htmlFor="annotation">Annotation</Label>
            <Textarea
              id="annotation"
              value={annotation}
              onChange={(e) => setAnnotation(e.target.value)}
              rows={3}
              placeholder="Clarify what happened, or leave empty to clear."
            />
          </div>
          <div className="grid gap-1.5">
            <Label htmlFor="annotation-reason">Reason (optional)</Label>
            <Input
              id="annotation-reason"
              value={reason}
              onChange={(e) => setReason(e.target.value)}
              placeholder="Recorded in the revision history"
            />
          </div>
        </div>
        <DialogFooter>
          <Button variant="outline" onClick={onClose} disabled={mutation.isPending}>
            Cancel
          </Button>
          <Button onClick={() => mutation.mutate()} disabled={mutation.isPending}>
            {mutation.isPending ? 'Saving…' : 'Save annotation'}
          </Button>
        </DialogFooter>
      </DialogContent>
    </Dialog>
  )
}

interface EditEventDialogProps {
  incidentId: string
  entry: IncidentTimelineEntry
  onClose: () => void
  onSaved: () => void
}

function toLocalInputValue(iso: string): string {
  const date = new Date(iso)
  if (Number.isNaN(date.getTime())) return ''
  const offsetMs = date.getTimezoneOffset() * 60_000
  return new Date(date.getTime() - offsetMs).toISOString().slice(0, 16)
}

function EditEventDialog({incidentId, entry, onClose, onSaved}: Readonly<EditEventDialogProps>) {
  const {toast} = useToast()
  const initialOccurredAt = toLocalInputValue(entry.originalOccurredAt)
  const [eventType, setEventType] = useState(entry.eventType)
  const [visibility, setVisibility] = useState<IncidentTimelineVisibility>(entry.visibility)
  const [occurredAt, setOccurredAt] = useState(initialOccurredAt)
  const [detailsText, setDetailsText] = useState(JSON.stringify(entry.details ?? {}, null, 2))
  const [reason, setReason] = useState('')
  const [detailsError, setDetailsError] = useState<string | null>(null)

  const mutation = useMutation({
    mutationFn: (payload: EditIncidentTimelineInput) =>
      api.editIncidentTimelineEvent(incidentId, entry.id, payload),
    onSuccess: onSaved,
    onError: (error: Error) => toast({title: 'Error', description: error.message, variant: 'destructive'}),
  })

  const submit = () => {
    const result = buildTimelineEditPayload(
      {
        eventType: entry.eventType,
        visibility: entry.visibility,
        occurredAtLocal: initialOccurredAt,
        details: entry.details ?? {},
      },
      {eventType, visibility, occurredAtLocal: occurredAt, detailsText, reason},
    )
    if ('error' in result) {
      setDetailsError(result.error)
      return
    }
    setDetailsError(null)
    mutation.mutate(result.payload)
  }

  return (
    <Dialog open onOpenChange={(open) => !open && onClose()}>
      <DialogContent className="max-h-[85vh] overflow-y-auto">
        <DialogHeader>
          <DialogTitle>Edit event</DialogTitle>
          <DialogDescription>
            Corrections are versioned — the original values are preserved in the revision history.
          </DialogDescription>
        </DialogHeader>
        <div className="grid gap-3 py-2">
          <div className="grid gap-1.5">
            <Label htmlFor="edit-event-type">Event type</Label>
            <Input
              id="edit-event-type"
              value={eventType}
              onChange={(e) => setEventType(e.target.value.toUpperCase())}
            />
          </div>
          <div className="grid gap-3 sm:grid-cols-2">
            <div className="grid gap-1.5">
              <Label htmlFor="edit-visibility">Visibility</Label>
              <Select
                value={visibility}
                onValueChange={(value) => setVisibility(value as IncidentTimelineVisibility)}
              >
                <SelectTrigger id="edit-visibility">
                  <SelectValue />
                </SelectTrigger>
                <SelectContent>
                  {TIMELINE_VISIBILITIES.map((value) => (
                    <SelectItem key={value} value={value}>
                      {TIMELINE_VISIBILITY_META[value].label}
                    </SelectItem>
                  ))}
                </SelectContent>
              </Select>
            </div>
            <div className="grid gap-1.5">
              <Label htmlFor="edit-occurred">Occurred at</Label>
              <Input
                id="edit-occurred"
                type="datetime-local"
                step="1"
                value={occurredAt}
                onChange={(e) => setOccurredAt(e.target.value)}
              />
            </div>
          </div>
          <div className="grid gap-1.5">
            <Label htmlFor="edit-details">Details (JSON)</Label>
            <Textarea
              id="edit-details"
              value={detailsText}
              onChange={(e) => setDetailsText(e.target.value)}
              rows={4}
              className="font-mono text-xs"
            />
            {detailsError && <p className="text-xs text-danger-fg">{detailsError}</p>}
          </div>
          <div className="grid gap-1.5">
            <Label htmlFor="edit-reason">Reason (optional)</Label>
            <Input
              id="edit-reason"
              value={reason}
              onChange={(e) => setReason(e.target.value)}
              placeholder="Why the correction was made"
            />
          </div>
        </div>
        <DialogFooter>
          <Button variant="outline" onClick={onClose} disabled={mutation.isPending}>
            Cancel
          </Button>
          <Button onClick={submit} disabled={mutation.isPending}>
            {mutation.isPending ? 'Saving…' : 'Save changes'}
          </Button>
        </DialogFooter>
      </DialogContent>
    </Dialog>
  )
}

interface RemoveDialogProps {
  restore: boolean
  isPending: boolean
  onClose: () => void
  onConfirm: (reason?: string) => void
}

function RemoveDialog({restore, isPending, onClose, onConfirm}: Readonly<RemoveDialogProps>) {
  const [reason, setReason] = useState('')
  const actionLabel = restore ? 'Restore' : 'Remove'
  const submitLabel = isPending ? 'Working…' : actionLabel
  return (
    <Dialog open onOpenChange={(open) => !open && onClose()}>
      <DialogContent>
        <DialogHeader>
          <DialogTitle>{restore ? 'Restore event' : 'Remove event'}</DialogTitle>
          <DialogDescription>
            {restore
              ? 'Bring this event back into the active timeline.'
              : 'This soft-deletes the event. The original evidence is preserved and can be restored.'}
          </DialogDescription>
        </DialogHeader>
        <div className="grid gap-1.5 py-2">
          <Label htmlFor="remove-reason">Reason (optional)</Label>
          <Input
            id="remove-reason"
            value={reason}
            onChange={(e) => setReason(e.target.value)}
            placeholder="Recorded in the revision history"
          />
        </div>
        <DialogFooter>
          <Button variant="outline" onClick={onClose} disabled={isPending}>
            Cancel
          </Button>
          <Button
            variant={restore ? 'default' : 'destructive'}
            onClick={() => onConfirm(reason.trim() || undefined)}
            disabled={isPending}
          >
            {submitLabel}
          </Button>
        </DialogFooter>
      </DialogContent>
    </Dialog>
  )
}

interface RevisionsDialogProps {
  incidentId: string
  entry: IncidentTimelineEntry
  memberName: (userId?: string) => string | null
  onClose: () => void
}

function RevisionsDialog({incidentId, entry, memberName, onClose}: Readonly<RevisionsDialogProps>) {
  const {data, isLoading} = useQuery({
    queryKey: ['incident-timeline-revisions', incidentId, entry.id],
    queryFn: () => api.getIncidentTimelineRevisions(incidentId, entry.id),
  })

  return (
    <Dialog open onOpenChange={(open) => !open && onClose()}>
      <DialogContent className="max-h-[85vh] overflow-y-auto">
        <DialogHeader>
          <DialogTitle>Revision history</DialogTitle>
          <DialogDescription>
            Every change to this event is retained, preserving the original evidence.
          </DialogDescription>
        </DialogHeader>
        <div className="space-y-2 py-2">
          {isLoading && <p className="text-xs text-muted-foreground">Loading revisions…</p>}
          {!isLoading && (data?.length ?? 0) === 0 && (
            <p className="text-xs text-muted-foreground">No revisions recorded yet.</p>
          )}
          {data?.map((revision) => (
            <div key={revision.id} className="rounded-lg border p-3">
              <div className="flex items-center justify-between gap-2">
                <div className="flex items-center gap-2">
                  <Badge variant="neutral" size="sm">
                    #{revision.revision}
                  </Badge>
                  <span className="text-sm font-medium">{humanizeKey(revision.action)}</span>
                </div>
                <span className="text-[11px] text-muted-foreground">
                  {formatDateTime(revision.createdAt)}
                </span>
              </div>
              <p className="mt-1 text-[11px] text-muted-foreground">
                by {memberName(revision.editedByUserId) ?? 'a responder'}
                {revision.reason ? ` · ${revision.reason}` : ''}
              </p>
              <div className="mt-2 grid gap-2 sm:grid-cols-2">
                <RevisionSnapshot title="Before" snapshot={revision.previous} />
                <RevisionSnapshot title="After" snapshot={revision.next} />
              </div>
            </div>
          ))}
        </div>
        <DialogFooter>
          <Button variant="outline" onClick={onClose}>
            Close
          </Button>
        </DialogFooter>
      </DialogContent>
    </Dialog>
  )
}

function RevisionSnapshot({
  title,
  snapshot,
}: Readonly<{title: string; snapshot: Record<string, IncidentFieldValue>}>) {
  return (
    <div>
      <p className="mb-1 text-[11px] font-medium uppercase tracking-wider text-muted-foreground">{title}</p>
      <pre className="overflow-x-auto rounded bg-muted/60 p-2 text-[11px] leading-relaxed">
        {JSON.stringify(snapshot, null, 2)}
      </pre>
    </div>
  )
}
