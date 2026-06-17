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

import {render, screen} from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import {describe, expect, it, vi} from 'vitest'

import {
  OnCallFallbackSettings,
  SilencePeriodList,
} from '../NotificationDeliverySections'
import {
  describeEmailDelivery,
  describePushDelivery,
  formatAlertFrequency,
  isValidOnCallPhone,
} from '../NotificationDeliveryFormatting'
import type {SilencePeriod} from '@/lib/api/types/monitoring'

vi.mock('@tanstack/react-router', () => ({
  Link: ({to, children, ...props}: {to: string; children: React.ReactNode}) => (
    <a href={to} {...props}>
      {children}
    </a>
  ),
}))

describe('NotificationDeliverySections', () => {
  it('formats delivery descriptions and alert frequency labels', () => {
    expect(describeEmailDelivery()).toBe('Your account email.')
    expect(describeEmailDelivery('ada@example.com', true)).toBe('ada@example.com - verified')
    expect(describeEmailDelivery('ada@example.com', false)).toBe('ada@example.com - unverified')
    expect(describePushDelivery(0)).toMatch(/No devices registered/)
    expect(describePushDelivery(1)).toBe('Deliver alert and on-call pushes to your 1 registered device.')
    expect(describePushDelivery(2)).toBe('Deliver alert and on-call pushes to your 2 registered devices.')
    expect(formatAlertFrequency(30)).toBe('30m')
    expect(formatAlertFrequency(60)).toBe('1h')
    expect(formatAlertFrequency(240)).toBe('4h')
  })

  it('validates on-call phone numbers and blocks save until consent is checked', async () => {
    const user = userEvent.setup()
    const onPhoneChange = vi.fn()
    const onConsentChange = vi.fn()
    const onSave = vi.fn()

    expect(isValidOnCallPhone('+15551234567')).toBe(true)
    expect(isValidOnCallPhone('555-123-4567')).toBe(false)

    render(
      <OnCallFallbackSettings
        contact={{
          phoneNumber: '+15551234567',
          onCallPhoneOptIn: false,
          onCallPhoneConsentedAt: null,
          onCallPhoneConsentVersion: null,
        }}
        isLoading={false}
        phone="+15551234567"
        consent={false}
        timezone="UTC"
        isSaving={false}
        isDeleting={false}
        onPhoneChange={onPhoneChange}
        onConsentChange={onConsentChange}
        onSave={onSave}
        onDelete={vi.fn()}
      />
    )

    expect(screen.getByText(/Phone number saved but not opted in yet/)).toBeInTheDocument()
    expect(screen.getByRole('button', {name: 'Save & opt in'})).toBeDisabled()

    await user.type(screen.getByLabelText('Mobile number (E.164 format)'), '8')
    await user.click(screen.getByRole('checkbox'))

    expect(onPhoneChange).toHaveBeenLastCalledWith('+155512345678')
    expect(onConsentChange).toHaveBeenCalledWith(true)
    expect(onSave).not.toHaveBeenCalled()
  })

  it('shows the on-call loading state', () => {
    render(
      <OnCallFallbackSettings
        isLoading={true}
        phone=""
        consent={false}
        timezone="UTC"
        isSaving={false}
        isDeleting={false}
        onPhoneChange={vi.fn()}
        onConsentChange={vi.fn()}
        onSave={vi.fn()}
        onDelete={vi.fn()}
      />
    )

    expect(screen.getByText(/Loading/)).toBeInTheDocument()
  })

  it('shows opted-in contact state and calls delete', async () => {
    const user = userEvent.setup()
    const onDelete = vi.fn()

    render(
      <OnCallFallbackSettings
        contact={{
          phoneNumber: '+15551234567',
          onCallPhoneOptIn: true,
          onCallPhoneConsentedAt: '2026-06-15T12:00:00Z',
          onCallPhoneConsentVersion: 'v1',
        }}
        isLoading={false}
        phone="+15551234567"
        consent={false}
        timezone="UTC"
        isSaving={false}
        isDeleting={false}
        onPhoneChange={vi.fn()}
        onConsentChange={vi.fn()}
        onSave={vi.fn()}
        onDelete={onDelete}
      />
    )

    expect(screen.getByText('Opted in - +15551234567')).toBeInTheDocument()

    await user.click(screen.getByRole('button', {name: 'Remove & opt out'}))

    expect(onDelete).toHaveBeenCalledTimes(1)
  })

  it('renders active and scheduled silence periods and deletes selected rows', async () => {
    const user = userEvent.setup()
    const onDelete = vi.fn()
    const periods: SilencePeriod[] = [
      {
        id: 'active-1',
        organizationId: 'org-1',
        reason: 'Deploy',
        startsAt: 1_000,
        endsAt: 2_000,
        createdBy: 'user-1',
        createdAt: 500,
      },
      {
        id: 'scheduled-1',
        organizationId: 'org-1',
        reason: null,
        startsAt: 3_000,
        endsAt: 4_000,
        createdBy: 'user-1',
        createdAt: 500,
      },
    ]

    render(
      <SilencePeriodList
        isLoading={false}
        activePeriods={[periods[0]]}
        scheduledPeriods={[periods[1]]}
        isDeleting={false}
        onDelete={onDelete}
        formatDateTime={(timestampMs) => `time-${timestampMs}`}
        formatTimeRemaining={() => '25m remaining'}
      />
    )

    expect(screen.getByText('Deploy')).toBeInTheDocument()
    expect(screen.getByText('Scheduled silence')).toBeInTheDocument()
    expect(screen.getByText('Active - 25m')).toBeInTheDocument()
    expect(screen.getByText('time-1000 - time-2000')).toBeInTheDocument()

    await user.click(screen.getAllByRole('button')[0])

    expect(onDelete).toHaveBeenCalledWith('active-1')
  })

  it('renders loading and empty silence states', () => {
    const noop = vi.fn()

    const {rerender} = render(
      <SilencePeriodList
        isLoading={true}
        activePeriods={[]}
        scheduledPeriods={[]}
        isDeleting={false}
        onDelete={noop}
        formatDateTime={(timestampMs) => `time-${timestampMs}`}
        formatTimeRemaining={() => '25m remaining'}
      />
    )

    expect(screen.getByText(/Loading silence periods/)).toBeInTheDocument()

    rerender(
      <SilencePeriodList
        isLoading={false}
        activePeriods={[]}
        scheduledPeriods={[]}
        isDeleting={false}
        onDelete={noop}
        formatDateTime={(timestampMs) => `time-${timestampMs}`}
        formatTimeRemaining={() => '25m remaining'}
      />
    )

    expect(screen.getByText('No silence periods')).toBeInTheDocument()
  })
})
