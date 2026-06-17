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

// Pure connection logic for the Add-data-source dialog: form state, the smart
// host paste-splitter, port validation, the live endpoint preview, and the
// mapping from form state to the API payload (structured config rides in
// extra_config; secrets ride in the dedicated credential fields).

import type {
  CreateCustomDataSourceRequest,
  CustomDataSourceResponse,
  TestConnectionRequest,
  UpdateCustomDataSourceRequest,
} from '@/lib/api'
import {
  type AuthMethod,
  type ConnectionMethod,
  type VendorDef,
  getVendor,
} from './dataSourceCatalog'

export interface DsFormState {
  vendor: string
  method: ConnectionMethod
  name: string
  description: string
  // shared host/port
  host: string
  port: string
  database: string
  username: string
  password: string
  // http
  scheme: 'http' | 'https'
  basePath: string
  authMethod: AuthMethod
  token: string
  headerName: string
  headerValue: string
  // influx
  influxVersion: '1' | '2'
  org: string
  bucket: string
  // clickhouse
  chProtocol: 'http' | 'native'
  // connection string
  connStr: string
  manual: boolean
  // sql advanced
  tlsMode: string
  timeout: string
  schema: string
  // bigquery
  projectId: string
  serviceAccount: string
  // snowflake
  account: string
  warehouse: string
  role: string
  // cloudwatch
  region: string
  useRole: boolean
  accessKey: string
  secretKey: string
}

const BLANK: DsFormState = {
  vendor: '', method: 'guided', name: '', description: '',
  host: '', port: '', database: '', username: '', password: '',
  scheme: 'https', basePath: '', authMethod: 'none', token: '', headerName: '', headerValue: '',
  influxVersion: '2', org: '', bucket: '',
  chProtocol: 'http',
  connStr: '', manual: false,
  tlsMode: 'require', timeout: '', schema: '',
  projectId: '', serviceAccount: '',
  account: '', warehouse: '', role: '',
  region: 'us-east-1', useRole: false, accessKey: '', secretKey: '',
}

const HOST_FIELD_ARCHES = new Set<VendorDef['arch']>(['sql', 'clickhouse', 'http', 'influx', 'connstr'])
const PORT_FIELD_ARCHES = new Set<VendorDef['arch']>(['sql', 'clickhouse', 'http', 'influx', 'connstr'])

export function defaultFormState(vendorKey: string): DsFormState {
  const v = getVendor(vendorKey)
  return {
    ...BLANK,
    vendor: vendorKey,
    scheme: v?.arch === 'clickhouse' ? 'http' : 'https',
    port: v?.port ? String(v.port) : '',
    tlsMode: v?.arch === 'sql' ? 'require' : '',
  }
}

/** Re-hydrate the form when editing an existing source (secrets stay blank). */
export function hydrateFormState(ds: CustomDataSourceResponse): DsFormState {
  const x = ds.extra_config ?? {}
  const base = defaultFormState(ds.source_type)
  const v = getVendor(ds.source_type)
  const host = sourceUsesHostField(v) ? ds.host : ''
  return {
    ...base,
    name: ds.name,
    description: ds.description ?? '',
    host,
    // `== null` also catches an explicit JSON `port: null` (the API encodes nulls),
    // so a portless source hydrates to a blank field rather than the string "null".
    port: ds.port == null ? base.port : String(ds.port),
    database: ds.database_name ?? '',
    scheme: x.scheme === 'http' ? 'http' : 'https',
    basePath: x.base_path ?? '',
    authMethod: (x.auth_method as AuthMethod) ?? 'none',
    headerName: x.header_name ?? '',
    influxVersion: x.influx_version === '1' ? '1' : '2',
    org: x.org ?? '',
    bucket: x.bucket ?? '',
    chProtocol: x.ch_protocol === 'native' ? 'native' : 'http',
    tlsMode: x.tls_mode ?? base.tlsMode,
    timeout: x.timeout ?? '',
    schema: x.schema ?? '',
    warehouse: x.warehouse ?? '',
    role: x.role ?? '',
    projectId: ds.host && v?.arch === 'bigquery' ? ds.host : '',
    account: ds.host && v?.arch === 'snowflake' ? ds.host : '',
    region: v?.arch === 'cloudwatch' ? ds.host || x.region || 'us-east-1' : 'us-east-1',
    useRole: x.use_role === 'true',
  }
}

function sourceUsesHostField(vendor: VendorDef | undefined): boolean {
  return vendor === undefined || HOST_FIELD_ARCHES.has(vendor.arch)
}

// ----- smart host paste-splitting -------------------------------------------

export interface HostSplit {
  scheme?: 'http' | 'https'
  host: string
  port?: string
  path?: string
  didSplit: boolean
}

const SCHEME_PATTERN = /^([a-z][a-z0-9+.-]*):\/\//i

/**
 * Pulls a scheme, port, path or user:pass@ out of a value pasted into the Host
 * field so the host field only ever holds a bare hostname. Fixes the classic
 * "I pasted https://host:9090/path and it errored" trap.
 */
export function smartSplitHost(raw: string): HostSplit {
  let host = (raw ?? '').trim()
  let scheme: 'http' | 'https' | undefined
  let port: string | undefined
  let path: string | undefined
  let didSplit = false

  const schemeMatch = SCHEME_PATTERN.exec(host)
  if (schemeMatch) {
    const s = schemeMatch[1].toLowerCase()
    if (s === 'http' || s === 'https') scheme = s
    host = host.slice(schemeMatch[0].length)
    didSplit = true
  }
  const at = host.lastIndexOf('@')
  if (at >= 0) {
    host = host.slice(at + 1)
    didSplit = true
  }
  const slash = host.indexOf('/')
  if (slash >= 0) {
    path = host.slice(slash)
    host = host.slice(0, slash)
    didSplit = true
  }
  const colon = host.lastIndexOf(':')
  if (colon >= 0) {
    const isBracketedIpv6 = host.startsWith('[') && host.includes(']')
    const looksLikeBareIpv6 = !isBracketedIpv6 && countChars(host, ':') > 1
    const p = host.slice(colon + 1)
    if (!looksLikeBareIpv6 && /^\d+$/.test(p)) {
      port = p
      host = host.slice(0, colon)
      didSplit = true
    }
  }
  return {scheme, host, port, path, didSplit}
}

function countChars(value: string, char: string): number {
  let count = 0
  for (const current of value) {
    if (current === char) count += 1
  }
  return count
}

export interface PortValidity {
  ok: boolean
  message?: string
}

export function validatePort(value: string): PortValidity {
  const v = (value ?? '').trim()
  if (v === '') return {ok: true}
  if (!/^\d+$/.test(v) || Number(v) < 1 || Number(v) > 65535) {
    return {ok: false, message: 'Port must be a number between 1 and 65535.'}
  }
  return {ok: true}
}

// ----- live endpoint preview ------------------------------------------------

export type PreviewTone =
  | 'scheme' | 'user' | 'pw' | 'host' | 'port' | 'path' | 'db' | 'muted'
export interface PreviewSeg {
  tone: PreviewTone
  text: string
}
export interface EndpointPreview {
  segments: PreviewSeg[]
  sub: string
  portOk: boolean
}

function resolvedPort(state: DsFormState, fallback?: number): string {
  if (state.port && /^\d+$/.test(state.port)) return state.port
  return fallback === undefined ? '' : String(fallback)
}

function maskUri(uri: string): PreviewSeg[] {
  // Mask a password embedded in a connection string for display only.
  return [{tone: 'host', text: uri.replace(/:([^:@/]+)@/, ':••••@')}]
}

interface ParsedConnectionUri {
  readonly host?: string
  readonly port?: number
  readonly database?: string
  readonly username?: string
  readonly password?: string
}

function decodeUriPart(value: string): string {
  try {
    return decodeURIComponent(value)
  } catch {
    return value
  }
}

function parseConnectionUri(raw: string): ParsedConnectionUri {
  const value = raw.trim()
  if (!value) return {}
  try {
    const parsed = new URL(value)
    const database = decodeUriPart(parsed.pathname.replace(/^\/+/, ''))
    return {
      host: parsed.hostname || undefined,
      port: parsed.port ? Number(parsed.port) : undefined,
      database: database || undefined,
      username: parsed.username ? decodeUriPart(parsed.username) : undefined,
      password: parsed.password ? decodeUriPart(parsed.password) : undefined,
    }
  } catch {
    const split = smartSplitHost(value.replace(/^[a-z+]+:\/\//i, ''))
    const database = split.path?.replace(/^\/+/, '')
    return {
      host: split.host || undefined,
      port: split.port ? Number(split.port) : undefined,
      database: database || undefined,
    }
  }
}

export function buildEndpointPreview(state: DsFormState): EndpointPreview {
  const v = getVendor(state.vendor)
  const portOk = validatePort(state.port).ok
  if (!v) return {segments: [{tone: 'muted', text: '—'}], sub: '', portOk}

  const host = state.host || '⟨host⟩'
  const port = resolvedPort(state, v.port)
  const preview = buildPreviewBody({state, vendor: v, host, port})
  return {...preview, portOk}
}

type PreviewContext = Readonly<{
  state: DsFormState
  vendor: VendorDef
  host: string
  port: string
}>

type PreviewBody = Readonly<Pick<EndpointPreview, 'segments' | 'sub'>>

function buildPreviewBody(context: PreviewContext): PreviewBody {
  if (context.state.method === 'string') return connectionStringPreview(context.state)

  switch (context.vendor.arch) {
    case 'sql': return sqlPreview(context)
    case 'clickhouse': return clickhousePreview(context)
    case 'http': return httpPreview(context)
    case 'influx': return influxPreview(context)
    case 'file': return filePreview(context.state)
    case 'connstr': return connectionStringSourcePreview(context)
    case 'bigquery': return bigQueryPreview(context.state)
    case 'snowflake': return snowflakePreview(context.state)
    case 'cloudwatch': return cloudWatchPreview(context.state)
    default: return {segments: [], sub: ''}
  }
}

function connectionStringPreview(state: DsFormState): PreviewBody {
  return {
    segments: state.connStr ? maskUri(state.connStr) : [{tone: 'pw', text: 'paste a connection string above'}],
    sub: 'Parsed on save · credentials read from the URI',
  }
}

function sqlPreview({state, vendor, host, port}: PreviewContext): PreviewBody {
  const userSegments = state.username
    ? [
        {tone: 'user' as const, text: state.username},
        {tone: 'pw' as const, text: state.password ? ':••••' : ''},
        {tone: 'user' as const, text: '@'},
      ]
    : []
  const noteSuffix = vendor.note ? ` · ${vendor.note}` : ''
  const scheme = vendor.scheme === 'postgresql' ? 'PostgreSQL' : vendor.scheme
  return {
    segments: [
      {tone: 'scheme', text: `${vendor.scheme}://`},
      ...userSegments,
      {tone: 'host', text: host},
      {tone: 'port', text: `:${port}`},
      {tone: 'path', text: '/'},
      {tone: 'db', text: state.database || 'database'},
    ],
    sub: `${scheme} wire protocol · TLS: ${state.tlsMode || 'off'}${noteSuffix}`,
  }
}

function clickhousePreview({state, host}: PreviewContext): PreviewBody {
  const native = state.chProtocol === 'native'
  const userSegments = state.username ? [{tone: 'user' as const, text: `${state.username}@`}] : []
  return {
    segments: [
      {tone: 'scheme', text: native ? 'clickhouse://' : `${state.scheme}://`},
      ...userSegments,
      {tone: 'host', text: host},
      {tone: 'port', text: `:${resolvedPort(state, native ? 9000 : 8123)}`},
      {tone: 'path', text: '/'},
      {tone: 'db', text: state.database || 'default'},
    ],
    sub: native ? 'Native TCP protocol' : 'HTTP interface',
  }
}

function httpPreview({state, vendor, host}: PreviewContext): PreviewBody {
  const base = state.basePath && state.basePath !== '/' ? state.basePath.replace(/\/$/, '') : ''
  const pathSegments = base ? [{tone: 'path' as const, text: base}] : []
  // HTTP sources can sit behind a reverse proxy on the scheme's default port, so a
  // blank field means "no port" — show nothing rather than a vendor fallback or a
  // stray colon (e.g. https://prometheus.example.com, not …:9090 or …:).
  const explicitPort = state.port.trim()
  const portSegments = explicitPort ? [{tone: 'port' as const, text: `:${explicitPort}`}] : []
  return {
    segments: [
      {tone: 'scheme', text: `${state.scheme}://`},
      {tone: 'host', text: host},
      ...portSegments,
      ...pathSegments,
    ],
    sub: `Moneat calls ${base}${vendor.apiPath} · auth: ${authLabel(state)}`,
  }
}

function influxPreview({state, host}: PreviewContext): PreviewBody {
  const sub = state.influxVersion === '2'
    ? `v2 Flux · org ${state.org || '⟨org⟩'} · bucket ${state.bucket || '⟨bucket⟩'} · token auth`
    : `v1 InfluxQL · db ${state.database || '⟨db⟩'}`
  return {
    segments: [
      {tone: 'scheme', text: `${state.scheme}://`},
      {tone: 'host', text: host},
      {tone: 'port', text: `:${resolvedPort(state, 8086)}`},
    ],
    sub,
  }
}

function filePreview(state: DsFormState): PreviewBody {
  return {
    segments: [{tone: 'scheme', text: 'file:'}, {tone: 'path', text: state.host || '/path/to/database.db'}],
    sub: 'Local file on the Moneat server',
  }
}

function connectionStringSourcePreview({state, vendor, host}: PreviewContext): PreviewBody {
  if (!state.manual) {
    return {
      segments: state.connStr ? maskUri(state.connStr) : [{tone: 'pw', text: vendor.stringPlaceholder ?? ''}],
      sub: 'Parsed on save · credentials read from the URI',
    }
  }
  const userSegments = state.username
    ? [{tone: 'user' as const, text: `${state.username}${state.password ? ':••••' : ''}@`}]
    : []
  const databaseSegments = state.database ? [{tone: 'db' as const, text: `/${state.database}`}] : []
  return {
    segments: [
      {tone: 'scheme', text: `${vendor.scheme}://`},
      ...userSegments,
      {tone: 'host', text: host},
      {tone: 'port', text: `:${resolvedPort(state, vendor.port)}`},
      ...databaseSegments,
    ],
    sub: 'Built from host & port',
  }
}

function bigQueryPreview(state: DsFormState): PreviewBody {
  const databaseSegments = state.database ? [{tone: 'db' as const, text: `/${state.database}`}] : []
  return {
    segments: [
      {tone: 'scheme', text: 'bigquery://'},
      {tone: 'host', text: state.projectId || '⟨project⟩'},
      ...databaseSegments,
    ],
    sub: 'Authenticated with a service-account key',
  }
}

function snowflakePreview(state: DsFormState): PreviewBody {
  return {
    segments: [
      {tone: 'scheme', text: 'https://'},
      {tone: 'host', text: state.account || '⟨account⟩'},
      {tone: 'path', text: '.snowflakecomputing.com'},
    ],
    sub: `warehouse ${state.warehouse || '⟨wh⟩'} · db ${state.database || '⟨db⟩'}`,
  }
}

function cloudWatchPreview(state: DsFormState): PreviewBody {
  return {
    segments: [{tone: 'scheme', text: 'cloudwatch://'}, {tone: 'host', text: state.region || 'us-east-1'}],
    sub: state.useRole ? 'Instance role / default credential chain' : 'Static access keys',
  }
}

export function authLabel(state: DsFormState): string {
  switch (state.authMethod) {
    case 'basic': return 'basic'
    case 'bearer': return 'bearer token'
    case 'header': return state.headerName || 'custom header'
    default: return 'none'
  }
}

// ----- payload mapping ------------------------------------------------------

function put(target: Record<string, string>, key: string, value: string | undefined) {
  if (value && value.trim() !== '') target[key] = value.trim()
}

/** Non-secret structured connection config, persisted in extra_config. */
export function buildExtraConfig(state: DsFormState): Record<string, string> {
  const v = getVendor(state.vendor)
  const x: Record<string, string> = {}
  if (!v || state.method === 'string') return x
  switch (v.arch) {
    case 'sql':
      put(x, 'tls_mode', state.tlsMode)
      put(x, 'timeout', state.timeout)
      put(x, 'schema', state.schema)
      break
    case 'clickhouse':
      put(x, 'ch_protocol', state.chProtocol)
      break
    case 'http':
      put(x, 'scheme', state.scheme)
      put(x, 'base_path', state.basePath)
      put(x, 'auth_method', state.authMethod === 'none' ? '' : state.authMethod)
      put(x, 'header_name', state.authMethod === 'header' ? state.headerName : '')
      put(x, 'timeout', state.timeout)
      break
    case 'influx':
      put(x, 'scheme', state.scheme)
      put(x, 'influx_version', state.influxVersion)
      put(x, 'org', state.org)
      put(x, 'bucket', state.bucket)
      break
    case 'snowflake':
      put(x, 'warehouse', state.warehouse)
      put(x, 'role', state.role)
      put(x, 'schema', state.schema)
      break
    case 'cloudwatch':
      if (state.useRole) x.use_role = 'true'
      break
    default:
      break
  }
  return x
}

/** The display name to persist (falls back to an auto-generated one). */
export function effectiveName(state: DsFormState): string {
  if (state.name.trim()) return state.name.trim()
  const v = getVendor(state.vendor)
  const label = v?.label ?? state.vendor
  const parsedConnection = state.method === 'string' ? parseConnectionUri(state.connStr) : {}
  const hostLike =
    state.host || parsedConnection.host || state.projectId || state.account || state.region || ''
  return hostLike ? `${label} · ${hostLike}` : label
}

function payloadHost(state: DsFormState, v: VendorDef): string {
  switch (v.arch) {
    case 'file': return state.host
    case 'bigquery': return state.projectId
    case 'snowflake': return state.account
    case 'cloudwatch': return state.region || 'us-east-1'
    case 'connstr':
      if (state.manual) return state.host
      return smartSplitHost(state.connStr.replace(/^[a-z+]+:\/\//i, '')).host || state.host || 'remote'
    default:
      if (state.method === 'string') {
        return parseConnectionUri(state.connStr).host || 'remote'
      }
      return state.host
  }
}

function payloadPort(state: DsFormState, v: VendorDef): number | undefined {
  if (v.arch === 'clickhouse') {
    return state.chProtocol === 'native' ? Number(resolvedPort(state, 9000)) : Number(resolvedPort(state, 8123))
  }
  if (state.method === 'string' && v.arch === 'sql') {
    return parseConnectionUri(state.connStr).port ?? v.port
  }
  if (state.port && /^\d+$/.test(state.port)) return Number(state.port)
  // HTTP sources keep the port optional (reverse-proxy friendly); every other
  // archetype falls back to its conventional wire-protocol port.
  if (v.arch === 'http') return undefined
  return v.port
}

/** Map the form to the create/update API request. */
export function buildPayload(state: DsFormState): CreateCustomDataSourceRequest & {header_value?: string} {
  const v = getVendor(state.vendor)
  if (!v) throw new Error(`Unknown source type: ${state.vendor}`)

  const req: CreateCustomDataSourceRequest & {header_value?: string} = {
    name: effectiveName(state),
    source_type: state.vendor,
    host: payloadHost(state, v),
  }

  addDescription(req, state)
  addPort(req, state, v)
  addDatabase(req, state, v)
  addConnectionString(req, state, v)
  addCredentials(req, state, v)
  addExtraConfig(req, state)
  return req
}

/**
 * Map the form to the update API request. Identical to {@link buildPayload}, but
 * when a port-bearing source intentionally leaves the port blank (an http source
 * behind a reverse proxy) it asks the backend to clear any previously stored port —
 * a plain omitted `port` is a partial-update no-op and could not erase the old value.
 */
export function buildUpdatePayload(state: DsFormState): UpdateCustomDataSourceRequest & {header_value?: string} {
  const payload: UpdateCustomDataSourceRequest & {header_value?: string} = buildPayload(state)
  const v = getVendor(state.vendor)
  if (v && payload.port == null && PORT_FIELD_ARCHES.has(v.arch)) {
    payload.clear_port = true
  }
  return payload
}

type DataSourcePayload = CreateCustomDataSourceRequest & {header_value?: string}

function addDescription(req: DataSourcePayload, state: DsFormState) {
  const description = state.description.trim()
  if (description) req.description = description
}

function addPort(req: DataSourcePayload, state: DsFormState, vendor: VendorDef) {
  const port = payloadPort(state, vendor)
  if (port != null && shouldPersistPort(vendor)) req.port = port
}

function shouldPersistPort(vendor: VendorDef): boolean {
  return PORT_FIELD_ARCHES.has(vendor.arch)
}

function addDatabase(req: DataSourcePayload, state: DsFormState, vendor: VendorDef) {
  const parsedConnection = parsedSqlConnection(state, vendor)
  if (parsedConnection.database) {
    req.database_name = parsedConnection.database
    return
  }
  if (vendor.arch === 'influx' && state.influxVersion === '1') {
    req.database_name = state.database || undefined
    return
  }
  if (state.database && vendor.arch !== 'file') req.database_name = state.database
}

function addConnectionString(req: DataSourcePayload, state: DsFormState, vendor: VendorDef) {
  const shouldPersist = state.method === 'string' || (vendor.arch === 'connstr' && !state.manual)
  if (shouldPersist && state.connStr) req.connection_string = state.connStr
}

function addCredentials(req: DataSourcePayload, state: DsFormState, vendor: VendorDef) {
  switch (vendor.arch) {
    case 'bigquery':
      addBigQueryCredentials(req, state)
      break
    case 'cloudwatch':
      addCloudWatchCredentials(req, state)
      break
    case 'snowflake':
      addSnowflakeCredentials(req, state)
      break
    case 'influx':
      addInfluxCredentials(req, state)
      break
    case 'http':
      addHttpCredentials(req, state)
      break
    case 'sql':
      addSqlCredentials(req, state, vendor)
      break
    case 'clickhouse':
    case 'connstr':
      addManualCredentials(req, state, vendor)
      break
    default:
      break
  }
}

function addBigQueryCredentials(req: DataSourcePayload, state: DsFormState) {
  if (state.projectId) req.project_id = state.projectId
  if (state.serviceAccount) req.service_account_json = state.serviceAccount
}

function addCloudWatchCredentials(req: DataSourcePayload, state: DsFormState) {
  req.region = state.region || 'us-east-1'
  if (state.useRole) return
  if (state.accessKey) req.access_key_id = state.accessKey
  if (state.secretKey) req.secret_access_key = state.secretKey
}

function addSnowflakeCredentials(req: DataSourcePayload, state: DsFormState) {
  if (state.account) req.account_identifier = state.account
  if (state.username) req.username = state.username
  if (state.password) req.password = state.password
}

function addInfluxCredentials(req: DataSourcePayload, state: DsFormState) {
  if (state.influxVersion === '2') {
    if (state.token) req.api_key = state.token
    return
  }
  if (state.username) req.username = state.username
  if (state.password) req.password = state.password
}

function addHttpCredentials(req: DataSourcePayload, state: DsFormState) {
  if (state.authMethod === 'basic') {
    if (state.username) req.username = state.username
    if (state.password) req.password = state.password
    return
  }
  if (state.authMethod === 'bearer') {
    if (state.token) req.api_key = state.token
    return
  }
  if (state.authMethod === 'header' && state.headerValue) req.header_value = state.headerValue
}

function addSqlCredentials(req: DataSourcePayload, state: DsFormState, vendor: VendorDef) {
  if (state.method === 'string') {
    const parsedConnection = parsedSqlConnection(state, vendor)
    if (parsedConnection.username) req.username = parsedConnection.username
    if (parsedConnection.password) req.password = parsedConnection.password
    return
  }
  addManualCredentials(req, state, vendor)
}

function addManualCredentials(req: DataSourcePayload, state: DsFormState, vendor: VendorDef) {
  const useManual = vendor.arch === 'connstr' ? state.manual : state.method !== 'string'
  if (!useManual) return
  if (state.username) req.username = state.username
  if (state.password) req.password = state.password
}

function parsedSqlConnection(state: DsFormState, vendor: VendorDef): ParsedConnectionUri {
  if (state.method === 'string' && vendor.arch === 'sql') return parseConnectionUri(state.connStr)
  return {}
}

function addExtraConfig(req: DataSourcePayload, state: DsFormState) {
  const extra = buildExtraConfig(state)
  if (Object.keys(extra).length > 0) req.extra_config = extra
}

/** The test-connection request mirrors the payload's connection-relevant bits. */
export function buildTestRequest(state: DsFormState): TestConnectionRequest & {extra_config?: Record<string, string>} {
  const p = buildPayload(state)
  return {
    source_type: p.source_type,
    host: p.host,
    port: p.port,
    database_name: p.database_name,
    username: p.username,
    password: p.password,
    api_key: p.api_key,
    header_value: p.header_value,
    access_key_id: p.access_key_id,
    secret_access_key: p.secret_access_key,
    service_account_json: p.service_account_json,
    account_identifier: p.account_identifier,
    connection_string: p.connection_string,
    project_id: p.project_id,
    region: p.region,
    extra_config: p.extra_config,
  }
}

/** Whether the minimum fields for this archetype are present. */
export function isFormReady(state: DsFormState): boolean {
  const v = getVendor(state.vendor)
  if (!v) return false
  if (!validatePort(state.port).ok) return false
  if (state.method === 'string') return state.connStr.trim() !== ''
  switch (v.arch) {
    case 'file': return state.host.trim() !== ''
    case 'bigquery': return state.projectId.trim() !== ''
    case 'cloudwatch': return state.useRole || (state.accessKey.trim() !== '' && state.secretKey.trim() !== '')
    case 'snowflake': return state.account.trim() !== '' && state.warehouse.trim() !== ''
    case 'connstr': return state.manual ? state.host.trim() !== '' : state.connStr.trim() !== ''
    default: return state.host.trim() !== ''
  }
}
