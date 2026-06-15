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
import {Copy, Globe} from 'lucide-react'

import {api} from '@/lib/api'
import {Badge} from '@/components/ui/badge'
import {Button} from '@/components/ui/button'
import {Input} from '@/components/ui/input'
import {Select, SelectContent, SelectItem, SelectTrigger, SelectValue} from '@/components/ui/select'
import {useToast} from '@/hooks/useToast'
import {useAuth} from '@/hooks/useAuth'
import {TIMEZONES} from '@/lib/timezones'
import {formatDate} from '@/lib/date-format'
import {useTimezone} from '@/hooks/useTimezone'
import {SettingRow, SettingsBlock, SettingsSection} from './SettingsPrimitives'

export function GeneralSettings() {
  const {user} = useAuth()
  const orgId = user?.orgId
  const {toast} = useToast()
  const queryClient = useQueryClient()
  const {timezone} = useTimezone()

  const {data: orgData} = useQuery({
    queryKey: ['organization', orgId],
    queryFn: () => api.getOrganizationAccountSettings(orgId!),
    enabled: !!orgId,
  })

  const savedName = orgData?.name ?? ''
  const savedTimezone = orgData?.defaultTimezone ?? ''

  // Null edit = "use the server value"; this keeps the form in sync with fetched
  // data without re-seeding state inside an effect.
  const [nameEdit, setNameEdit] = useState<string | null>(null)
  const [tzEdit, setTzEdit] = useState<string | null>(null)
  const name = nameEdit ?? savedName
  const defaultTimezone = tzEdit ?? savedTimezone
  const resetEdits = () => {
    setNameEdit(null)
    setTzEdit(null)
  }

  const dirty = name !== savedName || defaultTimezone !== savedTimezone

  const saveMutation = useMutation({
    mutationFn: () => api.updateOrganizationSettings(orgId!, {name, defaultTimezone}),
    onSuccess: () => {
      queryClient.invalidateQueries({queryKey: ['organization', orgId]})
      resetEdits()
      toast({title: 'Workspace updated', description: 'Your changes have been saved.'})
    },
    onError: (err: Error) =>
      toast({title: 'Failed to save', description: err.message, variant: 'destructive'}),
  })

  const hasTimezoneOption = useMemo(
    () => TIMEZONES.some((tz) => tz.value === defaultTimezone),
    [defaultTimezone]
  )

  const handleCopy = (value: string) => {
    navigator.clipboard.writeText(value)
    toast({title: 'Copied', description: 'Organization ID copied to clipboard.'})
  }

  return (
    <section>
      <SettingsSection
        title="General"
        description="Workspace identity and defaults that apply to everyone in your organization."
        actions={
          <>
            <Button variant="outline" disabled={!dirty} onClick={resetEdits}>
              Discard
            </Button>
            <Button disabled={!dirty || saveMutation.isPending} onClick={() => saveMutation.mutate()}>
              {saveMutation.isPending ? 'Saving…' : 'Save changes'}
            </Button>
          </>
        }
      />

      <SettingsBlock title="Workspace">
        <SettingRow label="Name" description="Shown across the app and in alert notifications.">
          <Input
            className="w-full sm:max-w-[320px]"
            value={name}
            onChange={(e) => setNameEdit(e.target.value)}
          />
        </SettingRow>
        <SettingRow
          label="Default timezone"
          description="New members inherit this; each member can override it in Preferences."
        >
          <Select value={defaultTimezone || '__unset__'} onValueChange={(v) => setTzEdit(v === '__unset__' ? '' : v)}>
            <SelectTrigger className="w-full sm:max-w-[320px]">
              <SelectValue placeholder="Select timezone…" />
            </SelectTrigger>
            <SelectContent className="max-h-[300px]">
              <SelectItem value="__unset__">UTC (default)</SelectItem>
              {!hasTimezoneOption && defaultTimezone && (
                <SelectItem value={defaultTimezone}>{defaultTimezone}</SelectItem>
              )}
              {TIMEZONES.map((tz) => (
                <SelectItem key={tz.value} value={tz.value}>
                  {tz.label}
                </SelectItem>
              ))}
            </SelectContent>
          </Select>
        </SettingRow>
      </SettingsBlock>

      <SettingsBlock title="Region & identity">
        <SettingRow label="Data region" description="Where your telemetry is stored. Set at creation and can't be changed.">
          <Badge variant="secondary" className="gap-1.5 font-normal">
            <Globe className="h-3 w-3" />
            {orgData?.dataRegion ?? 'Not set'}
          </Badge>
        </SettingRow>
        <SettingRow label="Organization ID" description="Reference this in support requests and the API.">
          <span className="font-mono text-xs text-muted-foreground">{orgData?.id ?? '—'}</span>
          {orgData?.id && (
            <Button
              variant="ghost"
              size="icon"
              className="h-7 w-7"
              onClick={() => handleCopy(orgData.id)}
              aria-label="Copy organization ID"
            >
              <Copy className="h-3.5 w-3.5" />
            </Button>
          )}
        </SettingRow>
        <SettingRow label="Created">
          <span className="font-mono text-xs text-muted-foreground">
            {orgData?.createdAt ? formatDate(new Date(orgData.createdAt), timezone) : '—'}
          </span>
        </SettingRow>
      </SettingsBlock>
    </section>
  )
}
