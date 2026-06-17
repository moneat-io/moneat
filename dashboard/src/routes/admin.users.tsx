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

import {createFileRoute} from '@tanstack/react-router'
import {useQuery, useMutation, useQueryClient} from '@tanstack/react-query'
import {api} from '@/lib/api'
import {Card, CardContent, CardDescription, CardHeader, CardTitle} from '@/components/ui/card'
import {Badge} from '@/components/ui/badge'
import {Button} from '@/components/ui/button'
import {Input} from '@/components/ui/input'
import {
  Table,
  TableBody,
  TableCell,
  TableHead,
  TableHeader,
  TableRow,
} from '@/components/ui/table'
import {
  Dialog,
  DialogContent,
  DialogDescription,
  DialogFooter,
  DialogHeader,
  DialogTitle,
} from '@/components/ui/dialog'
import {
  AlertDialog,
  AlertDialogAction,
  AlertDialogCancel,
  AlertDialogContent,
  AlertDialogDescription,
  AlertDialogFooter,
  AlertDialogHeader,
  AlertDialogTitle,
} from '@/components/ui/alert-dialog'
import {Checkbox} from '@/components/ui/checkbox'
import {Label} from '@/components/ui/label'
import {Switch} from '@/components/ui/switch'
import {SectionHeader} from '@/components/AdminComponents'
import {ChevronLeft, ChevronRight, Search, Shield, User, Mail, CheckCircle, XCircle, Trash2} from 'lucide-react'
import {useState} from 'react'

export const Route = createFileRoute('/admin/users')({
  component: AdminUsersPage,
})

function AdminUsersPage() {
  const [page, setPage] = useState(1)
  const [search, setSearch] = useState('')
  const [searchInput, setSearchInput] = useState('')
  const [selectedUsers, setSelectedUsers] = useState<Set<string>>(new Set())
  const [showDeleteConfirm, setShowDeleteConfirm] = useState(false)
  const [editUser, setEditUser] = useState<{
    id: string
    email: string
    isAdmin: boolean
    emailVerified: boolean
  } | null>(null)

  const limit = 25

  const {data, isLoading} = useQuery({
    queryKey: ['admin-users', page, limit, search],
    queryFn: () => api.getAdminUsers(page, limit, search || undefined),
  })

  const queryClient = useQueryClient()

  const updateMutation = useMutation({
    mutationFn: ({userId, updates}: {userId: string; updates: {isAdmin?: boolean; emailVerified?: boolean}}) =>
      api.updateAdminUser(userId, updates),
    onSuccess: () => {
      queryClient.invalidateQueries({queryKey: ['admin-users']})
      setEditUser(null)
    },
  })

  const deleteMutation = useMutation({
    mutationFn: (userIds: string[]) => api.deleteAdminUsers(userIds),
    onSuccess: (result) => {
      queryClient.invalidateQueries({queryKey: ['admin-users']})
      setSelectedUsers(new Set())
      setShowDeleteConfirm(false)
      if (result.errors.length > 0) {
        console.error('Delete errors:', result.errors)
      }
    },
  })

  const handleSearch = () => {
    setSearch(searchInput)
    setPage(1)
  }

  const handleClearSearch = () => {
    setSearchInput('')
    setSearch('')
    setPage(1)
  }

  const handleSaveUser = () => {
    if (!editUser) return
    updateMutation.mutate({
      userId: editUser.id,
      updates: {
        isAdmin: editUser.isAdmin,
        emailVerified: editUser.emailVerified,
      },
    })
  }

  const handleDeleteUsers = () => {
    if (selectedUsers.size === 0) return
    deleteMutation.mutate(Array.from(selectedUsers))
  }

  const handleToggleUser = (userId: string) => {
    const newSelection = new Set(selectedUsers)
    if (newSelection.has(userId)) {
      newSelection.delete(userId)
    } else {
      newSelection.add(userId)
    }
    setSelectedUsers(newSelection)
  }

  const handleToggleAll = () => {
    if (!data) return
    if (selectedUsers.size === data.users.length) {
      setSelectedUsers(new Set())
    } else {
      setSelectedUsers(new Set(data.users.map(u => u.id)))
    }
  }

  const totalPages = data ? Math.ceil(data.total / limit) : 0

  return (
    <div className="space-y-6">
      <SectionHeader title="User Management" description="View and manage all registered users">
        {selectedUsers.size > 0 && (
          <Button
            variant="destructive"
            onClick={() => setShowDeleteConfirm(true)}
            className="gap-2"
          >
            <Trash2 className="h-4 w-4" />
            Delete {selectedUsers.size} {selectedUsers.size === 1 ? 'User' : 'Users'}
          </Button>
        )}
      </SectionHeader>

      <Card>
        <CardHeader>
          <CardTitle className="text-base">Users</CardTitle>
          <CardDescription>
            {data ? `${data.total} total user${data.total !== 1 ? 's' : ''}` : 'Loading...'}
          </CardDescription>
        </CardHeader>
        <CardContent>
          {/* Search Bar */}
          <div className="flex gap-2 mb-4">
            <div className="relative flex-1">
              <Search className="absolute left-3 top-1/2 -translate-y-1/2 h-4 w-4 text-muted-foreground" />
              <Input
                placeholder="Search by email or name..."
                value={searchInput}
                onChange={(e) => setSearchInput(e.target.value)}
                onKeyDown={(e) => e.key === 'Enter' && handleSearch()}
                className="pl-9"
              />
            </div>
            <Button onClick={handleSearch} variant="secondary">
              Search
            </Button>
            {search && (
              <Button onClick={handleClearSearch} variant="outline">
                Clear
              </Button>
            )}
          </div>

          {/* Users Table */}
          {isLoading ? (
            <div className="text-center py-8 text-muted-foreground">Loading users...</div>
          ) : data && data.users.length > 0 ? (
            <>
              <div className="rounded-md border">
                <Table>
                  <TableHeader>
                    <TableRow>
                      <TableHead className="w-12">
                        <Checkbox
                          checked={data.users.length > 0 && selectedUsers.size === data.users.length}
                          onCheckedChange={handleToggleAll}
                        />
                      </TableHead>
                      <TableHead>User</TableHead>
                      <TableHead>Status</TableHead>
                      <TableHead>Organizations</TableHead>
                      <TableHead>OAuth</TableHead>
                      <TableHead className="text-right">Actions</TableHead>
                    </TableRow>
                  </TableHeader>
                  <TableBody>
                    {data.users.map((user) => (
                      <TableRow key={user.id}>
                        <TableCell>
                          <Checkbox
                            checked={selectedUsers.has(user.id)}
                            onCheckedChange={() => handleToggleUser(user.id)}
                          />
                        </TableCell>
                        <TableCell>
                          <div className="flex items-center gap-3">
                            <div className="flex items-center justify-center h-9 w-9 rounded-full bg-muted">
                              <User className="h-4 w-4 text-muted-foreground" />
                            </div>
                            <div>
                              <div className="flex items-center gap-2">
                                <span className="font-medium">{user.name || 'No name'}</span>
                                {user.isAdmin && (
                                  <Badge variant="default" className="h-5 gap-1 px-1.5">
                                    <Shield className="h-3 w-3" />
                                    Admin
                                  </Badge>
                                )}
                              </div>
                              <div className="text-xs text-muted-foreground">{user.email}</div>
                            </div>
                          </div>
                        </TableCell>
                        <TableCell>
                          <div className="flex flex-col gap-1">
                            <div className="flex items-center gap-1.5">
                              {user.emailVerified ? (
                                <CheckCircle className="h-3.5 w-3.5 text-success-fg" />
                              ) : (
                                <XCircle className="h-3.5 w-3.5 text-warning-fg" />
                              )}
                              <span className="text-sm">
                                {user.emailVerified ? 'Verified' : 'Unverified'}
                              </span>
                            </div>
                            {user.onboardingCompleted ? (
                              <Badge variant="outline" className="w-fit text-xs">
                                Onboarded
                              </Badge>
                            ) : (
                              <Badge variant="secondary" className="w-fit text-xs">
                                Pending
                              </Badge>
                            )}
                          </div>
                        </TableCell>
                        <TableCell>
                          <span className="tabular-nums">{user.organizationCount}</span>
                        </TableCell>
                        <TableCell>
                          {user.oauthProvider ? (
                            <Badge variant="outline" className="capitalize">
                              {user.oauthProvider}
                            </Badge>
                          ) : (
                            <span className="text-sm text-muted-foreground">Email</span>
                          )}
                        </TableCell>
                        <TableCell className="text-right">
                          <Button
                            size="sm"
                            variant="outline"
                            onClick={() =>
                              setEditUser({
                                id: user.id,
                                email: user.email,
                                isAdmin: user.isAdmin,
                                emailVerified: user.emailVerified,
                              })
                            }
                          >
                            Edit
                          </Button>
                        </TableCell>
                      </TableRow>
                    ))}
                  </TableBody>
                </Table>
              </div>

              {/* Pagination */}
              {totalPages > 1 && (
                <div className="flex items-center justify-between mt-4">
                  <p className="text-sm text-muted-foreground">
                    Showing {(page - 1) * limit + 1} to {Math.min(page * limit, data.total)} of {data.total}
                  </p>
                  <div className="flex gap-2">
                    <Button
                      variant="outline"
                      size="sm"
                      onClick={() => setPage((p) => Math.max(1, p - 1))}
                      disabled={page === 1}
                    >
                      <ChevronLeft className="h-4 w-4" />
                      Previous
                    </Button>
                    <div className="flex items-center gap-1">
                      <span className="text-sm px-2">
                        Page {page} of {totalPages}
                      </span>
                    </div>
                    <Button
                      variant="outline"
                      size="sm"
                      onClick={() => setPage((p) => Math.min(totalPages, p + 1))}
                      disabled={page >= totalPages}
                    >
                      Next
                      <ChevronRight className="h-4 w-4" />
                    </Button>
                  </div>
                </div>
              )}
            </>
          ) : (
            <div className="text-center py-8 text-muted-foreground">
              {search ? 'No users found matching your search' : 'No users found'}
            </div>
          )}
        </CardContent>
      </Card>

      {/* Edit User Dialog */}
      <Dialog open={!!editUser} onOpenChange={(open) => !open && setEditUser(null)}>
        <DialogContent>
          <DialogHeader>
            <DialogTitle>Edit User</DialogTitle>
            <DialogDescription>Update user permissions and settings</DialogDescription>
          </DialogHeader>

          {editUser && (
            <div className="space-y-4 py-4">
              <div className="space-y-2">
                <Label className="text-sm font-medium">Email</Label>
                <div className="flex items-center gap-2 text-sm text-muted-foreground">
                  <Mail className="h-4 w-4" />
                  {editUser.email}
                </div>
              </div>

              <div className="flex items-center justify-between space-x-2">
                <div className="space-y-0.5">
                  <Label htmlFor="admin-toggle" className="text-sm font-medium">
                    Admin Access
                  </Label>
                  <p className="text-sm text-muted-foreground">Grant full admin privileges</p>
                </div>
                <Switch
                  id="admin-toggle"
                  checked={editUser.isAdmin}
                  onCheckedChange={(checked) => setEditUser({...editUser, isAdmin: checked})}
                />
              </div>

              <div className="flex items-center justify-between space-x-2">
                <div className="space-y-0.5">
                  <Label htmlFor="verified-toggle" className="text-sm font-medium">
                    Email Verified
                  </Label>
                  <p className="text-sm text-muted-foreground">Mark email as verified</p>
                </div>
                <Switch
                  id="verified-toggle"
                  checked={editUser.emailVerified}
                  onCheckedChange={(checked) => setEditUser({...editUser, emailVerified: checked})}
                />
              </div>
            </div>
          )}

          <DialogFooter>
            <Button variant="outline" onClick={() => setEditUser(null)}>
              Cancel
            </Button>
            <Button onClick={handleSaveUser} disabled={updateMutation.isPending}>
              {updateMutation.isPending ? 'Saving...' : 'Save Changes'}
            </Button>
          </DialogFooter>
        </DialogContent>
      </Dialog>

      {/* Delete Confirmation Dialog */}
      <AlertDialog open={showDeleteConfirm} onOpenChange={setShowDeleteConfirm}>
        <AlertDialogContent>
          <AlertDialogHeader>
            <AlertDialogTitle>Delete {selectedUsers.size} {selectedUsers.size === 1 ? 'User' : 'Users'}?</AlertDialogTitle>
            <AlertDialogDescription>
              This action cannot be undone. This will permanently delete the selected {selectedUsers.size === 1 ? 'user' : 'users'} and all associated data including:
              <ul className="list-disc list-inside mt-2 space-y-1">
                <li>Organizations (if they are the sole member)</li>
                <li>Services and service keys</li>
                <li>Auth tokens and sessions</li>
                <li>Notification preferences</li>
                <li>All related events and data</li>
              </ul>
            </AlertDialogDescription>
          </AlertDialogHeader>
          <AlertDialogFooter>
            <AlertDialogCancel>Cancel</AlertDialogCancel>
            <AlertDialogAction
              onClick={handleDeleteUsers}
              disabled={deleteMutation.isPending}
              className="bg-destructive text-destructive-foreground hover:bg-destructive/90"
            >
              {deleteMutation.isPending ? 'Deleting...' : 'Delete'}
            </AlertDialogAction>
          </AlertDialogFooter>
        </AlertDialogContent>
      </AlertDialog>
    </div>
  )
}
