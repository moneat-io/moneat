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

import {afterAll, beforeAll, beforeEach, describe, expect, it, vi} from 'vitest'
import {fireEvent, screen, waitFor} from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import {renderRoute} from '@/test/utils'

const {mockApi} = vi.hoisted(() => ({
  mockApi: {
    getIncidentTypes: vi.fn(),
    getIncidentCustomFields: vi.fn(),
    getIncidentForms: vi.fn(),
    getIncidentRoles: vi.fn(),
    createIncidentType: vi.fn(),
    createIncidentCustomField: vi.fn(),
    createIncidentForm: vi.fn(),
    createIncidentRole: vi.fn(),
    getNativeIncidentCapabilities: vi.fn(),
  },
}))

vi.mock('@/lib/api', () => ({api: mockApi}))
vi.mock('@tanstack/react-router', () => ({
  createFileRoute: () => (options: Record<string, unknown>) => options,
}))
vi.mock('@/components/ui/dialog', () => ({
  Dialog: ({open, children}: Readonly<{open: boolean; children: React.ReactNode}>) =>
    open ? <>{children}</> : null,
  DialogContent: ({children}: Readonly<{children: React.ReactNode}>) => <div>{children}</div>,
  DialogDescription: ({children}: Readonly<{children: React.ReactNode}>) => <p>{children}</p>,
  DialogFooter: ({children}: Readonly<{children: React.ReactNode}>) => <div>{children}</div>,
  DialogHeader: ({children}: Readonly<{children: React.ReactNode}>) => <div>{children}</div>,
  DialogTitle: ({children}: Readonly<{children: React.ReactNode}>) => <h2>{children}</h2>,
}))
vi.mock('@/components/ui/select', async () => {
  const React = await import('react')
  interface SelectContextValue {
    open: boolean
    setOpen: (open: boolean) => void
    value: string
    onValueChange: (value: string) => void
  }
  const SelectContext = React.createContext<SelectContextValue | null>(null)
  return {
    Select: ({
      value,
      onValueChange,
      children,
    }: Readonly<{value: string; onValueChange: (value: string) => void; children: React.ReactNode}>) => {
      const [open, setOpen] = React.useState(false)
      return (
        <SelectContext.Provider value={{open, setOpen, value, onValueChange}}>
          <div>{children}</div>
        </SelectContext.Provider>
      )
    },
    SelectTrigger: ({children, ...props}: React.ButtonHTMLAttributes<HTMLButtonElement>) => {
      const context = React.useContext(SelectContext)
      return (
        <button
          {...props}
          type="button"
          role="combobox"
          aria-controls="test-select-options"
          aria-expanded={context?.open ?? false}
          onClick={() => context?.setOpen(!context.open)}
        >
          {children}
        </button>
      )
    },
    SelectValue: () => null,
    SelectContent: ({children}: Readonly<{children: React.ReactNode}>) => {
      const context = React.useContext(SelectContext)
      return context?.open ? <div role="listbox">{children}</div> : null
    },
    SelectItem: ({value, children}: Readonly<{value: string; children: React.ReactNode}>) => {
      const context = React.useContext(SelectContext)
      return (
        <button
          type="button"
          role="option"
          aria-selected={context?.value === value}
          onClick={() => {
            context?.onValueChange(value)
            context?.setOpen(false)
          }}
        >
          {children}
        </button>
      )
    },
  }
})

import {Route} from '../on-call.incident-configuration'

const field = (
  id: string,
  key: string,
  name: string,
  valueType: 'SELECT' | 'MULTI_SELECT' | 'TEXT' | 'NUMBER' | 'LINK'
) => ({
  id,
  key,
  name,
  version: 1,
  valueType,
  options: valueType === 'SELECT' || valueType === 'MULTI_SELECT'
    ? [
        {id: `${id}-us`, value: 'us', label: 'US', position: 0},
        {id: `${id}-eu`, value: 'eu', label: 'EU', position: 1},
      ]
    : [],
})

const configuredFields = [
  field('f1', 'region', 'Region', 'SELECT'),
  field('f2', 'services', 'Services', 'MULTI_SELECT'),
  field('f3', 'capacity', 'Capacity', 'NUMBER'),
  field('f4', 'runbook', 'Runbook', 'LINK'),
  field('f5', 'context', 'Context', 'TEXT'),
]

async function selectOption(
  user: ReturnType<typeof userEvent.setup>,
  triggerName: string,
  optionName: string
) {
  await user.click(screen.getByRole('combobox', {name: triggerName}))
  await user.click(await screen.findByRole('option', {name: optionName}))
}

describe('IncidentConfiguration', () => {
  const originalResizeObserver = globalThis.ResizeObserver
  const originalScrollIntoView = globalThis.HTMLElement.prototype.scrollIntoView
  const originalReleasePointerCapture = globalThis.HTMLElement.prototype.releasePointerCapture
  const originalHasPointerCapture = globalThis.HTMLElement.prototype.hasPointerCapture

  beforeAll(() => {
    globalThis.ResizeObserver = class ResizeObserver {
      observe() {}
      unobserve() {}
      disconnect() {}
    }
    globalThis.HTMLElement.prototype.scrollIntoView = vi.fn()
    globalThis.HTMLElement.prototype.releasePointerCapture = vi.fn()
    globalThis.HTMLElement.prototype.hasPointerCapture = vi.fn(() => false)
  })

  afterAll(() => {
    globalThis.ResizeObserver = originalResizeObserver
    globalThis.HTMLElement.prototype.scrollIntoView = originalScrollIntoView
    globalThis.HTMLElement.prototype.releasePointerCapture = originalReleasePointerCapture
    globalThis.HTMLElement.prototype.hasPointerCapture = originalHasPointerCapture
  })

  beforeEach(() => {
    vi.clearAllMocks()
    // Native incident rollout enabled so the configuration surface renders.
    mockApi.getNativeIncidentCapabilities.mockResolvedValue({
      enabled: true,
      environment: 'production',
      state: 'ENABLED',
      externalProviderPassthroughAffected: false,
    })
    mockApi.getIncidentTypes.mockResolvedValue([
      {id: 't1', key: 'security', name: 'Security incident', version: 2, enabled: true, description: 'Handle breaches'},
    ])
    mockApi.getIncidentCustomFields.mockResolvedValue([
      {id: 'f1', key: 'region', name: 'Region', version: 1, valueType: 'SELECT', options: [{id: 'o1', value: 'us', label: 'US', position: 0}]},
    ])
    mockApi.getIncidentForms.mockResolvedValue([
      {id: 'fm1', stage: 'DECLARATION', name: 'Declaration form', version: 1, incidentTypeId: undefined, fields: []},
    ])
    mockApi.getIncidentRoles.mockResolvedValue([
      {id: 'r1', key: 'scribe', name: 'Scribe', version: 1, responsibilities: ['Record the timeline'], privateInstructions: 'Private scribe playbook', required: false, default: false},
    ])
    mockApi.createIncidentType.mockResolvedValue({id: 't2', key: 'outage', name: 'Outage', version: 1, enabled: true})
    mockApi.createIncidentCustomField.mockResolvedValue(configuredFields[0])
    mockApi.createIncidentForm.mockResolvedValue({
      id: 'fm2',
      stage: 'UPDATE',
      name: 'Update form',
      version: 1,
      fields: [],
    })
    mockApi.createIncidentRole.mockResolvedValue({
      id: 'r2',
      key: 'incident-commander',
      name: 'Incident commander',
      version: 1,
      responsibilities: ['Coordinate responders'],
      required: true,
      default: true,
    })
  })

  it('explains versioned snapshots and lists incident types', async () => {
    renderRoute(Route)
    expect(await screen.findByText(/versioned snapshots/)).toBeInTheDocument()
    expect(await screen.findByText('Security incident')).toBeInTheDocument()
    expect(screen.getByText('v2')).toBeInTheDocument()
  })

  it('creates an incident type through the dialog', async () => {
    renderRoute(Route)
    await screen.findByText('Security incident')
    fireEvent.click(screen.getByRole('button', {name: /New type/}))
    fireEvent.change(await screen.findByPlaceholderText('Security incident'), {
      target: {value: '  Major API / DB outage!!!  '},
    })
    fireEvent.click(screen.getByRole('button', {name: 'Save type'}))
    await waitFor(() =>
      expect(mockApi.createIncidentType).toHaveBeenCalledWith(
        expect.objectContaining({name: 'Major API / DB outage!!!', key: 'major_api_db_outage'})
      )
    )
  })

  it('surfaces fields, forms, and roles (including authored private instructions)', async () => {
    renderRoute(Route)
    await screen.findByText('Security incident')

    fireEvent.mouseDown(screen.getByRole('tab', {name: 'Custom fields'}), {button: 0})
    expect(await screen.findByText('Region')).toBeInTheDocument()

    fireEvent.mouseDown(screen.getByRole('tab', {name: 'Forms'}), {button: 0})
    expect(await screen.findByText('Declaration form')).toBeInTheDocument()

    fireEvent.mouseDown(screen.getByRole('tab', {name: 'Roles'}), {button: 0})
    expect(await screen.findByText('Scribe')).toBeInTheDocument()
    // The configuration surface is where private instructions are authored, so
    // they are shown here (unlike the incident detail responders panel).
    expect(screen.getByText('Private scribe playbook')).toBeInTheDocument()
  })

  it('creates an option-backed custom field and removes unused option drafts', async () => {
    const user = userEvent.setup()
    renderRoute(Route)
    await screen.findByText('Security incident')
    await user.click(screen.getByRole('tab', {name: 'Custom fields'}))
    await user.click(await screen.findByRole('button', {name: /New field/}))

    await user.type(screen.getByLabelText('Name'), '  Impact tier  ')
    await user.clear(screen.getByLabelText('Key'))
    await user.type(screen.getByLabelText('Key'), 'impact_level')
    await selectOption(user, 'Type', 'Select (single)')
    await user.type(screen.getByLabelText('Description'), ' Customer impact level ')
    await user.type(screen.getByLabelText('Option 1 value'), ' p1 ')
    await user.type(screen.getByLabelText('Option 1 label'), ' Critical ')
    await user.click(screen.getByRole('button', {name: 'Add option'}))
    await user.type(screen.getByLabelText('Option 2 value'), 'unused')
    await user.type(screen.getByLabelText('Option 2 label'), 'Unused')
    await user.click(screen.getByRole('button', {name: 'Remove option 2'}))
    await user.click(screen.getByRole('button', {name: 'Save field'}))

    await waitFor(() => expect(mockApi.createIncidentCustomField).toHaveBeenCalledTimes(1))
    expect(mockApi.createIncidentCustomField).toHaveBeenCalledWith({
      key: 'impact_level',
      name: 'Impact tier',
      description: 'Customer impact level',
      valueType: 'SELECT',
      catalogResourceType: undefined,
      options: [{value: 'p1', label: 'Critical', position: 0, color: undefined}],
    })
  })

  it('requires and submits a catalog resource type for catalog fields', async () => {
    const user = userEvent.setup()
    renderRoute(Route)
    await screen.findByText('Security incident')
    await user.click(screen.getByRole('tab', {name: 'Custom fields'}))
    await user.click(await screen.findByRole('button', {name: /New field/}))
    await user.type(screen.getByLabelText('Name'), 'Affected service')
    await selectOption(user, 'Type', 'Catalog resource')
    expect(screen.getByRole('button', {name: 'Save field'})).toBeDisabled()
    await user.type(screen.getByLabelText('Catalog resource type'), ' service ')
    await user.click(screen.getByRole('button', {name: 'Save field'}))

    await waitFor(() => expect(mockApi.createIncidentCustomField).toHaveBeenCalledTimes(1))
    expect(mockApi.createIncidentCustomField).toHaveBeenCalledWith(
      expect.objectContaining({
        key: 'affected_service',
        valueType: 'CATALOG_RESOURCE',
        catalogResourceType: 'service',
        options: undefined,
      })
    )
  })

  it('composes a typed, conditional update form and preserves field ordering', async () => {
    const user = userEvent.setup()
    mockApi.getIncidentCustomFields.mockResolvedValue(configuredFields)
    renderRoute(Route)
    await screen.findByText('Security incident')
    await user.click(screen.getByRole('tab', {name: 'Forms'}))
    await user.click(await screen.findByRole('button', {name: /New form/}))

    await user.type(screen.getByLabelText('Name'), '  Update form  ')
    await selectOption(user, 'Stage', 'Update')
    await selectOption(user, 'Incident type', 'Security incident')

    for (const name of ['Region', 'Services', 'Capacity', 'Runbook', 'Context']) {
      await selectOption(user, 'Add field', name)
      await user.click(screen.getByRole('button', {name: 'Add'}))
    }

    await selectOption(user, 'Default value for Region', 'US')
    await user.click(screen.getByRole('checkbox', {name: 'US'}))
    await user.click(screen.getByRole('checkbox', {name: 'EU'}))
    await user.click(screen.getByRole('checkbox', {name: 'EU'}))
    await user.type(screen.getByLabelText('Default value for Capacity'), '-3.5')
    await user.type(screen.getByLabelText('Default value for Runbook'), 'https://runbook.example')
    await user.type(screen.getByLabelText('Default value for Context'), 'Customer checkout')
    await user.type(screen.getByLabelText('Help text for Context'), 'Describe observed impact')

    const visibleSwitches = screen.getAllByRole('switch', {name: 'Visible'})
    const requiredSwitches = screen.getAllByRole('switch', {name: 'Required'})
    await user.click(requiredSwitches[4])
    await user.click(visibleSwitches[4])
    expect(requiredSwitches[4]).toBeDisabled()
    await user.click(visibleSwitches[4])
    await user.click(requiredSwitches[4])

    await selectOption(user, 'Condition field for Context', 'Region')
    await selectOption(user, 'Condition value for Context', 'EU')

    await user.click(screen.getAllByRole('button', {name: 'Move up'})[4])
    await user.click(screen.getAllByRole('button', {name: 'Move down'})[3])
    await user.click(screen.getAllByRole('button', {name: 'Remove field'})[3])
    await user.click(screen.getByRole('button', {name: 'Save form'}))

    await waitFor(() => expect(mockApi.createIncidentForm).toHaveBeenCalledTimes(1))
    expect(mockApi.createIncidentForm).toHaveBeenCalledWith({
      incidentTypeId: 't1',
      stage: 'UPDATE',
      name: 'Update form',
      fields: [
        expect.objectContaining({fieldId: 'f1', position: 0, defaultValue: 'us'}),
        expect.objectContaining({fieldId: 'f2', position: 1, defaultValue: ['us']}),
        expect.objectContaining({fieldId: 'f3', position: 2, defaultValue: -3.5}),
        expect.objectContaining({
          fieldId: 'f5',
          position: 3,
          required: true,
          helpText: 'Describe observed impact',
          defaultValue: 'Customer checkout',
          condition: {fieldKey: 'region', equals: 'eu'},
        }),
      ],
    })
  })

  it('creates a required default responder role with private instructions', async () => {
    const user = userEvent.setup()
    renderRoute(Route)
    await screen.findByText('Security incident')
    await user.click(screen.getByRole('tab', {name: 'Roles'}))
    await user.click(await screen.findByRole('button', {name: /New role/}))

    await user.type(screen.getByLabelText('Name'), '  Incident commander  ')
    await user.type(screen.getByLabelText('Description'), ' Leads the response ')
    await user.type(
      screen.getByLabelText('Responsibilities (one per line)'),
      ' Coordinate responders \n\n Own status updates '
    )
    await user.type(screen.getByLabelText('Private responder instructions'), ' Page the executive liaison ')
    await user.click(screen.getByRole('switch', {name: 'Required'}))
    await user.click(screen.getByRole('switch', {name: 'Assign by default'}))
    await user.click(screen.getByRole('button', {name: 'Save role'}))

    await waitFor(() => expect(mockApi.createIncidentRole).toHaveBeenCalledTimes(1))
    expect(mockApi.createIncidentRole).toHaveBeenCalledWith({
      key: 'incident-commander',
      name: 'Incident commander',
      description: 'Leads the response',
      responsibilities: ['Coordinate responders', 'Own status updates'],
      privateInstructions: 'Page the executive liaison',
      required: true,
      default: true,
    })
  })
})
