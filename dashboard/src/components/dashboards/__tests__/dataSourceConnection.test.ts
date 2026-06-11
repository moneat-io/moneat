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
import type {CustomDataSourceResponse} from '@/lib/api'
import {
  buildEndpointPreview, buildExtraConfig, buildPayload, buildTestRequest,
  defaultFormState, effectiveName, hydrateFormState, isFormReady,
  smartSplitHost, validatePort,
} from '../dataSourceConnection'

function previewText(state: ReturnType<typeof defaultFormState>): string {
  return buildEndpointPreview(state).segments.map((s) => s.text).join('')
}

describe('smartSplitHost', () => {
  it('peels scheme, port and path out of a pasted URL', () => {
    expect(smartSplitHost('https://prom.example.com:9090/prometheus')).toEqual({
      scheme: 'https', host: 'prom.example.com', port: '9090', path: '/prometheus', didSplit: true,
    })
  })

  it('extracts a too-long port instead of choking on it', () => {
    const r = smartSplitHost('mydomain:80808080')
    expect(r.host).toBe('mydomain')
    expect(r.port).toBe('80808080')
    expect(validatePort(r.port!).ok).toBe(false)
  })

  it('strips embedded credentials', () => {
    const r = smartSplitHost('user:pass@db.internal:5432')
    expect(r.host).toBe('db.internal')
    expect(r.port).toBe('5432')
  })

  it('leaves a bare hostname untouched', () => {
    expect(smartSplitHost('plainhost.example.com')).toEqual({
      scheme: undefined, host: 'plainhost.example.com', port: undefined, path: undefined, didSplit: false,
    })
  })

  it('ignores a non-numeric port segment', () => {
    const r = smartSplitHost('host:notaport')
    expect(r.host).toBe('host:notaport')
    expect(r.port).toBeUndefined()
  })
})

describe('validatePort', () => {
  it('accepts empty (use default) and valid ports', () => {
    expect(validatePort('').ok).toBe(true)
    expect(validatePort('9090').ok).toBe(true)
    expect(validatePort('65535').ok).toBe(true)
  })

  it('rejects out-of-range and non-numeric ports', () => {
    expect(validatePort('0').ok).toBe(false)
    expect(validatePort('65536').ok).toBe(false)
    expect(validatePort('80808080').ok).toBe(false)
    expect(validatePort('abc').ok).toBe(false)
  })
})

describe('buildEndpointPreview', () => {
  it('builds a Postgres DSN with masked password', () => {
    const s = {...defaultFormState('postgresql'), host: 'db.example.com', database: 'app', username: 'svc', password: 'secret'}
    const p = buildEndpointPreview(s)
    expect(previewText(s)).toBe('postgresql://svc:••••@db.example.com:5432/app')
    expect(p.sub).toContain('PostgreSQL wire protocol')
    expect(p.sub).toContain('TLS: require')
  })

  it('shows the real API path and auth for an http source', () => {
    const s = {...defaultFormState('prometheus'), host: 'prom.example.com'}
    const p = buildEndpointPreview(s)
    expect(previewText(s)).toBe('https://prom.example.com:9090')
    expect(p.sub).toContain('Moneat calls /api/v1/query')
    expect(p.sub).toContain('auth: none')
    expect(p.portOk).toBe(true)
  })

  it('flags an invalid port', () => {
    const s = {...defaultFormState('prometheus'), host: 'h', port: '999999'}
    expect(buildEndpointPreview(s).portOk).toBe(false)
  })

  it('switches ClickHouse scheme by protocol', () => {
    const native = {...defaultFormState('clickhouse'), host: 'ch', chProtocol: 'native' as const, port: '9000'}
    expect(previewText(native)).toContain('clickhouse://')
    const httpProto = {...defaultFormState('clickhouse'), host: 'ch'}
    expect(previewText(httpProto)).toContain('http://ch')
  })
})

describe('buildExtraConfig', () => {
  it('captures http scheme, base path and auth method', () => {
    const s = {...defaultFormState('prometheus'), host: 'h', basePath: '/prometheus', authMethod: 'basic' as const}
    expect(buildExtraConfig(s)).toEqual({
      scheme: 'https', base_path: '/prometheus', auth_method: 'basic',
    })
  })

  it('captures sql tls mode', () => {
    expect(buildExtraConfig(defaultFormState('postgresql'))).toEqual({tls_mode: 'require'})
  })

  it('captures influx org/bucket/version', () => {
    const s = {...defaultFormState('influxdb'), org: 'acme', bucket: 'metrics'}
    expect(buildExtraConfig(s)).toMatchObject({influx_version: '2', org: 'acme', bucket: 'metrics', scheme: 'https'})
  })

  it('is empty in connection-string mode', () => {
    const s = {...defaultFormState('postgresql'), method: 'string' as const}
    expect(buildExtraConfig(s)).toEqual({})
  })
})

describe('buildPayload', () => {
  it('maps a guided Postgres form', () => {
    const s = {...defaultFormState('postgresql'), host: 'db.example.com', database: 'app', username: 'svc', password: 'pw'}
    const p = buildPayload(s)
    expect(p).toMatchObject({
      source_type: 'postgresql', host: 'db.example.com', port: 5432,
      database_name: 'app', username: 'svc', password: 'pw',
      extra_config: {tls_mode: 'require'},
    })
    expect(p.name).toBe('PostgreSQL · db.example.com')
  })

  it('maps a SQL connection string to the fields the backend uses', () => {
    const s = {
      ...defaultFormState('postgresql'),
      method: 'string' as const,
      connStr: 'postgresql://svc:p%40ss@db.example.com:15432/app',
    }
    const p = buildPayload(s)
    expect(p).toMatchObject({
      source_type: 'postgresql',
      host: 'db.example.com',
      port: 15432,
      database_name: 'app',
      username: 'svc',
      password: 'p@ss',
      connection_string: 'postgresql://svc:p%40ss@db.example.com:15432/app',
    })
    expect(p.name).toBe('PostgreSQL · db.example.com')
  })

  it('routes a bearer token to api_key for http sources', () => {
    const s = {...defaultFormState('prometheus'), host: 'h', authMethod: 'bearer' as const, token: 'tok'}
    const p = buildPayload(s)
    expect(p.api_key).toBe('tok')
    expect(p.extra_config?.auth_method).toBe('bearer')
  })

  it('routes a custom header value to header_value', () => {
    const s = {...defaultFormState('loki'), host: 'h', authMethod: 'header' as const, headerName: 'X-Scope-OrgID', headerValue: 'team-a'}
    const p = buildPayload(s)
    expect(p.header_value).toBe('team-a')
    expect(p.extra_config?.header_name).toBe('X-Scope-OrgID')
  })

  it('uses the instance role for cloudwatch and omits keys', () => {
    const s = {...defaultFormState('cloudwatch'), region: 'eu-west-1', useRole: true, accessKey: 'AKIA', secretKey: 'x'}
    const p = buildPayload(s)
    expect(p.region).toBe('eu-west-1')
    expect(p.access_key_id).toBeUndefined()
    expect(p.host).toBe('eu-west-1')
    expect(p.extra_config?.use_role).toBe('true')
  })

  it('sends a connection string and derives the host for mongodb', () => {
    const s = {...defaultFormState('mongodb'), connStr: 'mongodb+srv://u:p@cluster0.mongodb.net/app'}
    const p = buildPayload(s)
    expect(p.connection_string).toBe('mongodb+srv://u:p@cluster0.mongodb.net/app')
    expect(p.host).toBe('cluster0.mongodb.net')
  })

  it('maps a snowflake account to account_identifier', () => {
    const s = {...defaultFormState('snowflake'), account: 'xy12345.us-east-1', warehouse: 'WH', username: 'u', password: 'p'}
    const p = buildPayload(s)
    expect(p.account_identifier).toBe('xy12345.us-east-1')
    expect(p.host).toBe('xy12345.us-east-1')
    expect(p.port).toBeUndefined()
    expect(p.extra_config?.warehouse).toBe('WH')
  })
})

describe('buildTestRequest', () => {
  it('mirrors the payload connection fields', () => {
    const s = {...defaultFormState('prometheus'), host: 'h', basePath: '/p'}
    const t = buildTestRequest(s)
    expect(t.source_type).toBe('prometheus')
    expect(t.host).toBe('h')
    expect(t.extra_config?.base_path).toBe('/p')
  })

  it('includes custom header secrets when testing a connection', () => {
    const s = {
      ...defaultFormState('loki'),
      host: 'loki.example.com',
      authMethod: 'header' as const,
      headerName: 'X-Scope-OrgID',
      headerValue: 'tenant-a',
    }
    expect(buildTestRequest(s)).toMatchObject({
      header_value: 'tenant-a',
      extra_config: {auth_method: 'header', header_name: 'X-Scope-OrgID'},
    })
  })
})

describe('isFormReady', () => {
  it('requires the archetype minimums', () => {
    expect(isFormReady(defaultFormState('postgresql'))).toBe(false)
    expect(isFormReady({...defaultFormState('postgresql'), host: 'h'})).toBe(true)
    expect(isFormReady({...defaultFormState('sqlite'), host: '/db'})).toBe(true)
    expect(isFormReady({...defaultFormState('bigquery'), projectId: 'p'})).toBe(true)
    expect(isFormReady({...defaultFormState('snowflake'), account: 'a', warehouse: 'w'})).toBe(true)
    expect(isFormReady({...defaultFormState('cloudwatch'), useRole: true})).toBe(true)
  })

  it('blocks on a bad port and on a missing connection string', () => {
    expect(isFormReady({...defaultFormState('postgresql'), host: 'h', port: '999999'})).toBe(false)
    expect(isFormReady({...defaultFormState('postgresql'), method: 'string'})).toBe(false)
    expect(isFormReady({...defaultFormState('postgresql'), method: 'string', connStr: 'postgresql://h/db'})).toBe(true)
  })
})

describe('effectiveName', () => {
  it('auto-generates from label and host when blank', () => {
    expect(effectiveName({...defaultFormState('prometheus'), host: 'prom'})).toBe('Prometheus · prom')
  })
  it('respects a user-provided name', () => {
    expect(effectiveName({...defaultFormState('prometheus'), name: 'My Prom'})).toBe('My Prom')
  })
})

describe('hydrateFormState', () => {
  it('rebuilds form state from a stored source + extra_config', () => {
    const ds: CustomDataSourceResponse = {
      id: '00000000-0000-0000-0000-000000000005', org_id: 1, name: 'Edge Prom', source_type: 'prometheus',
      host: 'prom.example.com', port: 9090, extra_config: {scheme: 'http', base_path: '/p', auth_method: 'bearer'},
      enabled: true, created_by: 1, created_at: '', updated_at: '', has_credentials: true,
    }
    const s = hydrateFormState(ds)
    expect(s.vendor).toBe('prometheus')
    expect(s.host).toBe('prom.example.com')
    expect(s.scheme).toBe('http')
    expect(s.basePath).toBe('/p')
    expect(s.authMethod).toBe('bearer')
  })
})
