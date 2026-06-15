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

import {Link} from '@tanstack/react-router'
import {AlertCircle, CheckCircle2, Loader2, Trash2} from 'lucide-react'

import {Badge} from '@/components/ui/badge'
import {Button} from '@/components/ui/button'
import {Checkbox} from '@/components/ui/checkbox'
import {Input} from '@/components/ui/input'
import {Label} from '@/components/ui/label'
import {StatusDot} from '@/components/ui/status-dot'
import {formatDate as formatDateUtil} from '@/lib/date-format'
import {isValidOnCallPhone} from './NotificationDeliveryFormatting'
import type {OnCallContactSettings} from '@/lib/api/types/on-call'
import type {SilencePeriod} from '@/lib/api/types/monitoring'

export function OnCallFallbackSettings({
  contact,
  isLoading,
  phone,
  consent,
  timezone,
  isSaving,
  isDeleting,
  onPhoneChange,
  onConsentChange,
  onSave,
  onDelete,
}: Readonly<{
  contact?: OnCallContactSettings
  isLoading: boolean
  phone: string
  consent: boolean
  timezone: string
  isSaving: boolean
  isDeleting: boolean
  onPhoneChange: (value: string) => void
  onConsentChange: (value: boolean) => void
  onSave: () => void
  onDelete: () => void
}>) {
  if (isLoading) {
    return (
      <div className="flex items-center gap-2 text-muted-foreground text-sm">
        <Loader2 className="h-4 w-4 animate-spin" /> Loading...
      </div>
    )
  }

  if (contact?.onCallPhoneOptIn) {
    return (
      <div className="space-y-3">
        <div className="flex items-center gap-2">
          <CheckCircle2 className="h-4 w-4 text-success-fg" />
          <span className="text-sm font-medium">Opted in - {contact.phoneNumber}</span>
        </div>
        {contact.onCallPhoneConsentedAt && (
          <p className="text-xs text-muted-foreground">
            Consented on {formatDateUtil(new Date(contact.onCallPhoneConsentedAt), timezone)}
          </p>
        )}
        <div className="flex gap-2">
          <Button size="sm" variant="destructive" onClick={onDelete} disabled={isDeleting}>
            {isDeleting ? <Loader2 className="h-4 w-4 animate-spin" /> : 'Remove & opt out'}
          </Button>
        </div>
      </div>
    )
  }

  return (
    <div className="space-y-4 max-w-sm">
      {contact?.phoneNumber && !contact.onCallPhoneOptIn && (
        <div className="flex items-center gap-2 text-warning-fg text-sm">
          <AlertCircle className="h-4 w-4" />
          Phone number saved but not opted in yet. Check the consent box below to enable alerts.
        </div>
      )}
      <div className="space-y-2">
        <Label htmlFor="oncall-phone">Mobile number (E.164 format)</Label>
        <Input
          id="oncall-phone"
          type="tel"
          placeholder="+15551234567"
          value={phone}
          onChange={(event) => onPhoneChange(event.target.value)}
        />
      </div>
      <div className="flex items-start gap-2">
        <Checkbox
          id="oncall-consent"
          checked={consent}
          onCheckedChange={(checked) => onConsentChange(checked === true)}
          className="mt-0.5"
        />
        <Label htmlFor="oncall-consent" className="text-sm font-normal leading-snug cursor-pointer">
          I agree to receive on-call alert SMS messages and voice calls from Moneat at the number provided.
          Message and data rates may apply. Reply STOP to unsubscribe or HELP for help.
          I understand I can manage this setting anytime in my account.{' '}
          <Link to="/legal/sms-consent" className="underline text-primary" target="_blank">
            Learn more
          </Link>
        </Label>
      </div>
      <Button
        size="sm"
        onClick={onSave}
        disabled={!isValidOnCallPhone(phone) || !consent || isSaving}
      >
        {isSaving ? (
          <><Loader2 className="h-4 w-4 mr-2 animate-spin" />Saving...</>
        ) : (
          'Save & opt in'
        )}
      </Button>
      {contact?.phoneNumber && (
        <Button size="sm" variant="ghost" onClick={onDelete} disabled={isDeleting}>
          Remove number
        </Button>
      )}
    </div>
  )
}

export function SilencePeriodList({
  isLoading,
  activePeriods,
  scheduledPeriods,
  isDeleting,
  onDelete,
  formatDateTime,
  formatTimeRemaining,
}: Readonly<{
  isLoading: boolean
  activePeriods: SilencePeriod[]
  scheduledPeriods: SilencePeriod[]
  isDeleting: boolean
  onDelete: (periodId: string) => void
  formatDateTime: (timestampMs: number) => string
  formatTimeRemaining: (endsAt: number) => string
}>) {
  if (isLoading) {
    return (
      <div className="flex items-center gap-2 px-4 py-8 text-sm text-muted-foreground">
        <Loader2 className="h-4 w-4 animate-spin" /> Loading silence periods...
      </div>
    )
  }

  if (activePeriods.length === 0 && scheduledPeriods.length === 0) {
    return (
      <div className="px-4 py-12 text-center">
        <p className="text-sm font-medium">No silence periods</p>
        <p className="mx-auto mt-1 max-w-sm text-sm text-muted-foreground">
          Use the quick silence buttons above or schedule a maintenance window to suppress alert notifications.
        </p>
      </div>
    )
  }

  return (
    <div className="divide-y">
      {activePeriods.map((period) => (
        <SilencePeriodRow
          key={period.id}
          period={period}
          status="active"
          isDeleting={isDeleting}
          onDelete={onDelete}
          formatDateTime={formatDateTime}
          formatTimeRemaining={formatTimeRemaining}
        />
      ))}
      {scheduledPeriods.map((period) => (
        <SilencePeriodRow
          key={period.id}
          period={period}
          status="scheduled"
          isDeleting={isDeleting}
          onDelete={onDelete}
          formatDateTime={formatDateTime}
          formatTimeRemaining={formatTimeRemaining}
        />
      ))}
    </div>
  )
}

function SilencePeriodRow({
  period,
  status,
  isDeleting,
  onDelete,
  formatDateTime,
  formatTimeRemaining,
}: Readonly<{
  period: SilencePeriod
  status: 'active' | 'scheduled'
  isDeleting: boolean
  onDelete: (periodId: string) => void
  formatDateTime: (timestampMs: number) => string
  formatTimeRemaining: (endsAt: number) => string
}>) {
  const isActive = status === 'active'
  const title = period.reason || (isActive ? 'Silence period' : 'Scheduled silence')
  const badge =
    isActive ? (
      <Badge variant="warning">Active - {formatTimeRemaining(period.endsAt).replace(' remaining', '')}</Badge>
    ) : (
      <Badge variant="secondary">Scheduled</Badge>
    )

  return (
    <div className="group flex items-center gap-3 px-4 py-2.5">
      <StatusDot tone={isActive ? 'warning' : 'neutral'} />
      <div className="min-w-0 flex-1">
        <div className="flex items-center gap-2">
          <span className="text-sm font-medium">{title}</span>
          {badge}
        </div>
        <div className="mt-0.5 font-mono text-xs text-muted-foreground">
          {formatDateTime(period.startsAt)} - {formatDateTime(period.endsAt)}
        </div>
      </div>
      <Button
        size="icon"
        variant="ghost"
        className="h-7 w-7 opacity-0 transition-opacity group-hover:opacity-100"
        aria-label="Delete silence period"
        onClick={() => onDelete(period.id)}
        disabled={isDeleting}
      >
        <Trash2 className="h-3.5 w-3.5 text-destructive" />
      </Button>
    </div>
  )
}
