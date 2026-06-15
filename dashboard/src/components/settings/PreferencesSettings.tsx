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
import {Check, Loader2, Minus} from 'lucide-react'

import {api} from '@/lib/api'
import {Button} from '@/components/ui/button'
import {Select, SelectContent, SelectItem, SelectTrigger, SelectValue} from '@/components/ui/select'
import {Switch} from '@/components/ui/switch'
import {ThemePicker} from '@/components/ThemePicker'
import {useToast} from '@/hooks/useToast'
import {useTimezone} from '@/hooks/useTimezone'
import {TIMEZONES} from '@/lib/timezones'
import {CONFIGURABLE_SIDEBAR_ITEMS, getAllSidebarItemKeys} from '@/lib/sidebar-config'
import {formatDateTime} from '@/lib/date-format'
import {SettingRow, SettingsBlock, SettingsSection} from './SettingsPrimitives'

type DateFormatPref = 'medium' | 'iso' | 'dmy'

function asDateFormat(value: unknown): DateFormatPref | undefined {
  return value === 'medium' || value === 'iso' || value === 'dmy' ? value : undefined
}

export function PreferencesSettings() {
  const {toast} = useToast()
  const queryClient = useQueryClient()
  const {timezone, updateTimezone} = useTimezone()
  const browserTz = Intl.DateTimeFormat().resolvedOptions().timeZone

  const {data: user} = useQuery({queryKey: ['currentUser'], queryFn: () => api.getCurrentUser()})

  // Date format is server-backed (returned on the user, saved via
  // updateUserPreferences). A null edit means "follow the server value"; we fall
  // back to localStorage for instant paint before the user query resolves.
  const serverDateFormat = asDateFormat(user?.dateFormat)
  const storedDateFormat = asDateFormat(globalThis.localStorage?.getItem('dateFormat'))
  const [dateFormatEdit, setDateFormatEdit] = useState<DateFormatPref | null>(null)
  const dateFormat: DateFormatPref =
    dateFormatEdit ??
    serverDateFormat ??
    storedDateFormat ??
    'medium'

  const savePrefsMutation = useMutation({
    mutationFn: (prefs: Partial<{dateFormat: DateFormatPref}>) =>
      api.updateUserPreferences(prefs),
    onSuccess: () => {
      queryClient.invalidateQueries({queryKey: ['currentUser']})
      setDateFormatEdit(null)
    },
    onError: () =>
      toast({title: 'Error', description: 'Failed to save preference.', variant: 'destructive'}),
  })

  const handleDateFormatChange = (next: DateFormatPref) => {
    setDateFormatEdit(next)
    globalThis.localStorage?.setItem('dateFormat', next)
    savePrefsMutation.mutate({dateFormat: next})
  }

  // ── Locale: timezone (server-backed) ─────────────────────────────────────
  const savedTimezone = user?.timezone ?? null
  const hasCurrentTimezoneOption = TIMEZONES.some((tz) => tz.value === (savedTimezone ?? browserTz))
  const [savingTz, setSavingTz] = useState(false)

  const handleTimezoneChange = async (value: string) => {
    setSavingTz(true)
    try {
      await updateTimezone(value === '__browser__' ? null : value)
      toast({title: 'Preferences saved', description: 'Your timezone has been updated.'})
    } catch {
      toast({title: 'Error', description: 'Failed to save timezone.', variant: 'destructive'})
    } finally {
      setSavingTz(false)
    }
  }

  // ── Sidebar navigation (server-backed) ───────────────────────────────────
  // Null edit = "use the server value"; avoids re-seeding state inside an effect.
  const savedHidden = user?.sidebarHiddenItems || []
  const [hiddenEdit, setHiddenEdit] = useState<string[] | null>(null)
  const hiddenItems = hiddenEdit ?? savedHidden

  const saveSidebarMutation = useMutation({
    mutationFn: (items: string[]) => api.updateSidebarPreferences(items),
    onSuccess: () => {
      queryClient.invalidateQueries({queryKey: ['currentUser']})
      setHiddenEdit(null)
      toast({title: 'Preferences saved', description: 'Your sidebar has been updated.'})
    },
    onError: () =>
      toast({title: 'Error', description: 'Failed to save sidebar preferences.', variant: 'destructive'}),
  })

  const toggleItem = (key: string) =>
    setHiddenEdit((prev) => {
      const base = prev ?? savedHidden
      return base.includes(key) ? base.filter((k) => k !== key) : [...base, key]
    })
  const sidebarDirty =
    JSON.stringify([...hiddenItems].sort()) !== JSON.stringify([...savedHidden].sort())

  const previewNow = formatDateTime(new Date(), timezone)

  return (
    <section>
      <SettingsSection
        title="Preferences"
        description="Personalize how Moneat looks and behaves for you. These settings apply to your account only."
      />

      <SettingsBlock title="Appearance" hint="Theme applies everywhere and stays in sync with the sidebar switcher.">
        <ThemePicker />
      </SettingsBlock>

      <SettingsBlock title="Locale">
        <SettingRow
          label="Timezone"
          description={<>Overrides the workspace default for your view. Preview: <span className="font-mono">{previewNow}</span></>}
        >
          {savingTz && <Loader2 className="h-3.5 w-3.5 animate-spin text-muted-foreground" />}
          <Select value={savedTimezone ?? '__browser__'} onValueChange={handleTimezoneChange}>
            <SelectTrigger className="w-full sm:max-w-[320px]">
              <SelectValue placeholder="Select timezone…" />
            </SelectTrigger>
            <SelectContent className="max-h-[300px]">
              <SelectItem value="__browser__">Browser default ({browserTz})</SelectItem>
              {!hasCurrentTimezoneOption && savedTimezone && (
                <SelectItem value={savedTimezone}>{savedTimezone}</SelectItem>
              )}
              {TIMEZONES.map((tz) => (
                <SelectItem key={tz.value} value={tz.value}>
                  {tz.label}
                </SelectItem>
              ))}
            </SelectContent>
          </Select>
        </SettingRow>
        <SettingRow label="Date format">
          <Select
            value={dateFormat}
            onValueChange={(value) => {
              const parsed = asDateFormat(value)
              if (parsed) handleDateFormatChange(parsed)
            }}
          >
            <SelectTrigger className="w-full sm:max-w-[320px]">
              <SelectValue />
            </SelectTrigger>
            <SelectContent>
              <SelectItem value="medium">Jun 14, 2026</SelectItem>
              <SelectItem value="iso">2026-06-14</SelectItem>
              <SelectItem value="dmy">14/06/2026</SelectItem>
            </SelectContent>
          </Select>
        </SettingRow>
      </SettingsBlock>

      <SettingsBlock
        title="Sidebar navigation"
        actions={
          <>
            <Button variant="ghost" size="sm" onClick={() => setHiddenEdit([])}>
              <Check className="mr-1.5 h-3.5 w-3.5" />
              Show all
            </Button>
            <Button variant="ghost" size="sm" onClick={() => setHiddenEdit(getAllSidebarItemKeys())}>
              <Minus className="mr-1.5 h-3.5 w-3.5" />
              Hide all
            </Button>
          </>
        }
        hint="Choose which features appear in your sidebar. Settings is always visible."
      >
        <div className="grid grid-cols-1 gap-2 sm:grid-cols-2">
          {CONFIGURABLE_SIDEBAR_ITEMS.map((item) => {
            const ItemIcon = item.icon
            return (
              <label
                key={item.key}
                className="flex h-[34px] cursor-pointer items-center gap-2.5 rounded-md border px-2.5"
              >
                {ItemIcon && <ItemIcon className="h-4 w-4 text-muted-foreground" />}
                <span className="flex-1 text-sm font-medium">{item.label}</span>
                <Switch
                  checked={!hiddenItems.includes(item.key)}
                  onCheckedChange={() => toggleItem(item.key)}
                />
              </label>
            )
          })}
        </div>
        {sidebarDirty && (
          <div className="mt-3 flex justify-end">
            <Button
              size="sm"
              onClick={() => saveSidebarMutation.mutate(hiddenItems)}
              disabled={saveSidebarMutation.isPending}
            >
              {saveSidebarMutation.isPending && <Loader2 className="mr-2 h-4 w-4 animate-spin" />}
              Save changes
            </Button>
          </div>
        )}
      </SettingsBlock>
    </section>
  )
}
