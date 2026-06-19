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
import {Button} from '@/components/ui/button'
import {Input} from '@/components/ui/input'
import {Label} from '@/components/ui/label'
import {Textarea} from '@/components/ui/textarea'
import {Badge} from '@/components/ui/badge'
import {
  Select,
  SelectContent,
  SelectItem,
  SelectTrigger,
  SelectValue,
} from '@/components/ui/select'
import {Users2, X} from 'lucide-react'

export interface TeamFormData {
  name: string
  description: string
  slack: string
  repo: string
  memberIds: string[]
  onCallScheduleId: string | null
  escalationPolicyId: string | null
}

export interface TeamEditorOption {
  id: string
  name: string
}

interface TeamEditorProps {
  readonly initialData?: TeamFormData
  readonly members: readonly TeamEditorOption[]
  readonly schedules: readonly TeamEditorOption[]
  readonly policies: readonly TeamEditorOption[]
  readonly isSaving?: boolean
  readonly onSave: (data: TeamFormData) => void
  readonly onCancel: () => void
}

const NONE_VALUE = '__none__'

function emptyForm(): TeamFormData {
  return {
    name: '',
    description: '',
    slack: '',
    repo: '',
    memberIds: [],
    onCallScheduleId: null,
    escalationPolicyId: null,
  }
}

export function TeamEditor({
  initialData,
  members,
  schedules,
  policies,
  isSaving = false,
  onSave,
  onCancel,
}: TeamEditorProps) {
  const [form, setForm] = useState<TeamFormData>(initialData ?? emptyForm())
  const saveButtonLabel = teamSaveButtonLabel(isSaving, initialData !== undefined)

  const availableMembers = members.filter((member) => !form.memberIds.includes(member.id))
  const selectedMembers = form.memberIds
    .map((id) => members.find((member) => member.id === id))
    .filter((member): member is TeamEditorOption => member !== undefined)

  const update = <K extends keyof TeamFormData>(key: K, value: TeamFormData[K]) =>
    setForm((prev) => ({...prev, [key]: value}))

  const addMember = (id: string) => update('memberIds', [...form.memberIds, id])
  const removeMember = (id: string) => update('memberIds', form.memberIds.filter((memberId) => memberId !== id))

  const nameValid = form.name.trim() !== ''

  const handleSave = () => {
    if (!nameValid) return
    onSave({
      ...form,
      name: form.name.trim(),
      description: form.description.trim(),
      slack: form.slack.trim(),
      repo: form.repo.trim(),
    })
  }

  return (
    <div className="space-y-5">
      <div className="space-y-2">
        <Label htmlFor="team-name">Team name</Label>
        <Input
          id="team-name"
          value={form.name}
          onChange={(e) => update('name', e.target.value)}
          placeholder="e.g., Payments"
          autoFocus
        />
      </div>

      <div className="space-y-2">
        <Label htmlFor="team-description">Description</Label>
        <Textarea
          id="team-description"
          value={form.description}
          onChange={(e) => update('description', e.target.value)}
          placeholder="What this team owns and is responsible for"
          rows={2}
        />
      </div>

      <div className="grid grid-cols-1 gap-4 sm:grid-cols-2">
        <div className="space-y-2">
          <Label htmlFor="team-slack">Slack channel</Label>
          <Input
            id="team-slack"
            value={form.slack}
            onChange={(e) => update('slack', e.target.value)}
            placeholder="#payments-oncall"
          />
        </div>
        <div className="space-y-2">
          <Label htmlFor="team-repo">Repository</Label>
          <Input
            id="team-repo"
            value={form.repo}
            onChange={(e) => update('repo', e.target.value)}
            placeholder="moneat-io/payments"
          />
        </div>
      </div>

      <div className="grid grid-cols-1 gap-4 sm:grid-cols-2">
        <div className="space-y-2">
          <Label htmlFor="team-schedule">Primary on-call schedule</Label>
          <Select
            value={form.onCallScheduleId ?? NONE_VALUE}
            onValueChange={(value) => update('onCallScheduleId', value === NONE_VALUE ? null : value)}
          >
            <SelectTrigger id="team-schedule">
              <SelectValue placeholder="None" />
            </SelectTrigger>
            <SelectContent>
              <SelectItem value={NONE_VALUE}>None</SelectItem>
              {schedules.map((schedule) => (
                <SelectItem key={schedule.id} value={schedule.id}>
                  {schedule.name}
                </SelectItem>
              ))}
            </SelectContent>
          </Select>
        </div>
        <div className="space-y-2">
          <Label htmlFor="team-policy">Default escalation policy</Label>
          <Select
            value={form.escalationPolicyId ?? NONE_VALUE}
            onValueChange={(value) => update('escalationPolicyId', value === NONE_VALUE ? null : value)}
          >
            <SelectTrigger id="team-policy">
              <SelectValue placeholder="None" />
            </SelectTrigger>
            <SelectContent>
              <SelectItem value={NONE_VALUE}>None</SelectItem>
              {policies.map((policy) => (
                <SelectItem key={policy.id} value={policy.id}>
                  {policy.name}
                </SelectItem>
              ))}
            </SelectContent>
          </Select>
        </div>
      </div>

      <div className="space-y-2">
        <Label className="flex items-center gap-1.5">
          <Users2 className="h-4 w-4 text-muted-foreground" />
          Members
        </Label>
        {selectedMembers.length > 0 ? (
          <div className="flex flex-wrap gap-1.5">
            {selectedMembers.map((member) => (
              <Badge key={member.id} variant="neutral" className="gap-1 pr-1">
                {member.name}
                <button
                  type="button"
                  aria-label={`Remove ${member.name}`}
                  className="rounded-sm p-0.5 text-muted-foreground hover:bg-muted hover:text-foreground"
                  onClick={() => removeMember(member.id)}
                >
                  <X className="h-3 w-3" />
                </button>
              </Badge>
            ))}
          </div>
        ) : (
          <p className="text-xs text-muted-foreground">No members yet.</p>
        )}
        {availableMembers.length > 0 && (
          <Select value="" onValueChange={addMember}>
            <SelectTrigger aria-label="Add member">
              <SelectValue placeholder="Add member" />
            </SelectTrigger>
            <SelectContent>
              {availableMembers.map((member) => (
                <SelectItem key={member.id} value={member.id}>
                  {member.name}
                </SelectItem>
              ))}
            </SelectContent>
          </Select>
        )}
      </div>

      <div className="flex justify-end gap-2 border-t pt-4">
        <Button type="button" variant="outline" onClick={onCancel} disabled={isSaving}>
          Cancel
        </Button>
        <Button type="button" onClick={handleSave} disabled={!nameValid || isSaving}>
          {saveButtonLabel}
        </Button>
      </div>
    </div>
  )
}

function teamSaveButtonLabel(isSaving: boolean, isEditing: boolean): string {
  if (isSaving) return 'Saving…'
  return isEditing ? 'Save changes' : 'Create team'
}
