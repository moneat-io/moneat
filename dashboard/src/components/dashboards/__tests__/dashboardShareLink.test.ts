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

import {describe, it, expect} from 'vitest'
import {buildDashboardShareUrl, parseDashboardLink} from '../dashboardShareLink'

describe('parseDashboardLink', () => {
  it('extracts a time range when both ends are present', () => {
    expect(parseDashboardLink({from: 'now-1h', to: 'now'}).timeRange).toEqual({from: 'now-1h', to: 'now'})
    expect(parseDashboardLink({from: 'now-1h'}).timeRange).toBeUndefined()
  })

  it('parses variable selections from JSON', () => {
    expect(parseDashboardLink({vars: '{"env":"prod","pod":"a,b"}'}).variableValues).toEqual({
      env: 'prod', pod: 'a,b',
    })
  })

  it('ignores malformed, non-object, or non-string variable payloads', () => {
    expect(parseDashboardLink({vars: 'not json'}).variableValues).toBeUndefined()
    expect(parseDashboardLink({vars: '[1,2]'}).variableValues).toBeUndefined()
    expect(parseDashboardLink({vars: '{"n":5}'}).variableValues).toBeUndefined()
    expect(parseDashboardLink({})).toEqual({})
  })
})

describe('buildDashboardShareUrl', () => {
  it('encodes the time range and variables', () => {
    const url = buildDashboardShareUrl(
      'https://app.moneat.io/dashboards/7',
      {from: 'now-1h', to: 'now'},
      {env: 'prod'},
    )
    expect(url).toContain('from=now-1h')
    expect(url).toContain('to=now')
    expect(url).toContain('vars=')
    expect(url.startsWith('https://app.moneat.io/dashboards/7?')).toBe(true)
  })

  it('omits empty variable values', () => {
    const url = buildDashboardShareUrl('https://x/d/1', {from: 'now-1h', to: 'now'}, {env: ''})
    expect(url).not.toContain('vars=')
  })

  it('returns the bare base when there is nothing to encode', () => {
    expect(buildDashboardShareUrl('https://x/d/1', {from: '', to: ''}, {})).toBe('https://x/d/1')
  })
})
