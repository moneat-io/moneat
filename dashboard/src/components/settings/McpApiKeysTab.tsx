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

import {type FormEvent, useMemo, useState} from 'react'
import {useMutation, useQuery, useQueryClient} from '@tanstack/react-query'
import {Bot, ChevronRight, Copy, Edit3, KeyRound, Plus, ShieldCheck, Trash2} from 'lucide-react'
import {api, type CreateMcpApiKeyResponse, type McpApiKey, type McpToolCatalog} from '@/lib/api'
import {backendBaseUrl} from '@/lib/backend-url'
import {Button} from '@/components/ui/button'
import {Input} from '@/components/ui/input'
import {Label} from '@/components/ui/label'
import {Checkbox} from '@/components/ui/checkbox'
import {Badge} from '@/components/ui/badge'
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
import {Tooltip, TooltipContent, TooltipProvider, TooltipTrigger} from '@/components/ui/tooltip'
import {useToast} from '@/hooks/useToast'
import {useTimezone} from '@/hooks/useTimezone'
import {formatDate as formatDateUtil} from '@/lib/date-format'

function formatDate(iso: string | null | undefined, timezone: string): string {
  if (!iso) return '-'
  try {
    return formatDateUtil(new Date(iso), timezone)
  } catch {
    return '-'
  }
}

function readOnlyToolNames(catalog: McpToolCatalog): string[] {
  return catalog.sections.flatMap((section) =>
    section.tools.filter((tool) => tool.readOnly).map((tool) => tool.name)
  )
}

function allResourceUris(catalog: McpToolCatalog): string[] {
  return catalog.resources.map((resource) => resource.uri)
}

function allToolNames(catalog: McpToolCatalog): string[] {
  return catalog.sections.flatMap((section) => section.tools.map((tool) => tool.name))
}

function toolCounts(catalog: McpToolCatalog | undefined, selectedTools: string[]) {
  if (!catalog) return {read: 0, write: 0}
  const selected = new Set(selectedTools)
  return catalog.sections
    .flatMap((section) => section.tools)
    .filter((tool) => selected.has(tool.name))
    .reduce(
      (counts, tool) => ({
        read: counts.read + (tool.readOnly ? 1 : 0),
        write: counts.write + (tool.readOnly ? 0 : 1),
      }),
      {read: 0, write: 0}
    )
}

export function McpApiKeysTab() {
  const queryClient = useQueryClient()
  const {toast} = useToast()
  const {timezone} = useTimezone()
  const [createOpen, setCreateOpen] = useState(false)
  const [editKey, setEditKey] = useState<McpApiKey | null>(null)
  const [revokeKey, setRevokeKey] = useState<McpApiKey | null>(null)
  const [createdKey, setCreatedKey] = useState<CreateMcpApiKeyResponse | null>(null)

  const {data: catalog, isLoading: catalogLoading} = useQuery({
    queryKey: ['mcpToolCatalog'],
    queryFn: () => api.getMcpToolCatalog(),
    enabled: api.isAuthenticated(),
  })

  const {data: keysData, isLoading: keysLoading} = useQuery({
    queryKey: ['mcpApiKeys'],
    queryFn: () => api.getMcpApiKeys(),
    enabled: api.isAuthenticated(),
  })

  const createMutation = useMutation({
    mutationFn: api.createMcpApiKey,
    onSuccess: (data) => {
      queryClient.invalidateQueries({queryKey: ['mcpApiKeys']})
      setCreateOpen(false)
      setCreatedKey(data)
      toast({title: 'MCP key created', description: "Copy the key now. It won't be shown again."})
    },
    onError: (err: Error) => {
      toast({title: 'Failed to create MCP key', description: err.message, variant: 'destructive'})
    },
  })

  const updateMutation = useMutation({
    mutationFn: ({id, request}: {id: string; request: Parameters<typeof api.updateMcpApiKey>[1]}) =>
      api.updateMcpApiKey(id, request),
    onSuccess: () => {
      queryClient.invalidateQueries({queryKey: ['mcpApiKeys']})
      setEditKey(null)
      toast({title: 'MCP key updated', description: 'Tool access has been updated.'})
    },
    onError: (err: Error) => {
      toast({title: 'Failed to update MCP key', description: err.message, variant: 'destructive'})
    },
  })

  const deleteMutation = useMutation({
    mutationFn: api.deleteMcpApiKey,
    onSuccess: () => {
      queryClient.invalidateQueries({queryKey: ['mcpApiKeys']})
      setRevokeKey(null)
      toast({title: 'MCP key revoked', description: 'The MCP key can no longer connect.'})
    },
    onError: (err: Error) => {
      toast({title: 'Failed to revoke MCP key', description: err.message, variant: 'destructive'})
    },
  })

  const keys = keysData?.keys ?? []
  const loading = catalogLoading || keysLoading

  return (
    <>
      <Card id="mcp-api-keys">
        <CardHeader className="flex flex-row items-start justify-between gap-4 space-y-0">
          <div>
            <CardTitle className="flex items-center gap-2">
              <Bot className="h-5 w-5" />
              MCP Keys
            </CardTitle>
            <CardDescription>
              Create dedicated MCP keys and choose which tools AI clients can use.
            </CardDescription>
          </div>
          <TooltipProvider>
            <Tooltip>
              <TooltipTrigger asChild>
                <Button
                  size="icon"
                  onClick={() => setCreateOpen(true)}
                  disabled={!catalog || !!createdKey}
                  aria-label="New Key"
                  title="New MCP key"
                >
                  <Plus className="h-4 w-4" />
                  <span className="sr-only">New Key</span>
                </Button>
              </TooltipTrigger>
              <TooltipContent>New key</TooltipContent>
            </Tooltip>
          </TooltipProvider>
        </CardHeader>
        <CardContent>
          {loading ? (
            <p className="text-muted-foreground text-sm py-8">Loading MCP keys...</p>
          ) : keys.length === 0 ? (
            <div className="border rounded-lg p-8 text-center text-muted-foreground">
              <Bot className="h-10 w-10 mx-auto mb-2 opacity-50" />
              <p className="font-medium">No MCP keys yet</p>
              <p className="text-sm mt-1">Create a dedicated key for Cursor, Claude, or another MCP client.</p>
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
                  <TableHead>Tools</TableHead>
                  <TableHead>Last Used</TableHead>
                  <TableHead>Created</TableHead>
                  <TableHead className="w-[96px]"></TableHead>
                </TableRow>
              </TableHeader>
              <TableBody>
                {keys.map((key) => {
                  const counts = toolCounts(catalog, key.enabledTools)
                  return (
                    <TableRow key={key.id}>
                      <TableCell className="font-medium">{key.name}</TableCell>
                      <TableCell className="font-mono text-sm text-muted-foreground">
                        {key.keyPrefix}...
                      </TableCell>
                      <TableCell>
                        <div className="flex flex-wrap gap-1">
                          <Badge variant="secondary">{counts.read} read</Badge>
                          {counts.write > 0 && <Badge variant="outline">{counts.write} write</Badge>}
                        </div>
                      </TableCell>
                      <TableCell className="text-muted-foreground text-sm">
                        {formatDate(key.lastUsedAt, timezone)}
                      </TableCell>
                      <TableCell className="text-muted-foreground text-sm">
                        {formatDate(key.createdAt, timezone)}
                      </TableCell>
                      <TableCell>
                        <div className="flex justify-end gap-1">
                          <Button
                            variant="ghost"
                            size="icon"
                            onClick={() => setEditKey(key)}
                            aria-label={`Edit MCP key ${key.name}`}
                          >
                            <Edit3 className="h-4 w-4" />
                          </Button>
                          <Button
                            variant="ghost"
                            size="icon"
                            className="text-destructive hover:text-destructive hover:bg-destructive/10"
                            onClick={() => setRevokeKey(key)}
                            aria-label={`Revoke MCP key ${key.name}`}
                          >
                            <Trash2 className="h-4 w-4" />
                          </Button>
                        </div>
                      </TableCell>
                    </TableRow>
                  )
                })}
              </TableBody>
            </Table>
          )}
        </CardContent>
      </Card>

      <Card>
        <CardHeader>
          <CardTitle className="text-base">MCP Server Configuration</CardTitle>
          <CardDescription>Use a dedicated MCP key in your IDE or agent configuration.</CardDescription>
        </CardHeader>
        <CardContent>
          <pre className="text-xs bg-muted px-3 py-2 rounded-md break-all whitespace-pre-wrap font-mono">
            {`{
  "mcpServers": {
    "moneat": {
      "url": "${backendBaseUrl}/v1/mcp",
      "headers": {
        "Authorization": "Bearer <YOUR_MCP_KEY>"
      }
    }
  }
}`}
          </pre>
        </CardContent>
      </Card>

      {catalog && createOpen && (
        <McpKeyDialog
          open
          title="Create MCP key"
          description="Select the tools this MCP client can use."
          catalog={catalog}
          pending={createMutation.isPending}
          onClose={() => setCreateOpen(false)}
          onSubmit={(request) => createMutation.mutate(request)}
        />
      )}
      {catalog && editKey && (
        <McpKeyDialog
          open
          title="Edit MCP key"
          description="Changes apply the next time the MCP client connects."
          catalog={catalog}
          existingKey={editKey}
          pending={updateMutation.isPending}
          onClose={() => setEditKey(null)}
          onSubmit={(request) => {
            if (editKey) updateMutation.mutate({id: editKey.id, request})
          }}
        />
      )}

      <CreatedMcpKeyDialog
        createdKey={createdKey}
        onClose={() => setCreatedKey(null)}
        onCopy={async (value) => {
          try {
            await navigator.clipboard.writeText(value)
            toast({title: 'Copied', description: 'MCP key copied to clipboard.'})
          } catch {
            toast({
              title: 'Copy failed',
              description: 'Could not copy to clipboard. Please copy it manually.',
              variant: 'destructive',
            })
          }
        }}
      />

      <RevokeMcpKeyDialog
        keyToRevoke={revokeKey}
        isRevoking={deleteMutation.isPending}
        onClose={() => setRevokeKey(null)}
        onConfirm={() => revokeKey && deleteMutation.mutate(revokeKey.id)}
      />
    </>
  )
}

interface McpKeyDialogProps {
  open: boolean
  title: string
  description: string
  catalog: McpToolCatalog
  existingKey?: McpApiKey | null
  pending: boolean
  onClose: () => void
  onSubmit: (request: {name: string; enabledTools: string[]; enabledResources: string[]}) => void
}

function McpKeyDialog({
  open,
  title,
  description,
  catalog,
  existingKey,
  pending,
  onClose,
  onSubmit,
}: McpKeyDialogProps) {
  const defaultTools = useMemo(() => readOnlyToolNames(catalog), [catalog])
  const defaultResources = useMemo(() => allResourceUris(catalog), [catalog])
  const effectiveResources = useMemo(
    () => existingKey?.enabledResources ?? defaultResources,
    [existingKey?.enabledResources, defaultResources]
  )
  const everyTool = useMemo(() => allToolNames(catalog), [catalog])
  const [name, setName] = useState(existingKey?.name ?? '')
  const [selectedTools, setSelectedTools] = useState<Set<string>>(
    () => new Set(existingKey?.enabledTools ?? defaultTools)
  )
  const [expandedSections, setExpandedSections] = useState<Set<string>>(() => new Set())

  const toggleTool = (toolName: string) => {
    setSelectedTools((prev) => {
      const next = new Set(prev)
      if (next.has(toolName)) next.delete(toolName)
      else next.add(toolName)
      return next
    })
  }

  const toggleSectionExpanded = (sectionId: string) => {
    setExpandedSections((prev) => {
      const next = new Set(prev)
      if (next.has(sectionId)) next.delete(sectionId)
      else next.add(sectionId)
      return next
    })
  }

  const setSectionTools = (toolNames: string[], enabled: boolean) => {
    setSelectedTools((prev) => {
      const next = new Set(prev)
      toolNames.forEach((toolName) => {
        if (enabled) next.add(toolName)
        else next.delete(toolName)
      })
      return next
    })
  }

  const selectReadOnlyTools = () => setSelectedTools(new Set(defaultTools))

  const handleSubmit = (event: FormEvent) => {
    event.preventDefault()
    if (!name.trim() || selectedTools.size === 0) return
    onSubmit({
      name: name.trim(),
      enabledTools: Array.from(selectedTools).sort(),
      enabledResources: Array.from(effectiveResources).sort(),
    })
  }

  const selectedWriteCount = catalog.sections
    .flatMap((section) => section.tools)
    .filter((tool) => !tool.readOnly && selectedTools.has(tool.name))
    .length

  const selectedReadCount = selectedTools.size - selectedWriteCount
  const hasAllToolsSelected = selectedTools.size === everyTool.length
  const hasOnlyReadOnlyDefaults =
    selectedTools.size === defaultTools.length &&
    defaultTools.every((toolName) => selectedTools.has(toolName))

  return (
    <Dialog open={open} onOpenChange={(nextOpen) => !nextOpen && onClose()}>
      <DialogContent className="sm:max-w-5xl">
        <DialogHeader>
          <DialogTitle>{title}</DialogTitle>
          <DialogDescription>{description}</DialogDescription>
        </DialogHeader>
        <form onSubmit={handleSubmit} className="flex flex-col gap-4">
          <div className="flex flex-col gap-2">
            <Label htmlFor="mcp-key-name">Name</Label>
            <Input
              id="mcp-key-name"
              placeholder="e.g. Cursor production"
              value={name}
              onChange={(event) => setName(event.target.value)}
              required
            />
          </div>

          <div className="flex items-center justify-between gap-3 rounded-md border p-3">
            <div className="flex items-start gap-2">
              <ShieldCheck className="h-5 w-5 mt-0.5 text-muted-foreground" />
              <div>
                <p className="text-sm font-medium">Default is read-only investigation</p>
                <p className="text-xs text-muted-foreground">
                  Write tools stay off unless you explicitly select them.
                </p>
              </div>
            </div>
            <div className="flex shrink-0 flex-wrap justify-end gap-1">
              <Badge variant="secondary">{selectedReadCount} read</Badge>
              <Badge variant={selectedWriteCount > 0 ? 'outline' : 'secondary'}>
                {selectedWriteCount} write
              </Badge>
            </div>
          </div>

          <div className="flex flex-wrap items-center justify-between gap-2 rounded-md border bg-muted/30 p-3">
            <div className="text-sm">
              <span className="font-medium">{selectedTools.size}</span>
              <span className="text-muted-foreground"> of {everyTool.length} tools selected</span>
            </div>
            <div className="flex flex-wrap gap-2">
              <Button
                type="button"
                variant="outline"
                size="sm"
                onClick={() => setSelectedTools(new Set(everyTool))}
                disabled={hasAllToolsSelected}
              >
                Select all
              </Button>
              <Button
                type="button"
                variant="outline"
                size="sm"
                onClick={selectReadOnlyTools}
                disabled={hasOnlyReadOnlyDefaults}
              >
                Read-only
              </Button>
              <Button
                type="button"
                variant="outline"
                size="sm"
                onClick={() => setSelectedTools(new Set())}
                disabled={selectedTools.size === 0}
              >
                Unselect all
              </Button>
            </div>
          </div>

          <div className="overflow-hidden rounded-md border">
            {catalog.sections.map((section) => {
              const sectionToolNames = section.tools.map((tool) => tool.name)
              const selectedCount = section.tools.filter((tool) => selectedTools.has(tool.name)).length
              const allSelected = selectedCount === section.tools.length
              const isExpanded = expandedSections.has(section.id)
              return (
                <section key={section.id} className="border-b last:border-b-0">
                  <div className="flex items-center gap-3 p-3">
                    <button
                      type="button"
                      className="grid h-7 w-7 shrink-0 place-items-center rounded-md text-muted-foreground hover:bg-muted hover:text-foreground focus:outline-none focus:ring-1 focus:ring-ring"
                      aria-label={`${isExpanded ? 'Collapse' : 'Expand'} ${section.label}`}
                      aria-expanded={isExpanded}
                      onClick={() => toggleSectionExpanded(section.id)}
                    >
                      <ChevronRight
                        className={`h-4 w-4 transition-transform ${isExpanded ? 'rotate-90' : ''}`}
                      />
                    </button>
                    <button
                      type="button"
                      className="min-w-0 flex-1 text-left"
                      aria-expanded={isExpanded}
                      onClick={() => toggleSectionExpanded(section.id)}
                    >
                      <span className="flex flex-wrap items-center gap-2">
                        <span className="text-sm font-medium">{section.label}</span>
                        <Badge variant={selectedCount > 0 ? 'secondary' : 'outline'}>
                          {selectedCount}/{section.tools.length}
                        </Badge>
                      </span>
                      <span className="mt-0.5 block text-xs text-muted-foreground">{section.description}</span>
                    </button>
                    <Button
                      type="button"
                      variant="outline"
                      size="sm"
                      onClick={() => setSectionTools(sectionToolNames, !allSelected)}
                    >
                      {allSelected ? 'Clear' : 'Select all'}
                    </Button>
                  </div>
                  {isExpanded && (
                    <>
                      <div className="divide-y border-t sm:hidden">
                        {section.tools.map((tool) => (
                          <label key={tool.name} className="flex cursor-pointer gap-3 p-3">
                            <div className="pt-0.5">
                              <Checkbox
                                checked={selectedTools.has(tool.name)}
                                onCheckedChange={() => toggleTool(tool.name)}
                                aria-label={`Enable ${tool.name}`}
                              />
                            </div>
                            <span className="min-w-0 flex-1">
                              <span className="flex flex-wrap items-center gap-2">
                                <span className="break-all font-mono text-xs">{tool.name}</span>
                                <Badge variant={tool.readOnly ? 'secondary' : 'outline'}>
                                  {tool.readOnly ? 'read' : 'write'}
                                </Badge>
                              </span>
                              <span className="mt-1 block text-xs text-muted-foreground">
                                {tool.description}
                              </span>
                            </span>
                          </label>
                        ))}
                      </div>
                      <div className="hidden sm:block">
                        <table className="w-full caption-bottom border-t text-sm">
                          <TableHeader>
                            <TableRow>
                              <TableHead className="w-10">Use</TableHead>
                              <TableHead className="w-[220px]">Tool</TableHead>
                              <TableHead className="w-20">Access</TableHead>
                              <TableHead>Description</TableHead>
                            </TableRow>
                          </TableHeader>
                          <TableBody>
                            {section.tools.map((tool) => (
                              <TableRow key={tool.name}>
                                <TableCell>
                                  <Checkbox
                                    checked={selectedTools.has(tool.name)}
                                    onCheckedChange={() => toggleTool(tool.name)}
                                    aria-label={`Enable ${tool.name}`}
                                  />
                                </TableCell>
                                <TableCell className="font-mono text-xs">{tool.name}</TableCell>
                                <TableCell>
                                  <Badge variant={tool.readOnly ? 'secondary' : 'outline'}>
                                    {tool.readOnly ? 'read' : 'write'}
                                  </Badge>
                                </TableCell>
                                <TableCell className="text-xs text-muted-foreground">
                                  {tool.description}
                                </TableCell>
                              </TableRow>
                            ))}
                          </TableBody>
                        </table>
                      </div>
                    </>
                  )}
                </section>
              )
            })}
          </div>

          <DialogFooter>
            <Button type="button" variant="outline" onClick={onClose}>
              Cancel
            </Button>
            <Button type="submit" disabled={pending || !name.trim() || selectedTools.size === 0}>
              {pending ? 'Saving...' : 'Save key'}
            </Button>
          </DialogFooter>
        </form>
      </DialogContent>
    </Dialog>
  )
}

function CreatedMcpKeyDialog({
  createdKey,
  onClose,
  onCopy,
}: {
  createdKey: CreateMcpApiKeyResponse | null
  onClose: () => void
  onCopy: (value: string) => Promise<void>
}) {
  return (
    <Dialog open={!!createdKey} onOpenChange={(open) => !open && onClose()}>
      <DialogContent>
        <DialogHeader>
          <DialogTitle>MCP key created</DialogTitle>
          <DialogDescription>Copy the key now. You will not be able to see it again.</DialogDescription>
        </DialogHeader>
        {createdKey && (
          <div className="flex flex-col gap-4">
            <div className="flex items-center gap-2 rounded-md border bg-muted/50 p-3 font-mono text-sm">
              <KeyRound className="h-5 w-5 shrink-0 text-muted-foreground" />
              <span className="text-muted-foreground">Store this key securely.</span>
            </div>
            <div className="flex gap-2">
              <Input readOnly value={createdKey.key} className="font-mono text-sm" />
              <Button
                type="button"
                variant="secondary"
                size="icon"
                onClick={() => onCopy(createdKey.key)}
                aria-label="Copy MCP key"
              >
                <Copy className="h-4 w-4" />
              </Button>
            </div>
            <DialogFooter>
              <Button onClick={onClose}>Done</Button>
            </DialogFooter>
          </div>
        )}
      </DialogContent>
    </Dialog>
  )
}

function RevokeMcpKeyDialog({
  keyToRevoke,
  isRevoking,
  onClose,
  onConfirm,
}: {
  keyToRevoke: McpApiKey | null
  isRevoking: boolean
  onClose: () => void
  onConfirm: () => void
}) {
  return (
    <Dialog open={!!keyToRevoke} onOpenChange={(open) => !open && onClose()}>
      <DialogContent>
        <DialogHeader>
          <DialogTitle>Revoke MCP key</DialogTitle>
          <DialogDescription>
            {keyToRevoke
              ? `Are you sure you want to revoke "${keyToRevoke.name}"? MCP clients using it will disconnect.`
              : ''}
          </DialogDescription>
        </DialogHeader>
        <DialogFooter>
          <Button type="button" variant="outline" onClick={onClose}>
            Cancel
          </Button>
          <Button type="button" variant="destructive" onClick={onConfirm} disabled={isRevoking}>
            {isRevoking ? 'Revoking...' : 'Revoke key'}
          </Button>
        </DialogFooter>
      </DialogContent>
    </Dialog>
  )
}
