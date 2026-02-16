// Moneat - Mobile-First Error Monitoring Platform
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

import { useState } from 'react'
import { Button } from '@/components/ui/button'
import { Card, CardContent, CardHeader, CardTitle } from '@/components/ui/card'
import { Input } from '@/components/ui/input'
import { Label } from '@/components/ui/label'
import {
  Select,
  SelectContent,
  SelectItem,
  SelectTrigger,
  SelectValue,
} from '@/components/ui/select'
import { Badge } from '@/components/ui/badge'
import { TIMEZONES } from '@/lib/timezones'
import { Calendar, Trash2, User } from 'lucide-react'

export interface OnCallScheduleData {
  name: string
  rotationType: 'DAILY' | 'WEEKLY' | 'CUSTOM'
  handoffTime: string
  timezone: string
  participantIds: number[]
}

interface User {
  id: number
  name: string
}

interface ScheduleEditorProps {
  initialData?: OnCallScheduleData
  users: User[]
  onSave: (data: OnCallScheduleData) => void
  onCancel: () => void
}

export function ScheduleEditor({ initialData, users, onSave, onCancel }: ScheduleEditorProps) {
  const [schedule, setSchedule] = useState<OnCallScheduleData>(
    initialData || {
      name: '',
      rotationType: 'WEEKLY',
      handoffTime: '09:00:00',
      timezone: Intl.DateTimeFormat().resolvedOptions().timeZone,
      participantIds: [],
    }
  )

  const availableUsers = users.filter((u) => !schedule.participantIds.includes(u.id))
  const hasCurrentTimezoneOption = TIMEZONES.some((tz) => tz.value === schedule.timezone)

  const addParticipant = (userId: number) => {
    setSchedule((prev) => ({
      ...prev,
      participantIds: [...prev.participantIds, userId],
    }))
  }

  const removeParticipant = (userId: number) => {
    setSchedule((prev) => ({
      ...prev,
      participantIds: prev.participantIds.filter((id) => id !== userId),
    }))
  }

  const moveParticipant = (index: number, direction: 'up' | 'down') => {
    const newIndex = direction === 'up' ? index - 1 : index + 1
    if (newIndex < 0 || newIndex >= schedule.participantIds.length) return

    setSchedule((prev) => {
      const newParticipants = [...prev.participantIds]
      ;[newParticipants[index], newParticipants[newIndex]] = [
        newParticipants[newIndex],
        newParticipants[index],
      ]
      return { ...prev, participantIds: newParticipants }
    })
  }

  const handleSave = () => {
    if (!schedule.name.trim()) {
      alert('Please enter a schedule name')
      return
    }
    if (schedule.participantIds.length === 0) {
      alert('Please add at least one participant')
      return
    }
    onSave(schedule)
  }

  return (
    <div className="space-y-6">
      <div className="space-y-4">
        <div className="space-y-2">
          <Label htmlFor="schedule-name">Schedule Name</Label>
          <Input
            id="schedule-name"
            value={schedule.name}
            onChange={(e) => setSchedule((prev) => ({ ...prev, name: e.target.value }))}
            placeholder="e.g., Engineering On-Call"
          />
        </div>

        <div className="grid grid-cols-2 gap-4">
          <div className="space-y-2">
            <Label htmlFor="rotation-type">Rotation Type</Label>
            <Select
              value={schedule.rotationType}
              onValueChange={(value: any) =>
                setSchedule((prev) => ({ ...prev, rotationType: value }))
              }
            >
              <SelectTrigger id="rotation-type">
                <SelectValue />
              </SelectTrigger>
              <SelectContent>
                <SelectItem value="DAILY">Daily</SelectItem>
                <SelectItem value="WEEKLY">Weekly</SelectItem>
                <SelectItem value="CUSTOM">Custom</SelectItem>
              </SelectContent>
            </Select>
          </div>

          <div className="space-y-2">
            <Label htmlFor="handoff-time">Handoff Time</Label>
            <Input
              id="handoff-time"
              type="time"
              value={schedule.handoffTime.slice(0, 5)}
              onChange={(e) =>
                setSchedule((prev) => ({ ...prev, handoffTime: `${e.target.value}:00` }))
              }
            />
          </div>
        </div>

        <div className="space-y-2">
          <Label htmlFor="timezone">Timezone</Label>
          <Select
            value={schedule.timezone}
            onValueChange={(value) =>
              setSchedule((prev) => ({ ...prev, timezone: value }))
            }
          >
            <SelectTrigger id="timezone">
              <SelectValue placeholder="Select timezone..." />
            </SelectTrigger>
            <SelectContent className="max-h-[300px]">
              {!hasCurrentTimezoneOption && schedule.timezone && (
                <SelectItem value={schedule.timezone}>{schedule.timezone}</SelectItem>
              )}
              {TIMEZONES.map((tz) => (
                <SelectItem key={tz.value} value={tz.value}>
                  {tz.label}
                </SelectItem>
              ))}
            </SelectContent>
          </Select>
        </div>
      </div>

      <Card>
        <CardHeader>
          <CardTitle className="text-base flex items-center gap-2">
            <User className="h-4 w-4" />
            Rotation Order
          </CardTitle>
        </CardHeader>
        <CardContent className="space-y-3">
          {schedule.participantIds.length === 0 && (
            <p className="text-sm text-muted-foreground text-center py-4">
              No participants added yet
            </p>
          )}

          {schedule.participantIds.map((userId, index) => {
            const user = users.find((u) => u.id === userId)
            if (!user) return null

            return (
              <div key={userId} className="flex items-center justify-between p-2 border rounded-md">
                <div className="flex items-center gap-2">
                  <Badge variant="outline">{index + 1}</Badge>
                  <span className="text-sm">{user.name}</span>
                </div>
                <div className="flex items-center gap-1">
                  <Button
                    variant="ghost"
                    size="sm"
                    onClick={() => moveParticipant(index, 'up')}
                    disabled={index === 0}
                  >
                    ↑
                  </Button>
                  <Button
                    variant="ghost"
                    size="sm"
                    onClick={() => moveParticipant(index, 'down')}
                    disabled={index === schedule.participantIds.length - 1}
                  >
                    ↓
                  </Button>
                  <Button
                    variant="ghost"
                    size="sm"
                    onClick={() => removeParticipant(userId)}
                  >
                    <Trash2 className="h-4 w-4" />
                  </Button>
                </div>
              </div>
            )
          })}

          {availableUsers.length > 0 && (
            <Select onValueChange={(value) => addParticipant(parseInt(value))}>
              <SelectTrigger>
                <SelectValue placeholder="Add participant" />
              </SelectTrigger>
              <SelectContent>
                {availableUsers.map((user) => (
                  <SelectItem key={user.id} value={user.id.toString()}>
                    {user.name}
                  </SelectItem>
                ))}
              </SelectContent>
            </Select>
          )}
        </CardContent>
      </Card>

      <div className="flex justify-end gap-2 border-t pt-4">
        <Button variant="outline" onClick={onCancel}>
          Cancel
        </Button>
        <Button onClick={handleSave}>
          <Calendar className="h-4 w-4 mr-2" />
          Save Schedule
        </Button>
      </div>
    </div>
  )
}
