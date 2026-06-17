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

import {useMemo, useState} from 'react'
import {useMutation, useQuery, useQueryClient} from '@tanstack/react-query'
import {AlertCircle, Loader2, Pencil, Plus, Search, Trash2, UserPlus, X} from 'lucide-react'
import {api, type OrgMember, type RbacRole} from '@/lib/api'
import {cn} from '@/lib/utils'
import {useToast} from '@/hooks/useToast'
import {Alert, AlertDescription, AlertTitle} from '@/components/ui/alert'
import {Badge} from '@/components/ui/badge'
import {Button} from '@/components/ui/button'
import {Card, CardContent, CardDescription, CardHeader, CardTitle} from '@/components/ui/card'
import {Checkbox} from '@/components/ui/checkbox'
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
import {Select, SelectContent, SelectGroup, SelectItem, SelectTrigger, SelectValue} from '@/components/ui/select'
import {Table, TableBody, TableCell, TableHead, TableHeader, TableRow} from '@/components/ui/table'

type KnownRbacPermission =
  | 'workflows:read'
  | 'workflows:write'
  | 'workflows:run'
  | 'connections:read'
  | 'connections:write'
  | 'connections:resolve'
  | 'logs:read'
  | 'logs:manage'
  | 'logs:live-tail'
  | 'logs:metrics'
  | 'logs:monitors'

type PermissionOption = {
  key: KnownRbacPermission
  label: string
  description: string
}

type PermissionGroup = {
  title: string
  description: string
  permissions: PermissionOption[]
}

type RoleFormValues = {
  name: string
  permissions: string[]
}

const PERMISSION_GROUPS: PermissionGroup[] = [
  {
    title: 'Workflow automations',
    description: 'Controls workflow visibility, editing, and manual execution.',
    permissions: [
      {
        key: 'workflows:read',
        label: 'View workflows',
        description: 'See workflow definitions, runs, previews, and audit entries.',
      },
      {
        key: 'workflows:write',
        label: 'Manage workflows',
        description: 'Create, update, publish, import, export, and delete workflows.',
      },
      {
        key: 'workflows:run',
        label: 'Run workflows',
        description: 'Trigger workflow runs and cancel active workflow instances.',
      },
    ],
  },
  {
    title: 'Workflow connections',
    description: 'Controls the connection vault used by advanced workflow steps.',
    permissions: [
      {
        key: 'connections:read',
        label: 'View connections',
        description: 'List configured workflow connections and metadata.',
      },
      {
        key: 'connections:write',
        label: 'Manage connections',
        description: 'Create, update, and delete workflow connections.',
      },
      {
        key: 'connections:resolve',
        label: 'Resolve secrets',
        description: 'Use stored connection credentials when workflow steps execute.',
      },
    ],
  },
  {
    title: 'Log management',
    description: 'Controls log Explorer management workflows and derived log signals.',
    permissions: [
      {
        key: 'logs:read',
        label: 'View logs',
        description: 'See log data and saved Explorer state.',
      },
      {
        key: 'logs:manage',
        label: 'Manage logs',
        description: 'Create and update log indexes, pipelines, saved views, and retention actions.',
      },
      {
        key: 'logs:live-tail',
        label: 'Live tail logs',
        description: 'Stream matching logs in real time from the Explorer.',
      },
      {
        key: 'logs:metrics',
        label: 'Create log metrics',
        description: 'Create, preview, and roll up metrics derived from log queries.',
      },
      {
        key: 'logs:monitors',
        label: 'Create log monitors',
        description: 'Create monitor drafts and dashboard alerts from log queries.',
      },
    ],
  },
]

const KNOWN_PERMISSION_KEYS: ReadonlySet<string> = new Set<string>(
  PERMISSION_GROUPS.flatMap((group) => group.permissions.map((permission) => permission.key))
)
const PERMISSION_ORDER: ReadonlyMap<string, number> = new Map<string, number>(
  PERMISSION_GROUPS
    .flatMap((group) => group.permissions.map((permission) => permission.key))
    .map((permission, index) => [permission, index])
)
const UUID_PATTERN = /^[0-9a-f]{8}-[0-9a-f]{4}-[1-5][0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/i

function sortPermissions(first: string, second: string): number {
  const firstOrder = PERMISSION_ORDER.get(first) ?? Number.MAX_SAFE_INTEGER
  const secondOrder = PERMISSION_ORDER.get(second) ?? Number.MAX_SAFE_INTEGER
  return firstOrder - secondOrder || first.localeCompare(second)
}

function permissionMatchesQuery(permission: PermissionOption, query: string): boolean {
  return (
    permission.key.toLowerCase().includes(query) ||
    permission.label.toLowerCase().includes(query) ||
    permission.description.toLowerCase().includes(query)
  )
}

function permissionGroupMatchesQuery(group: PermissionGroup, query: string): boolean {
  return group.title.toLowerCase().includes(query) || group.description.toLowerCase().includes(query)
}

function getVisiblePermissionGroups(query: string): PermissionGroup[] {
  if (!query) return PERMISSION_GROUPS

  return PERMISSION_GROUPS
    .map((group) => {
      const permissions = permissionGroupMatchesQuery(group, query)
        ? group.permissions
        : group.permissions.filter((permission) => permissionMatchesQuery(permission, query))

      return {...group, permissions}
    })
    .filter((group) => group.permissions.length > 0)
}

function getPermissionGroupSelectionLabel(group: PermissionGroup, permissionSet: ReadonlySet<string>): string {
  const sourceGroup = PERMISSION_GROUPS.find((permissionGroup) => permissionGroup.title === group.title) ?? group
  const selectedCount = sourceGroup.permissions.filter((permission) => permissionSet.has(permission.key)).length
  return `${selectedCount}/${sourceGroup.permissions.length}`
}

function getErrorMessage(error: unknown): string {
  return error instanceof Error ? error.message : 'Something went wrong.'
}

function formatTimestamp(value: string): string {
  const date = new Date(value)
  if (Number.isNaN(date.getTime())) return value
  return date.toLocaleDateString(undefined, {month: 'short', day: 'numeric', year: 'numeric'})
}

function memberDisplayName(member: OrgMember | undefined, userId: string): string {
  if (member?.name) return member.name
  if (member?.email) return member.email
  return `User ${userId}`
}

function memberSecondaryLabel(member: OrgMember | undefined): string {
  if (!member) return 'Unknown member'
  return member.name ? member.email : member.role
}

function orgRoleVariant(role: string): 'default' | 'secondary' | 'outline' {
  if (role === 'owner') return 'default'
  if (role === 'admin') return 'secondary'
  return 'outline'
}

function isUuid(value: string): boolean {
  return UUID_PATTERN.test(value)
}

function RoleLoadingState() {
  return (
    <Card>
      <CardContent className="flex items-center justify-center gap-3 py-12 text-sm text-muted-foreground">
        <Loader2 className="size-4 animate-spin" />
        Loading RBAC roles
      </CardContent>
    </Card>
  )
}

function EmptyRolesState({onCreate}: Readonly<{onCreate: () => void}>) {
  return (
    <Card>
      <CardHeader>
        <CardTitle>No custom RBAC roles yet</CardTitle>
        <CardDescription>
          Create roles with granular permissions, then assign them to organization members.
        </CardDescription>
      </CardHeader>
      <CardContent>
        <Button onClick={onCreate}>
          <Plus />
          Create role
        </Button>
      </CardContent>
    </Card>
  )
}

function RoleList({
  roles,
  selectedRoleId,
  onSelectRole,
}: Readonly<{
  roles: RbacRole[]
  selectedRoleId: string | null
  onSelectRole: (roleId: string) => void
}>) {
  return (
    <Card>
      <CardHeader>
        <CardTitle>Roles</CardTitle>
        <CardDescription>{roles.length} custom role{roles.length === 1 ? '' : 's'}</CardDescription>
      </CardHeader>
      <CardContent>
        <div className="flex flex-col gap-2">
          {roles.map((role) => {
            const isSelected = role.id === selectedRoleId
            return (
              <button
                key={role.id}
                type="button"
                onClick={() => onSelectRole(role.id)}
                className={cn(
                  'rounded-md border bg-background p-3 text-left transition-colors hover:bg-muted/50',
                  'focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-ring',
                  isSelected && 'border-primary bg-muted'
                )}
              >
                <div className="flex items-center justify-between gap-3">
                  <span className="min-w-0 truncate text-sm font-medium">{role.name}</span>
                  <Badge variant="outline">{role.permissions.length}</Badge>
                </div>
                <p className="mt-1 text-xs text-muted-foreground">
                  Updated {formatTimestamp(role.updated_at)}
                </p>
              </button>
            )
          })}
        </div>
      </CardContent>
    </Card>
  )
}

function PermissionBadges({permissions}: {permissions: string[]}) {
  if (permissions.length === 0) {
    return <p className="text-sm text-muted-foreground">No granular permissions are enabled for this role.</p>
  }

  return (
    <div className="flex flex-wrap gap-2">
      {permissions.toSorted(sortPermissions).map((permission) => (
        <Badge key={permission} variant={KNOWN_PERMISSION_KEYS.has(permission) ? 'secondary' : 'outline'}>
          {permission}
        </Badge>
      ))}
    </div>
  )
}

function PermissionOptionRow({
  permission,
  checked,
  disabled,
  onToggle,
}: {
  permission: PermissionOption
  checked: boolean
  disabled: boolean
  onToggle: (permission: string, checked: boolean) => void
}) {
  return (
    <label
      className={cn(
        'flex cursor-pointer items-start gap-3 rounded-md p-2 transition-colors hover:bg-muted/50',
        disabled && 'cursor-default hover:bg-transparent'
      )}
    >
      <Checkbox
        checked={checked}
        disabled={disabled}
        onCheckedChange={(isChecked) => onToggle(permission.key, isChecked === true)}
        aria-label={permission.label}
        className="mt-0.5"
      />
      <span className="flex min-w-0 flex-col gap-1">
        <span className="text-sm font-medium">{permission.label}</span>
        <span className="break-words font-mono text-xs text-muted-foreground">{permission.key}</span>
        <span className="text-xs text-muted-foreground">{permission.description}</span>
      </span>
    </label>
  )
}

function UnknownPermissionList({
  permissions,
  disabled,
  onToggle,
}: {
  permissions: string[]
  disabled: boolean
  onToggle: (permission: string, checked: boolean) => void
}) {
  if (permissions.length === 0) return null

  return (
    <div className="rounded-md border p-3">
      <div className="mb-3">
        <h4 className="text-sm font-medium">Other permissions</h4>
        <p className="text-xs text-muted-foreground">
          These keys are stored on the role but are not modeled by this UI yet.
        </p>
      </div>
      <div className="flex flex-col gap-2">
        {permissions.map((permission) => (
          <label key={permission} className="flex cursor-pointer items-center gap-3 rounded-md p-2">
            <Checkbox
              checked
              disabled={disabled}
              onCheckedChange={(isChecked) => onToggle(permission, isChecked === true)}
              aria-label={permission}
            />
            <span className="min-w-0 break-words font-mono text-sm">{permission}</span>
          </label>
        ))}
      </div>
    </div>
  )
}

function PermissionChecklist({
  permissions,
  disabled,
  onToggle,
}: {
  permissions: string[]
  disabled: boolean
  onToggle: (permission: string, checked: boolean) => void
}) {
  const [query, setQuery] = useState('')
  const [selectedGroupTitle, setSelectedGroupTitle] = useState(PERMISSION_GROUPS[0]?.title ?? '')
  const normalizedQuery = query.trim().toLowerCase()
  const permissionSet = useMemo(() => new Set(permissions), [permissions])
  const unknownPermissions = useMemo(
    () => permissions.filter((permission) => !KNOWN_PERMISSION_KEYS.has(permission)).toSorted(),
    [permissions]
  )
  const visibleGroups = useMemo(() => getVisiblePermissionGroups(normalizedQuery), [normalizedQuery])
  const activeGroup = visibleGroups.find((group) => group.title === selectedGroupTitle) ?? visibleGroups[0] ?? null

  return (
    <div className="flex flex-col gap-3">
      <div className="rounded-md border">
        <div className="flex flex-col gap-3 border-b p-3 sm:flex-row sm:items-end">
          <div className="flex flex-1 flex-col gap-2">
            <Label htmlFor="rbac-permission-search" className="text-xs font-medium text-muted-foreground">
              Search permissions
            </Label>
            <div className="relative">
              <Search className="pointer-events-none absolute left-3 top-1/2 size-4 -translate-y-1/2 text-muted-foreground" />
              <Input
                id="rbac-permission-search"
                type="search"
                value={query}
                onChange={(event) => setQuery(event.target.value)}
                placeholder="Name, key, or description"
                disabled={disabled}
                className="pl-9"
              />
            </div>
          </div>
          <Badge variant="secondary" className="w-fit">
            {permissions.length} selected
          </Badge>
        </div>

        <div className="grid min-h-[320px] md:grid-cols-[260px_1fr]">
          <div className="border-b bg-muted/30 p-2 md:border-b-0 md:border-r">
            <div className="flex max-h-52 flex-col gap-1 overflow-y-auto md:max-h-[360px]">
              {visibleGroups.map((group) => {
                const isActive = group.title === activeGroup?.title
                return (
                  <button
                    key={group.title}
                    type="button"
                    onClick={() => setSelectedGroupTitle(group.title)}
                    aria-pressed={isActive}
                    className={cn(
                      'flex items-start justify-between gap-3 rounded-md px-3 py-2 text-left text-sm',
                      'transition-colors hover:bg-background',
                      'focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-ring',
                      isActive && 'bg-background shadow-sm'
                    )}
                  >
                    <span className="min-w-0 flex-1">
                      <span className="block font-medium leading-snug">{group.title}</span>
                      <span className="block truncate text-xs text-muted-foreground">
                        {group.permissions.length} visible
                      </span>
                    </span>
                    <Badge variant="outline" className="mt-0.5 shrink-0">
                      {getPermissionGroupSelectionLabel(group, permissionSet)}
                    </Badge>
                  </button>
                )
              })}
            </div>
          </div>

          <div className="max-h-[360px] overflow-y-auto p-3">
            {activeGroup ? (
              <div>
                <div className="mb-3">
                  <h4 className="text-sm font-medium">{activeGroup.title}</h4>
                  <p className="text-xs text-muted-foreground">{activeGroup.description}</p>
                </div>
                <div className="flex flex-col gap-1">
                  {activeGroup.permissions.map((permission) => (
                    <PermissionOptionRow
                      key={permission.key}
                      permission={permission}
                      checked={permissionSet.has(permission.key)}
                      disabled={disabled}
                      onToggle={onToggle}
                    />
                  ))}
                </div>
              </div>
            ) : (
              <div className="flex min-h-48 items-center justify-center rounded-md border border-dashed p-6 text-center">
                <p className="text-sm text-muted-foreground">No permissions match this search.</p>
              </div>
            )}
          </div>
        </div>
      </div>

      <UnknownPermissionList permissions={unknownPermissions} disabled={disabled} onToggle={onToggle} />
    </div>
  )
}

function RbacRoleFormFields({
  initialName,
  initialPermissions,
  isSubmitting,
  onCancel,
  onSubmit,
}: {
  initialName: string
  initialPermissions: string[]
  isSubmitting: boolean
  onCancel: () => void
  onSubmit: (values: RoleFormValues) => void
}) {
  const [name, setName] = useState(initialName)
  const [permissions, setPermissions] = useState<string[]>(initialPermissions.toSorted(sortPermissions))

  const handleToggle = (permission: string, checked: boolean) => {
    setPermissions((current) => {
      const next = new Set(current)
      if (checked) {
        next.add(permission)
      } else {
        next.delete(permission)
      }
      return Array.from(next).sort(sortPermissions)
    })
  }

  const handleSubmit = () => {
    const trimmedName = name.trim()
    if (!trimmedName) return
    onSubmit({name: trimmedName, permissions})
  }

  return (
    <>
      <div className="flex flex-col gap-5">
        <div className="flex flex-col gap-2">
          <Label htmlFor="rbac-role-name">Role name</Label>
          <Input
            id="rbac-role-name"
            value={name}
            onChange={(event) => setName(event.target.value)}
            placeholder="Incident responder"
            disabled={isSubmitting}
          />
        </div>
        <div className="flex flex-col gap-2">
          <h3 className="text-sm font-medium">Permissions</h3>
          <PermissionChecklist permissions={permissions} disabled={isSubmitting} onToggle={handleToggle} />
        </div>
      </div>

      <DialogFooter className="mt-5 border-t pb-4 pt-5">
        <Button type="button" variant="outline" onClick={onCancel} disabled={isSubmitting}>
          Cancel
        </Button>
        <Button type="button" onClick={handleSubmit} disabled={!name.trim() || isSubmitting}>
          {isSubmitting && <Loader2 className="animate-spin" />}
          Save role
        </Button>
      </DialogFooter>
    </>
  )
}

function RbacRoleFormDialog({
  open,
  title,
  description,
  role,
  isSubmitting,
  onOpenChange,
  onSubmit,
}: {
  open: boolean
  title: string
  description: string
  role?: RbacRole | null
  isSubmitting: boolean
  onOpenChange: (open: boolean) => void
  onSubmit: (values: RoleFormValues) => void
}) {
  const formKey = role?.id === undefined ? 'create' : `edit-${role.id}`

  return (
    <Dialog open={open} onOpenChange={onOpenChange}>
      <DialogContent className="max-h-[calc(100vh-3rem)] overflow-y-auto sm:max-w-3xl">
        <DialogHeader>
          <DialogTitle>{title}</DialogTitle>
          <DialogDescription>{description}</DialogDescription>
        </DialogHeader>

        {open && (
          <RbacRoleFormFields
            key={formKey}
            initialName={role?.name ?? ''}
            initialPermissions={role?.permissions ?? []}
            isSubmitting={isSubmitting}
            onCancel={() => onOpenChange(false)}
            onSubmit={onSubmit}
          />
        )}
      </DialogContent>
    </Dialog>
  )
}

function RoleAssignments({
  role,
  members,
}: {
  role: RbacRole
  members: OrgMember[]
}) {
  const queryClient = useQueryClient()
  const {toast} = useToast()
  const [assignmentUserId, setAssignmentUserId] = useState('')
  const assignmentsQuery = useQuery({
    queryKey: ['rbac-role-assignments', role.id],
    queryFn: () => api.getRbacRoleAssignments(role.id),
  })

  const assignedUserIds = useMemo(
    () => new Set((assignmentsQuery.data ?? []).map((assignment) => assignment.user_id)),
    [assignmentsQuery.data]
  )
  const memberById = useMemo(
    () => new Map(members.map((member) => [member.userId, member])),
    [members]
  )
  const availableMembers = useMemo(
    () => members.filter((member) => !assignedUserIds.has(member.userId)),
    [assignedUserIds, members]
  )

  const assignMutation = useMutation({
    mutationFn: (userId: string) => api.assignRbacRole(role.id, userId),
    onSuccess: () => {
      setAssignmentUserId('')
      queryClient.invalidateQueries({queryKey: ['rbac-role-assignments', role.id]})
      toast({title: 'Role assigned'})
    },
    onError: (error) => {
      toast({title: 'Failed to assign role', description: getErrorMessage(error), variant: 'destructive'})
    },
  })

  const unassignMutation = useMutation({
    mutationFn: (userId: string) => api.unassignRbacRole(role.id, userId),
    onSuccess: () => {
      queryClient.invalidateQueries({queryKey: ['rbac-role-assignments', role.id]})
      toast({title: 'Role assignment removed'})
    },
    onError: (error) => {
      toast({title: 'Failed to remove assignment', description: getErrorMessage(error), variant: 'destructive'})
    },
  })

  const handleAssign = () => {
    if (!assignmentUserId) return
    if (!isUuid(assignmentUserId)) {
      toast({title: 'Invalid member ID', variant: 'destructive'})
      return
    }
    assignMutation.mutate(assignmentUserId)
  }

  if (assignmentsQuery.isLoading) {
    return (
      <div className="flex items-center gap-2 py-3 text-sm text-muted-foreground">
        <Loader2 className="size-4 animate-spin" />
        Loading assignments
      </div>
    )
  }

  if (assignmentsQuery.isError) {
    return (
      <Alert variant="destructive">
        <AlertCircle className="size-4" />
        <AlertTitle>Assignments failed to load</AlertTitle>
        <AlertDescription>{getErrorMessage(assignmentsQuery.error)}</AlertDescription>
      </Alert>
    )
  }

  const assignments = assignmentsQuery.data ?? []

  return (
    <div className="flex flex-col gap-4">
      <div className="flex flex-col gap-3 sm:flex-row sm:items-end">
        <div className="flex flex-1 flex-col gap-2 sm:max-w-sm">
          <Label htmlFor="rbac-assignment-member">Assign member</Label>
          <Select
            value={assignmentUserId}
            onValueChange={setAssignmentUserId}
            disabled={availableMembers.length === 0 || assignMutation.isPending}
          >
            <SelectTrigger id="rbac-assignment-member">
              <SelectValue placeholder="Choose a team member" />
            </SelectTrigger>
            <SelectContent>
              <SelectGroup>
                {availableMembers.map((member) => (
                  <SelectItem key={member.userId} value={String(member.userId)}>
                    {member.name ? `${member.name} (${member.email})` : member.email}
                  </SelectItem>
                ))}
              </SelectGroup>
            </SelectContent>
          </Select>
        </div>
        <Button onClick={handleAssign} disabled={!assignmentUserId || assignMutation.isPending}>
          {assignMutation.isPending ? <Loader2 className="animate-spin" /> : <UserPlus />}
          Assign
        </Button>
      </div>

      {availableMembers.length === 0 && members.length > 0 && (
        <p className="text-xs text-muted-foreground">Every current team member already has this role.</p>
      )}

      {assignments.length === 0 ? (
        <div className="rounded-md border p-4 text-sm text-muted-foreground">
          No users are assigned to this role.
        </div>
      ) : (
        <Table>
          <TableHeader>
            <TableRow>
              <TableHead>Member</TableHead>
              <TableHead>Org role</TableHead>
              <TableHead>Assigned</TableHead>
              <TableHead className="text-right">Action</TableHead>
            </TableRow>
          </TableHeader>
          <TableBody>
            {assignments.map((assignment) => {
              const member = memberById.get(assignment.user_id)
              return (
                <TableRow key={assignment.id}>
                  <TableCell>
                    <div className="flex flex-col gap-1">
                      <span className="font-medium">{memberDisplayName(member, assignment.user_id)}</span>
                      <span className="text-xs text-muted-foreground">{memberSecondaryLabel(member)}</span>
                    </div>
                  </TableCell>
                  <TableCell>
                    {member ? (
                      <Badge variant={orgRoleVariant(member.role)}>{member.role}</Badge>
                    ) : (
                      <Badge variant="outline">unknown</Badge>
                    )}
                  </TableCell>
                  <TableCell className="text-muted-foreground">
                    {formatTimestamp(assignment.created_at)}
                  </TableCell>
                  <TableCell className="text-right">
                    <Button
                      variant="ghost"
                      size="sm"
                      onClick={() => unassignMutation.mutate(assignment.user_id)}
                      disabled={unassignMutation.isPending}
                    >
                      <X />
                      Remove
                    </Button>
                  </TableCell>
                </TableRow>
              )
            })}
          </TableBody>
        </Table>
      )}
    </div>
  )
}

function RoleDetails({
  role,
  members,
  onEdit,
  onDelete,
}: {
  role: RbacRole
  members: OrgMember[]
  onEdit: (role: RbacRole) => void
  onDelete: (role: RbacRole) => void
}) {
  return (
    <Card>
      <CardHeader>
        <div className="flex flex-col gap-4 sm:flex-row sm:items-start sm:justify-between">
          <div>
            <CardTitle>{role.name}</CardTitle>
            <CardDescription>Updated {formatTimestamp(role.updated_at)}</CardDescription>
          </div>
          <div className="flex flex-wrap gap-2">
            <Button variant="outline" size="sm" onClick={() => onEdit(role)}>
              <Pencil />
              Edit
            </Button>
            <Button variant="outline" size="sm" onClick={() => onDelete(role)}>
              <Trash2 />
              Delete
            </Button>
          </div>
        </div>
      </CardHeader>
      <CardContent className="flex flex-col gap-6">
        <section className="flex flex-col gap-3">
          <div>
            <h3 className="text-sm font-medium">Permissions</h3>
            <p className="text-sm text-muted-foreground">
              These keys are evaluated by product surfaces that support granular RBAC.
            </p>
          </div>
          <PermissionBadges permissions={role.permissions} />
        </section>

        <section className="flex flex-col gap-3">
          <div>
            <h3 className="text-sm font-medium">Assigned members</h3>
            <p className="text-sm text-muted-foreground">
              Assigned users are governed by the union of their granular RBAC roles.
            </p>
          </div>
          <RoleAssignments role={role} members={members} />
        </section>
      </CardContent>
    </Card>
  )
}

function DeleteRoleDialog({
  role,
  isDeleting,
  onOpenChange,
  onConfirm,
}: {
  role: RbacRole | null
  isDeleting: boolean
  onOpenChange: (open: boolean) => void
  onConfirm: () => void
}) {
  return (
    <Dialog open={role !== null} onOpenChange={onOpenChange}>
      <DialogContent>
        <DialogHeader>
          <DialogTitle>Delete role</DialogTitle>
          <DialogDescription>
            Delete {role?.name}. Existing assignments for this role will be removed.
          </DialogDescription>
        </DialogHeader>
        <DialogFooter className="mt-5 border-t pb-4 pt-5">
          <Button variant="outline" onClick={() => onOpenChange(false)} disabled={isDeleting}>
            Cancel
          </Button>
          <Button variant="destructive" onClick={onConfirm} disabled={isDeleting}>
            {isDeleting && <Loader2 className="animate-spin" />}
            Delete role
          </Button>
        </DialogFooter>
      </DialogContent>
    </Dialog>
  )
}

export function RbacSettings() {
  const queryClient = useQueryClient()
  const {toast} = useToast()
  const [createOpen, setCreateOpen] = useState(false)
  const [roleToEdit, setRoleToEdit] = useState<RbacRole | null>(null)
  const [roleToDelete, setRoleToDelete] = useState<RbacRole | null>(null)
  const [selectedRoleId, setSelectedRoleId] = useState<string | null>(null)

  const rolesQuery = useQuery({
    queryKey: ['rbac-roles'],
    queryFn: () => api.getRbacRoles(),
  })
  const membersQuery = useQuery({
    queryKey: ['org-members'],
    queryFn: () => api.getOrgMembers(),
  })

  const roles = useMemo(
    () => (rolesQuery.data ?? []).toSorted((first, second) => first.name.localeCompare(second.name)),
    [rolesQuery.data]
  )
  const members = membersQuery.data?.members ?? []
  const selectedRole = roles.find((role) => role.id === selectedRoleId) ?? roles[0] ?? null
  const effectiveSelectedRoleId = selectedRole?.id ?? null

  const createMutation = useMutation({
    mutationFn: (values: RoleFormValues) => api.createRbacRole(values),
    onSuccess: (role) => {
      setCreateOpen(false)
      setSelectedRoleId(role.id)
      queryClient.setQueryData<RbacRole[]>(['rbac-roles'], (current = []) => [
        ...current.filter((existingRole) => existingRole.id !== role.id),
        role,
      ])
      queryClient.invalidateQueries({queryKey: ['rbac-roles']})
      toast({title: 'RBAC role created', description: `${role.name} is ready for assignments.`})
    },
    onError: (error) => {
      toast({title: 'Failed to create role', description: getErrorMessage(error), variant: 'destructive'})
    },
  })

  const updateMutation = useMutation({
    mutationFn: ({roleId, values}: {roleId: string; values: RoleFormValues}) =>
      api.updateRbacRole(roleId, values),
    onSuccess: (role) => {
      setRoleToEdit(null)
      setSelectedRoleId(role.id)
      queryClient.setQueryData<RbacRole[]>(['rbac-roles'], (current = []) =>
        current.map((existingRole) => (existingRole.id === role.id ? role : existingRole))
      )
      queryClient.invalidateQueries({queryKey: ['rbac-roles']})
      toast({title: 'RBAC role updated'})
    },
    onError: (error) => {
      toast({title: 'Failed to update role', description: getErrorMessage(error), variant: 'destructive'})
    },
  })

  const deleteMutation = useMutation({
    mutationFn: (roleId: string) => api.deleteRbacRole(roleId),
    onSuccess: () => {
      const deletedRoleId = roleToDelete?.id
      setRoleToDelete(null)
      setSelectedRoleId(null)
      if (deletedRoleId !== undefined) {
        queryClient.setQueryData<RbacRole[]>(['rbac-roles'], (current = []) =>
          current.filter((role) => role.id !== deletedRoleId)
        )
      }
      queryClient.invalidateQueries({queryKey: ['rbac-roles']})
      toast({title: 'RBAC role deleted'})
    },
    onError: (error) => {
      toast({title: 'Failed to delete role', description: getErrorMessage(error), variant: 'destructive'})
    },
  })

  const pageError = rolesQuery.error ?? membersQuery.error
  const isLoading = rolesQuery.isLoading || membersQuery.isLoading

  return (
    <div className="flex flex-col gap-6">
      <div className="flex flex-col gap-3 sm:flex-row sm:items-start sm:justify-between">
        <div>
          <h2 className="text-xl font-semibold">Role-based access control</h2>
          <p className="text-sm text-muted-foreground">
            Define granular roles and assign them to members in this organization.
          </p>
        </div>
        <Button onClick={() => setCreateOpen(true)}>
          <Plus />
          Create role
        </Button>
      </div>

      {pageError && (
        <Alert variant="destructive">
          <AlertCircle className="size-4" />
          <AlertTitle>RBAC failed to load</AlertTitle>
          <AlertDescription>{getErrorMessage(pageError)}</AlertDescription>
        </Alert>
      )}

      {isLoading ? (
        <RoleLoadingState />
      ) : roles.length === 0 ? (
        <EmptyRolesState onCreate={() => setCreateOpen(true)} />
      ) : (
        <div className="grid gap-6 lg:grid-cols-[minmax(260px,360px),1fr]">
          <RoleList roles={roles} selectedRoleId={effectiveSelectedRoleId} onSelectRole={setSelectedRoleId} />
          {selectedRole && (
            <RoleDetails
              role={selectedRole}
              members={members}
              onEdit={setRoleToEdit}
              onDelete={setRoleToDelete}
            />
          )}
        </div>
      )}

      <RbacRoleFormDialog
        open={createOpen}
        title="Create RBAC role"
        description="Choose the granular permissions this role should grant."
        isSubmitting={createMutation.isPending}
        onOpenChange={setCreateOpen}
        onSubmit={(values) => createMutation.mutate(values)}
      />

      <RbacRoleFormDialog
        open={roleToEdit !== null}
        title="Edit RBAC role"
        description="Update the role name and permission keys."
        role={roleToEdit}
        isSubmitting={updateMutation.isPending}
        onOpenChange={(open) => {
          if (!open) setRoleToEdit(null)
        }}
        onSubmit={(values) => {
          if (roleToEdit) updateMutation.mutate({roleId: roleToEdit.id, values})
        }}
      />

      <DeleteRoleDialog
        role={roleToDelete}
        isDeleting={deleteMutation.isPending}
        onOpenChange={(open) => {
          if (!open) setRoleToDelete(null)
        }}
        onConfirm={() => {
          if (roleToDelete) deleteMutation.mutate(roleToDelete.id)
        }}
      />
    </div>
  )
}
