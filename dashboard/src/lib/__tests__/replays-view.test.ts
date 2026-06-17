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

import {describe, expect, it} from 'vitest'
import {
  browserOsLabel,
  deriveReplaySignals,
  formatReplayClock,
  getActivityLevel,
  isAnonymous,
  isMobileOs,
  replayAvatarClass,
  replayDisplayName,
  replayEntryPath,
  replayExtraPageCount,
  replayInitials,
  replayStatusTone,
} from '@/lib/replays-view'

describe('formatReplayClock', () => {
  it('formats minutes:seconds and clamps negatives/sub-second', () => {
    expect(formatReplayClock(0)).toBe('0:00')
    expect(formatReplayClock(-5)).toBe('0:00')
    expect(formatReplayClock(500)).toBe('0:00')
    expect(formatReplayClock(84_000)).toBe('1:24')
    expect(formatReplayClock(9_000)).toBe('0:09')
  })

  it('adds an hours segment past 60 minutes', () => {
    expect(formatReplayClock(3_661_000)).toBe('1:01:01')
  })
})

describe('replayDisplayName / isAnonymous', () => {
  it('prefers email, then username, then id, then Anonymous', () => {
    expect(replayDisplayName({email: 'a@b.com', username: 'A', id: '1'})).toBe('a@b.com')
    expect(replayDisplayName({username: 'Worker User', id: '1'})).toBe('Worker User')
    expect(replayDisplayName({id: 'anon-1'})).toBe('anon-1')
    expect(replayDisplayName(undefined)).toBe('Anonymous')
    expect(replayDisplayName({})).toBe('Anonymous')
  })

  it('detects anonymous users', () => {
    expect(isAnonymous(undefined)).toBe(true)
    expect(isAnonymous({})).toBe(true)
    expect(isAnonymous({id: 'x'})).toBe(false)
  })
})

describe('replayInitials', () => {
  it('derives initials from the best available identity', () => {
    expect(replayInitials({username: 'Ada Lovelace'})).toBe('AL')
    expect(replayInitials({username: 'ada'})).toBe('AD')
    expect(replayInitials({email: 'zoe@x.io'})).toBe('ZO')
    expect(replayInitials({id: 'qx-9'})).toBe('QX')
    expect(replayInitials(undefined)).toBe('?')
  })
})

describe('replayAvatarClass', () => {
  it('returns a muted tint for anonymous and a deterministic chart tint otherwise', () => {
    expect(replayAvatarClass(undefined)).toContain('bg-muted')
    const a = replayAvatarClass({email: 'a@b.com'})
    const b = replayAvatarClass({email: 'a@b.com'})
    expect(a).toBe(b)
    expect(a).toMatch(/bg-chart-\d/)
  })
})

describe('getActivityLevel', () => {
  it('buckets activity into High/Medium/Low/Idle', () => {
    expect(getActivityLevel(95).label).toBe('High')
    expect(getActivityLevel(50).label).toBe('Medium')
    expect(getActivityLevel(10).label).toBe('Low')
    expect(getActivityLevel(0).label).toBe('Idle')
    expect(getActivityLevel(95).barClass).toContain('bg-')
  })
})

describe('deriveReplaySignals', () => {
  it('derives the error badge from errorCount', () => {
    expect(deriveReplaySignals({errorCount: 2})).toEqual([
      {key: 'error', label: '2', variant: 'danger'},
    ])
    expect(deriveReplaySignals({errorCount: 0})).toEqual([])
  })

  it('appends behavioural signals and ignores a redundant error signal', () => {
    const badges = deriveReplaySignals({
      errorCount: 1,
      signals: ['rage_click', 'bounce', 'error', 'purchase'],
    })
    expect(badges.map((b) => b.key)).toEqual(['error', 'rage', 'bounce', 'purchase'])
  })
})

describe('replayEntryPath / replayExtraPageCount', () => {
  it('reduces a URL to its path and prefers entryUrl', () => {
    expect(replayEntryPath({urls: ['https://app.x.io/checkout?step=2']})).toBe('/checkout?step=2')
    expect(replayEntryPath({entryUrl: 'https://x.io/a', urls: ['https://x.io/b']})).toBe('/a')
    expect(replayEntryPath({urls: ['https://x.io']})).toBe('/')
    expect(replayEntryPath({urls: ['/already/a/path']})).toBe('/already/a/path')
    expect(replayEntryPath({urls: []})).toBe('—')
  })

  it('counts pages beyond the entry', () => {
    expect(replayExtraPageCount({urls: ['a', 'b', 'c']})).toBe(2)
    expect(replayExtraPageCount({urls: ['a']})).toBe(0)
    expect(replayExtraPageCount({urls: []})).toBe(0)
  })
})

describe('replayStatusTone', () => {
  it('prioritises errors, then friction signals, then activity', () => {
    expect(replayStatusTone({errorCount: 1, activity: 0})).toBe('danger')
    expect(replayStatusTone({errorCount: 0, activity: 10, signals: ['rage_click']})).toBe('warning')
    expect(replayStatusTone({errorCount: 0, activity: 90})).toBe('success')
    expect(replayStatusTone({errorCount: 0, activity: 30})).toBe('neutral')
  })
})

describe('browserOsLabel / isMobileOs', () => {
  it('joins browser and os when present', () => {
    expect(browserOsLabel({browserName: 'Chrome', osName: 'macOS'})).toBe('Chrome · macOS')
    expect(browserOsLabel({})).toBeNull()
  })

  it('recognises mobile operating systems', () => {
    expect(isMobileOs('iOS 17')).toBe(true)
    expect(isMobileOs('Android 14')).toBe(true)
    expect(isMobileOs('macOS 14')).toBe(false)
    expect(isMobileOs(undefined)).toBe(false)
  })
})
