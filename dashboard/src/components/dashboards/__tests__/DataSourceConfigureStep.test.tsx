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
import {fireEvent, render, screen, waitFor} from '@testing-library/react'
import {describe, expect, it, vi} from 'vitest'
import type {TestConnectionResult} from '@/lib/api'
import {DataSourceConfigureStep} from '../DataSourceConfigureStep'
import {DataSourcePickerStep} from '../DataSourcePickerStep'
import {getVendor} from '../dataSourceCatalog'
import {type DsFormState, defaultFormState} from '../dataSourceConnection'

function renderConfigureStep(
  vendorKey: string,
  initial: Partial<DsFormState> = {},
  testResult: TestConnectionResult | null = null,
  testing = false,
) {
  const onBack = vi.fn()
  const onTest = vi.fn()

  function Harness() {
    const vendor = getVendor(vendorKey)
    if (!vendor) throw new Error(`unknown vendor ${vendorKey}`)
    const [state, setState] = useState<DsFormState>(() => ({
      ...defaultFormState(vendorKey),
      ...initial,
    }))

    return (
      <DataSourceConfigureStep
        state={state}
        vendor={vendor}
        editing={false}
        update={(patch) => setState((current) => ({...current, ...patch}))}
        onBack={onBack}
        testResult={testResult}
        testing={testing}
        onTest={onTest}
      />
    )
  }

  const result = render(<Harness />)
  return {...result, onBack, onTest}
}

describe('DataSourceConfigureStep', () => {
  it('renders InfluxDB v2 fields and switches to v1 credential fields', () => {
    renderConfigureStep('influxdb')

    expect(screen.getByText('InfluxDB version')).toBeInTheDocument()
    expect(screen.getByLabelText('Organization')).toBeInTheDocument()
    expect(screen.getByLabelText('Bucket')).toBeInTheDocument()
    expect(screen.getByLabelText('API token')).toBeInTheDocument()

    fireEvent.click(screen.getByRole('button', {name: /1.x/}))

    expect(screen.getByLabelText('Database')).toBeInTheDocument()
    expect(screen.getByLabelText(/Username/)).toBeInTheDocument()
    expect(screen.getByLabelText('Password')).toBeInTheDocument()
  })

  it('expands SQL advanced options and keeps the connection test available', () => {
    const {onTest} = renderConfigureStep('postgresql', {host: 'db.internal'})

    fireEvent.click(screen.getByRole('button', {name: 'Test connection'}))
    expect(onTest).toHaveBeenCalled()

    fireEvent.click(screen.getByRole('button', {name: /Advanced options/}))

    expect(screen.getByLabelText('TLS / SSL mode')).toBeInTheDocument()
    expect(screen.getByLabelText(/Connect timeout/)).toBeInTheDocument()
    expect(screen.getByLabelText(/Default schema/)).toBeInTheDocument()
  })

  it('updates identity, host, and credential fields without stale split banners', () => {
    renderConfigureStep('postgresql', {host: 'old.internal'})

    fireEvent.change(screen.getByLabelText('Display name'), {target: {value: 'Warehouse DB'}})
    fireEvent.change(screen.getByLabelText(/Description/), {target: {value: 'Read replica'}})
    fireEvent.change(screen.getByLabelText('Host'), {target: {value: 'db.internal'}})
    fireEvent.change(screen.getByLabelText('Host'), {target: {value: 'db.internal:notaport'}})
    fireEvent.change(screen.getByLabelText(/Username/), {target: {value: 'svc'}})
    fireEvent.change(screen.getByLabelText('Password'), {target: {value: 'secret'}})

    expect(screen.getByLabelText('Display name')).toHaveValue('Warehouse DB')
    expect(screen.getByLabelText(/Description/)).toHaveValue('Read replica')
    expect(screen.getByLabelText('Host')).toHaveValue('db.internal:notaport')
    expect(screen.getByLabelText(/Username/)).toHaveValue('svc')
    expect(screen.getByLabelText('Password')).toHaveValue('secret')
    expect(screen.queryByText(/Split your pasted value/)).not.toBeInTheDocument()
  })

  it('switches SQL sources to connection-string mode and toggles password visibility', () => {
    renderConfigureStep('postgresql')

    fireEvent.click(screen.getByRole('button', {name: /Connection string/}))
    fireEvent.change(screen.getByLabelText('Connection string'), {
      target: {value: 'postgresql://svc:secret@db.internal:5432/app'},
    })

    expect(screen.getByLabelText('Connection string')).toHaveValue(
      'postgresql://svc:secret@db.internal:5432/app',
    )
    expect(screen.queryByLabelText('Password')).not.toBeInTheDocument()

    renderConfigureStep('mysql')
    const password = screen.getByLabelText('Password')
    expect(password).toHaveAttribute('type', 'password')

    fireEvent.click(screen.getByRole('button', {name: 'Show value'}))
    expect(screen.getByLabelText('Password')).toHaveAttribute('type', 'text')
    expect(screen.getByRole('button', {name: 'Hide value'})).toBeInTheDocument()
  })

  it('splits pasted HTTP endpoints and lets the user undo the split', () => {
    renderConfigureStep('prometheus')

    fireEvent.change(screen.getByLabelText('Host'), {
      target: {value: 'https://metrics.internal:9443/prometheus'},
    })

    expect(screen.getByLabelText('Host')).toHaveValue('metrics.internal')
    expect(screen.getByLabelText(/^Port/)).toHaveValue('9443')
    expect(screen.getByLabelText(/Base path/)).toHaveValue('/prometheus')
    expect(screen.getByText(/Split your pasted value/)).toBeInTheDocument()

    fireEvent.click(screen.getByRole('button', {name: 'undo'}))

    expect(screen.getByLabelText('Host')).toHaveValue('')
    // Prometheus has no default port, so undo restores the blank field.
    expect(screen.getByLabelText(/^Port/)).toHaveValue('')
    expect(screen.getByLabelText(/Base path/)).toHaveValue('')
  })

  it('defaults the Prometheus port to a blank, optional field', () => {
    renderConfigureStep('prometheus')

    expect(screen.getByLabelText(/^Port/)).toHaveValue('')
    // The Port label is flagged optional for http sources (reverse-proxy friendly).
    expect(screen.getByText(/^Port/)).toHaveTextContent('optional')
  })

  it('renders HTTP authentication variants and advanced request options', () => {
    renderConfigureStep('elasticsearch')

    fireEvent.change(screen.getByLabelText('Authentication'), {target: {value: 'basic'}})
    expect(screen.getByLabelText(/Username/)).toBeInTheDocument()
    expect(screen.getByLabelText('Password')).toBeInTheDocument()

    fireEvent.change(screen.getByLabelText('Authentication'), {target: {value: 'bearer'}})
    expect(screen.getByLabelText('Bearer token')).toBeInTheDocument()

    fireEvent.change(screen.getByLabelText('Authentication'), {target: {value: 'header'}})
    expect(screen.getByLabelText('Header name')).toBeInTheDocument()
    expect(screen.getByLabelText('Header value')).toBeInTheDocument()

    fireEvent.click(screen.getByRole('button', {name: /Advanced options/}))
    expect(screen.getByLabelText(/Request timeout/)).toBeInTheDocument()
    expect(screen.getByLabelText(/Index/)).toBeInTheDocument()
  })

  it('updates ClickHouse protocol defaults and shows invalid ports', () => {
    renderConfigureStep('clickhouse', {port: 'abc'})

    expect(screen.getByText(/fix the port to continue/)).toBeInTheDocument()
    fireEvent.click(screen.getByRole('button', {name: /Native/}))
    expect(screen.getByLabelText('Port')).toHaveValue('9000')

    fireEvent.click(screen.getByRole('button', {name: /HTTP/}))
    expect(screen.getByLabelText('Port')).toHaveValue('8123')
  })

  it('renders manual host and credentials for connection-string sources', () => {
    renderConfigureStep('mongodb')

    expect(screen.getByLabelText('Connection string')).toBeInTheDocument()
    fireEvent.click(screen.getByRole('button', {name: /Enter host & port manually instead/}))

    expect(screen.getByLabelText('Host')).toBeInTheDocument()
    expect(screen.getByLabelText('Port')).toBeInTheDocument()
    expect(screen.getByLabelText(/Database/)).toBeInTheDocument()
    expect(screen.getByLabelText(/Username/)).toBeInTheDocument()
    expect(screen.getByLabelText('Password')).toBeInTheDocument()
  })

  it('renders local-file, Snowflake, and test-result details', () => {
    renderConfigureStep('sqlite')
    expect(screen.getByLabelText('Database file path')).toBeInTheDocument()
    expect(screen.getByText(/SQLite is a local file/)).toBeInTheDocument()

    const success: TestConnectionResult = {
      success: true,
      message: 'Connected',
      tables: ['events', 'issues'],
      metrics: ['up'],
    }
    renderConfigureStep('snowflake', {account: 'xy12345.us-east-1', warehouse: 'WH'}, success)
    expect(screen.getByLabelText('Account identifier')).toBeInTheDocument()
    expect(screen.getByLabelText('Warehouse')).toBeInTheDocument()
    expect(screen.getByLabelText(/Role/)).toBeInTheDocument()
    expect(screen.getByText('tables: events, issues')).toBeInTheDocument()
    expect(screen.getByText('metrics: up')).toBeInTheDocument()
  })

  it('toggles CloudWatch static credentials when using the instance role', () => {
    renderConfigureStep('cloudwatch')

    expect(screen.getByLabelText('AWS region')).toBeInTheDocument()
    expect(screen.getByLabelText('Access key ID')).toBeInTheDocument()
    expect(screen.getByLabelText('Secret access key')).toBeInTheDocument()

    fireEvent.click(screen.getByLabelText(/Use the instance role/))

    expect(screen.queryByLabelText('Access key ID')).not.toBeInTheDocument()
    expect(screen.queryByLabelText('Secret access key')).not.toBeInTheDocument()
  })

  it('loads a BigQuery service account JSON file', async () => {
    renderConfigureStep('bigquery')
    const file = new File(['{"type":"service_account","project_id":"demo"}'], 'key.json', {
      type: 'application/json',
    })

    fireEvent.change(screen.getByLabelText('Upload JSON key file'), {target: {files: [file]}})

    await waitFor(() => {
      expect(screen.getByLabelText(/paste the JSON/)).toHaveValue(
        '{"type":"service_account","project_id":"demo"}',
      )
    })

    fireEvent.change(screen.getByLabelText(/paste the JSON/), {
      target: {value: '{"type":"service_account","project_id":"manual"}'},
    })
    expect(screen.getByLabelText(/paste the JSON/)).toHaveValue(
      '{"type":"service_account","project_id":"manual"}',
    )
  })

  it('updates CloudWatch region and static credentials', () => {
    renderConfigureStep('cloudwatch')

    fireEvent.change(screen.getByLabelText('AWS region'), {target: {value: 'eu-west-1'}})
    fireEvent.change(screen.getByLabelText('Access key ID'), {target: {value: 'AKIA123'}})
    fireEvent.change(screen.getByLabelText('Secret access key'), {target: {value: 'secret'}})

    expect(screen.getByLabelText('AWS region')).toHaveValue('eu-west-1')
    expect(screen.getByLabelText('Access key ID')).toHaveValue('AKIA123')
    expect(screen.getByLabelText('Secret access key')).toHaveValue('secret')
  })
})

describe('DataSourcePickerStep', () => {
  it('shows and clears an empty search result', () => {
    render(<DataSourcePickerStep onPick={vi.fn()} />)
    const search = screen.getByLabelText('Search data sources')

    fireEvent.change(search, {target: {value: 'not-a-real-source'}})
    expect(screen.getByText(/No data source matches/)).toBeInTheDocument()

    fireEvent.keyDown(search, {key: 'Escape'})
    expect(screen.queryByText(/No data source matches/)).not.toBeInTheDocument()
    expect(screen.getByRole('button', {name: /PostgreSQL/})).toBeInTheDocument()
  })
})
