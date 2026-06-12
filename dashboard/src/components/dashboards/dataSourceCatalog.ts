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

// Pure catalog of the data-source types Moneat can connect to, grouped by
// "archetype" — the connection shape that drives which fields the configure
// step renders. No JSX lives here so the file stays fast-refresh friendly.

export type SourceArch =
  | 'sql'
  | 'clickhouse'
  | 'http'
  | 'influx'
  | 'file'
  | 'connstr'
  | 'bigquery'
  | 'snowflake'
  | 'cloudwatch'

export type SourceCategory = 'database' | 'metrics' | 'search' | 'nosql' | 'cloud'
export type ConnectionMethod = 'guided' | 'string'
export type AuthMethod = 'none' | 'basic' | 'bearer' | 'header'

export interface VendorDef {
  readonly key: string
  readonly label: string
  readonly category: SourceCategory
  readonly arch: SourceArch
  readonly port?: number
  /** DSN scheme used in the endpoint preview for sql / connstr sources. */
  readonly scheme?: string
  /** API path Moneat appends for http sources (e.g. /api/v1/query). */
  readonly apiPath?: string
  readonly blurb: string
  readonly dbPlaceholder?: string
  readonly userPlaceholder?: string
  readonly note?: string
  /** Whether a "Connection string" method tab is offered. */
  readonly supportsString?: boolean
  readonly stringPlaceholder?: string
  /** Elasticsearch-style index/data-view field. */
  readonly indexField?: string
  readonly indexPlaceholder?: string
}

export const CATEGORY_LABELS: Record<SourceCategory, string> = {
  database: 'Databases',
  metrics: 'Metrics & time-series',
  search: 'Search & logs',
  nosql: 'NoSQL',
  cloud: 'Cloud',
}

export const CATEGORY_ORDER: readonly SourceCategory[] = [
  'database',
  'metrics',
  'search',
  'nosql',
  'cloud',
]

/** Monogram + brand colour for each source's logo tile. */
export const SOURCE_LOGO: Record<string, {mono: string; bg: string; fg?: string}> = {
  postgresql: {mono: 'PG', bg: '#336791'},
  mysql: {mono: 'My', bg: '#00758F'},
  mariadb: {mono: 'Ma', bg: '#9c5a44'},
  mssql: {mono: 'MS', bg: '#CC2927'},
  clickhouse: {mono: 'CH', bg: '#c9a400', fg: '#1c1c1c'},
  sqlite: {mono: 'SL', bg: '#0a5c86'},
  cockroachdb: {mono: 'CR', bg: '#6933FF'},
  prometheus: {mono: 'Pr', bg: '#E6522C'},
  influxdb: {mono: 'In', bg: '#22ADF6'},
  graphite: {mono: 'Gr', bg: '#3a3a3a'},
  elasticsearch: {mono: 'ES', bg: '#0b8e86'},
  loki: {mono: 'Lk', bg: '#F26022'},
  mongodb: {mono: 'Mn', bg: '#3e8e3a'},
  redis: {mono: 'Rd', bg: '#DC382D'},
  bigquery: {mono: 'BQ', bg: '#4285F4'},
  snowflake: {mono: 'Sn', bg: '#1f9fd0'},
  cloudwatch: {mono: 'CW', bg: '#d97800'},
}

export const VENDORS: readonly VendorDef[] = [
  // Databases
  {key: 'postgresql', label: 'PostgreSQL', category: 'database', arch: 'sql', port: 5432, scheme: 'postgresql', blurb: 'Open-source relational DB', dbPlaceholder: 'database', userPlaceholder: 'postgres', supportsString: true},
  {key: 'mysql', label: 'MySQL', category: 'database', arch: 'sql', port: 3306, scheme: 'mysql', blurb: 'Popular relational DB', dbPlaceholder: 'database', userPlaceholder: 'root', supportsString: true},
  {key: 'mariadb', label: 'MariaDB', category: 'database', arch: 'sql', port: 3306, scheme: 'mariadb', blurb: 'MySQL-compatible DB', dbPlaceholder: 'database', userPlaceholder: 'root', supportsString: true},
  {key: 'mssql', label: 'SQL Server', category: 'database', arch: 'sql', port: 1433, scheme: 'sqlserver', blurb: 'Microsoft SQL Server', dbPlaceholder: 'database', userPlaceholder: 'sa', supportsString: true},
  {key: 'clickhouse', label: 'ClickHouse', category: 'database', arch: 'clickhouse', port: 8123, blurb: 'Fast OLAP database', dbPlaceholder: 'default', userPlaceholder: 'default'},
  {key: 'cockroachdb', label: 'CockroachDB', category: 'database', arch: 'sql', port: 26257, scheme: 'postgresql', blurb: 'Distributed SQL · PG wire', dbPlaceholder: 'defaultdb', userPlaceholder: 'root', note: 'Speaks the PostgreSQL wire protocol.', supportsString: true},
  {key: 'sqlite', label: 'SQLite', category: 'database', arch: 'file', blurb: 'Embedded SQL database'},
  // Metrics & time-series
  {key: 'prometheus', label: 'Prometheus', category: 'metrics', arch: 'http', port: 9090, apiPath: '/api/v1/query', blurb: 'Metrics & monitoring', supportsString: true},
  {key: 'influxdb', label: 'InfluxDB', category: 'metrics', arch: 'influx', port: 8086, blurb: 'Time-series database'},
  {key: 'graphite', label: 'Graphite', category: 'metrics', arch: 'http', port: 80, apiPath: '/render', blurb: 'Metrics storage & graphing', supportsString: true},
  // Search & logs
  {key: 'elasticsearch', label: 'Elasticsearch', category: 'search', arch: 'http', port: 9200, apiPath: '/_search', indexField: 'Index / data view', indexPlaceholder: 'logs-*', blurb: 'Search & analytics', supportsString: true},
  {key: 'loki', label: 'Loki', category: 'search', arch: 'http', port: 3100, apiPath: '/loki/api/v1/query_range', blurb: 'Log aggregation', supportsString: true},
  // NoSQL
  {key: 'mongodb', label: 'MongoDB', category: 'nosql', arch: 'connstr', port: 27017, scheme: 'mongodb', stringPlaceholder: 'mongodb+srv://user:pass@cluster0.mongodb.net/mydb', dbPlaceholder: 'database', blurb: 'Document database'},
  {key: 'redis', label: 'Redis', category: 'nosql', arch: 'connstr', port: 6379, scheme: 'redis', stringPlaceholder: 'redis://default:pass@host:6379/0', dbPlaceholder: 'db index', blurb: 'In-memory data store'},
  // Cloud
  {key: 'bigquery', label: 'BigQuery', category: 'cloud', arch: 'bigquery', blurb: 'Google Cloud data warehouse'},
  {key: 'snowflake', label: 'Snowflake', category: 'cloud', arch: 'snowflake', port: 443, blurb: 'Cloud data platform'},
  {key: 'cloudwatch', label: 'CloudWatch', category: 'cloud', arch: 'cloudwatch', blurb: 'AWS monitoring & metrics'},
]

export const AWS_REGIONS: readonly string[] = [
  'us-east-1', 'us-east-2', 'us-west-1', 'us-west-2',
  'eu-west-1', 'eu-west-2', 'eu-central-1',
  'ap-southeast-1', 'ap-southeast-2', 'ap-northeast-1', 'ap-south-1',
  'sa-east-1', 'ca-central-1',
]

const VENDOR_BY_KEY: Record<string, VendorDef> = Object.fromEntries(
  VENDORS.map((v) => [v.key, v])
)

export function getVendor(key: string | undefined | null): VendorDef | undefined {
  return key ? VENDOR_BY_KEY[key] : undefined
}

export interface VendorGroup {
  category: SourceCategory
  label: string
  vendors: VendorDef[]
}

/** Vendors grouped by category, optionally filtered by a free-text query. */
export function groupVendors(query = ''): VendorGroup[] {
  const q = query.trim().toLowerCase()
  return CATEGORY_ORDER.map((category) => {
    const vendors = VENDORS.filter(
      (v) =>
        v.category === category &&
        (q === '' ||
          v.key.includes(q) ||
          v.label.toLowerCase().includes(q) ||
          v.blurb.toLowerCase().includes(q))
    )
    return {category, label: CATEGORY_LABELS[category], vendors}
  }).filter((g) => g.vendors.length > 0)
}
