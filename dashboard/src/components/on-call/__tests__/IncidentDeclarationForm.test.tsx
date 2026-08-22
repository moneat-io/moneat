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

import {beforeEach, describe, expect, it, vi} from 'vitest'
import {fireEvent, screen, waitFor} from '@testing-library/react'
import {renderWithQueryClient} from '@/test/utils'
import {IncidentDeclarationForm} from '../IncidentDeclarationForm'
import type {DeclareIncidentInput} from '@/lib/api/types'

const {mockApi} = vi.hoisted(() => ({
  mockApi: {
    getIncidentTypes: vi.fn(),
    getIncidentForms: vi.fn(),
  },
}))

vi.mock('@/lib/api', () => ({api: mockApi}))

const TITLE_PLACEHOLDER = 'Short, specific summary of what is happening'

function renderForm(props: Partial<React.ComponentProps<typeof IncidentDeclarationForm>> = {}) {
  const onSubmit = vi.fn()
  renderWithQueryClient(
    <IncidentDeclarationForm isSubmitting={false} onSubmit={onSubmit} {...props} />
  )
  return onSubmit
}

const declarationForm = {
  id: 'form-1',
  stage: 'DECLARATION' as const,
  version: 3,
  name: 'Declaration form',
  incidentTypeId: undefined,
  fields: [
    {
      id: 'ff-root',
      position: 0,
      visible: true,
      required: true,
      condition: {},
      field: {id: 'cf-root', key: 'root_cause', version: 1, name: 'Root cause', valueType: 'TEXT' as const, options: []},
    },
    {
      id: 'ff-scope',
      position: 1,
      visible: true,
      required: false,
      condition: {},
      field: {
        id: 'cf-scope',
        key: 'scope',
        version: 1,
        name: 'Scope',
        valueType: 'SELECT' as const,
        options: [{id: 'o1', value: 'partial', label: 'Partial', position: 0}],
      },
    },
  ],
}

// Minimal configured form field with an empty condition and no options.
function field(key: string, name: string, valueType: string, position: number) {
  return {
    id: `ff-${key}`,
    position,
    visible: true,
    required: false,
    condition: {} as Record<string, unknown>,
    field: {id: `cf-${key}`, key, version: 1, name, valueType, options: [] as unknown[]},
  }
}

describe('IncidentDeclarationForm', () => {
  beforeEach(() => {
    vi.clearAllMocks()
    mockApi.getIncidentTypes.mockResolvedValue([])
    mockApi.getIncidentForms.mockResolvedValue([])
  })

  it('declares a standalone LIVE incident with defaults', async () => {
    const onSubmit = renderForm()
    fireEvent.change(screen.getByPlaceholderText(TITLE_PLACEHOLDER), {target: {value: 'Checkout down'}})
    fireEvent.click(screen.getByRole('button', {name: 'Declare incident'}))
    await waitFor(() => expect(onSubmit).toHaveBeenCalledTimes(1))
    const payload = onSubmit.mock.calls[0][0] as DeclareIncidentInput
    expect(payload).toMatchObject({
      title: 'Checkout down',
      severity: 'SEV-2',
      mode: 'LIVE',
      visibility: 'ORGANIZATION',
      initialStatus: 'ACTIVE',
    })
  })

  it('carries RETROSPECTIVE mode through to the payload', async () => {
    const onSubmit = renderForm({defaults: {mode: 'RETROSPECTIVE'}})
    fireEvent.change(screen.getByPlaceholderText(TITLE_PLACEHOLDER), {target: {value: 'Post-hoc write up'}})
    fireEvent.click(screen.getByRole('button', {name: 'Declare incident'}))
    await waitFor(() => expect(onSubmit).toHaveBeenCalled())
    expect((onSubmit.mock.calls[0][0] as DeclareIncidentInput).mode).toBe('RETROSPECTIVE')
  })

  it('carries TEST mode through to the payload', async () => {
    const onSubmit = renderForm({defaults: {mode: 'TEST'}})
    fireEvent.change(screen.getByPlaceholderText(TITLE_PLACEHOLDER), {target: {value: 'Game day drill'}})
    fireEvent.click(screen.getByRole('button', {name: 'Declare incident'}))
    await waitFor(() => expect(onSubmit).toHaveBeenCalled())
    expect((onSubmit.mock.calls[0][0] as DeclareIncidentInput).mode).toBe('TEST')
  })

  it('carries PRIVATE visibility and TRIAGE status through to the payload', async () => {
    const onSubmit = renderForm({defaults: {visibility: 'PRIVATE', initialStatus: 'TRIAGE'}})
    fireEvent.change(screen.getByPlaceholderText(TITLE_PLACEHOLDER), {target: {value: 'Sensitive matter'}})
    fireEvent.click(screen.getByRole('button', {name: 'Declare incident'}))
    await waitFor(() => expect(onSubmit).toHaveBeenCalled())
    const payload = onSubmit.mock.calls[0][0] as DeclareIncidentInput
    expect(payload.visibility).toBe('PRIVATE')
    expect(payload.initialStatus).toBe('TRIAGE')
  })

  it('prefills from an alert and keeps fields editable', async () => {
    const onSubmit = renderForm({
      defaults: {title: 'Payments outage', description: 'API is down', severity: 'SEV-1'},
      originHint: 'Declaring from alert "Payments outage"',
    })
    expect(screen.getByText('Declaring from alert "Payments outage"')).toBeInTheDocument()
    const titleInput = screen.getByPlaceholderText(TITLE_PLACEHOLDER) as HTMLInputElement
    expect(titleInput.value).toBe('Payments outage')
    fireEvent.click(screen.getByRole('button', {name: 'Declare incident'}))
    await waitFor(() => expect(onSubmit).toHaveBeenCalled())
    const payload = onSubmit.mock.calls[0][0] as DeclareIncidentInput
    expect(payload).toMatchObject({title: 'Payments outage', description: 'API is down', severity: 'SEV-1'})
  })

  it('renders configured fields, blocks on missing required, then submits their values', async () => {
    mockApi.getIncidentForms.mockResolvedValue([declarationForm])
    const onSubmit = renderForm()
    fireEvent.change(screen.getByPlaceholderText(TITLE_PLACEHOLDER), {target: {value: 'Data pipeline stalled'}})

    const rootCause = await screen.findByLabelText(/Root cause/)
    expect(screen.getByText('Declaration form')).toBeInTheDocument()
    expect(screen.getByText('Scope')).toBeInTheDocument()

    fireEvent.click(screen.getByRole('button', {name: 'Declare incident'}))
    expect(onSubmit).not.toHaveBeenCalled()
    expect(await screen.findByText('Root cause is required.')).toBeInTheDocument()

    fireEvent.change(rootCause, {target: {value: 'bad deploy'}})
    fireEvent.click(screen.getByRole('button', {name: 'Declare incident'}))
    await waitFor(() => expect(onSubmit).toHaveBeenCalled())
    expect((onSubmit.mock.calls[0][0] as DeclareIncidentInput).fields).toEqual({root_cause: 'bad deploy'})
  })

  it('emits type-correct values for number, multi-select, link, and string controls', async () => {
    mockApi.getIncidentForms.mockResolvedValue([
      {
        id: 'form-typed',
        stage: 'DECLARATION',
        version: 1,
        name: 'Typed details',
        incidentTypeId: undefined,
        fields: [
          field('impact_count', 'Impact count', 'NUMBER', 0),
          {
            ...field('regions', 'Regions', 'MULTI_SELECT', 1),
            field: {
              id: 'cf-regions',
              key: 'regions',
              version: 1,
              name: 'Regions',
              valueType: 'MULTI_SELECT',
              options: [
                {id: 'o1', value: 'us', label: 'US', position: 0},
                {id: 'o2', value: 'eu', label: 'EU', position: 1},
              ],
            },
          },
          field('dashboard', 'Dashboard', 'LINK', 2),
          field('owner', 'Owner', 'USER', 3),
        ],
      },
    ])
    const onSubmit = renderForm()
    fireEvent.change(screen.getByPlaceholderText(TITLE_PLACEHOLDER), {target: {value: 'Typed incident'}})

    fireEvent.change(await screen.findByLabelText(/Impact count/), {target: {value: '5'}})
    fireEvent.change(screen.getByLabelText(/Dashboard/), {target: {value: 'https://dash.example.com/x'}})
    fireEvent.change(screen.getByLabelText(/Owner/), {target: {value: 'user-123'}})
    fireEvent.click(screen.getByRole('checkbox', {name: 'US'}))

    fireEvent.click(screen.getByRole('button', {name: 'Declare incident'}))
    await waitFor(() => expect(onSubmit).toHaveBeenCalled())
    expect((onSubmit.mock.calls[0][0] as DeclareIncidentInput).fields).toEqual({
      impact_count: 5,
      regions: ['us'],
      dashboard: 'https://dash.example.com/x',
      owner: 'user-123',
    })
  })

  it('shows a conditional field only when a typed equals matches', async () => {
    mockApi.getIncidentForms.mockResolvedValue([
      {
        id: 'form-cond',
        stage: 'DECLARATION',
        version: 1,
        name: 'Conditional',
        incidentTypeId: undefined,
        fields: [
          field('sev_num', 'Severity number', 'NUMBER', 0),
          {...field('reason', 'Reason', 'TEXT', 1), condition: {fieldKey: 'sev_num', equals: 2}},
        ],
      },
    ])
    const onSubmit = renderForm()
    fireEvent.change(screen.getByPlaceholderText(TITLE_PLACEHOLDER), {target: {value: 'Conditional incident'}})

    await screen.findByLabelText(/Severity number/)
    expect(screen.queryByLabelText(/Reason/)).not.toBeInTheDocument()

    fireEvent.change(screen.getByLabelText(/Severity number/), {target: {value: '2'}})
    fireEvent.change(await screen.findByLabelText(/Reason/), {target: {value: 'blast radius'}})

    fireEvent.click(screen.getByRole('button', {name: 'Declare incident'}))
    await waitFor(() => expect(onSubmit).toHaveBeenCalled())
    expect((onSubmit.mock.calls[0][0] as DeclareIncidentInput).fields).toEqual({sev_num: 2, reason: 'blast radius'})
  })

  it('keeps a malformed conditional field hidden (fails closed)', async () => {
    mockApi.getIncidentForms.mockResolvedValue([
      {
        id: 'form-bad-cond',
        stage: 'DECLARATION',
        version: 1,
        name: 'Malformed',
        incidentTypeId: undefined,
        fields: [
          field('sev_num', 'Severity number', 'NUMBER', 0),
          {...field('reason', 'Reason', 'TEXT', 1), condition: {fieldKey: 'sev_num'}},
        ],
      },
    ])
    renderForm()
    await screen.findByLabelText(/Severity number/)
    fireEvent.change(screen.getByLabelText(/Severity number/), {target: {value: '2'}})
    expect(screen.queryByLabelText(/Reason/)).not.toBeInTheDocument()
  })

  // Guards the render-phase re-seed (the same path that runs when switching
  // incident types): configured fields must not flash before the form resolves,
  // must seed to the active form's defaults, and must never submit stale values.
  it('re-seeds field defaults when the active form resolves, without a stale flash or submit', async () => {
    mockApi.getIncidentForms.mockResolvedValue([
      {
        id: 'form-default',
        stage: 'DECLARATION',
        version: 1,
        name: 'Seeded form',
        incidentTypeId: undefined,
        fields: [{...field('region', 'Region', 'TEXT', 0), defaultValue: 'us-east'}],
      },
    ])
    const onSubmit = renderForm()

    // Nothing configured is rendered until the form resolves (no stale flash).
    expect(screen.queryByLabelText(/Region/)).not.toBeInTheDocument()

    const regionInput = (await screen.findByLabelText(/Region/)) as HTMLInputElement
    expect(regionInput.value).toBe('us-east')

    fireEvent.change(screen.getByPlaceholderText(TITLE_PLACEHOLDER), {target: {value: 'Region incident'}})
    fireEvent.click(screen.getByRole('button', {name: 'Declare incident'}))
    await waitFor(() => expect(onSubmit).toHaveBeenCalled())
    expect((onSubmit.mock.calls[0][0] as DeclareIncidentInput).fields).toEqual({region: 'us-east'})

    // Edits after the seed win; the default never reverts or leaks a stale value.
    onSubmit.mockClear()
    fireEvent.change(regionInput, {target: {value: 'eu-west'}})
    fireEvent.click(screen.getByRole('button', {name: 'Declare incident'}))
    await waitFor(() => expect(onSubmit).toHaveBeenCalled())
    expect((onSubmit.mock.calls[0][0] as DeclareIncidentInput).fields).toEqual({region: 'eu-west'})
  })
})
