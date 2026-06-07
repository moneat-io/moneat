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

import {QueryClient, QueryClientProvider} from '@tanstack/react-query'
import {fireEvent, render, screen, waitFor} from '@testing-library/react'
import {beforeEach, describe, expect, it, vi} from 'vitest'

import {SalesContactDialog} from '@/components/landing/SalesContactDialog'

const {mockApi, mockToast} = vi.hoisted(() => ({
  mockApi: {createSalesInquiry: vi.fn()},
  mockToast: vi.fn(),
}))

vi.mock('@/lib/api', () => ({api: mockApi}))
vi.mock('@/hooks/useToast', () => ({useToast: () => ({toast: mockToast})}))

function renderDialog() {
  const queryClient = new QueryClient({
    defaultOptions: {queries: {retry: false}, mutations: {retry: false}},
  })
  const onOpenChange = vi.fn()
  render(
    <QueryClientProvider client={queryClient}>
      <SalesContactDialog open onOpenChange={onOpenChange} />
    </QueryClientProvider>,
  )
  return {onOpenChange}
}

function fillValidForm() {
  fireEvent.change(screen.getByLabelText(/name/i), {target: {value: 'Ada Lovelace'}})
  fireEvent.change(screen.getByLabelText(/work email/i), {target: {value: 'ada@acme.com'}})
  fireEvent.change(screen.getByLabelText(/company/i), {target: {value: 'Acme Corp'}})
  fireEvent.change(screen.getByLabelText(/message/i), {target: {value: 'We need a dedicated SLA.'}})
}

describe('SalesContactDialog', () => {
  beforeEach(() => {
    vi.clearAllMocks()
  })

  it('renders the contact form fields when open', () => {
    renderDialog()
    expect(screen.getByLabelText(/name/i)).toBeInTheDocument()
    expect(screen.getByLabelText(/work email/i)).toBeInTheDocument()
    expect(screen.getByLabelText(/company/i)).toBeInTheDocument()
    expect(screen.getByLabelText(/message/i)).toBeInTheDocument()
  })

  it('blocks submission and shows validation errors when required fields are empty', async () => {
    renderDialog()
    fireEvent.click(screen.getByRole('button', {name: /send message/i}))
    expect(mockApi.createSalesInquiry).not.toHaveBeenCalled()
    expect(await screen.findByText('Name is required')).toBeInTheDocument()
  })

  it('blocks submission when the email is malformed', async () => {
    renderDialog()
    fillValidForm()
    fireEvent.change(screen.getByLabelText(/work email/i), {target: {value: 'not-an-email'}})
    fireEvent.click(screen.getByRole('button', {name: /send message/i}))
    expect(mockApi.createSalesInquiry).not.toHaveBeenCalled()
    expect(await screen.findByText(/valid.*email/i)).toBeInTheDocument()
  })

  it('blocks submission when the email contains whitespace', async () => {
    renderDialog()
    fillValidForm()
    fireEvent.change(screen.getByLabelText(/work email/i), {target: {value: 'ada @acme.com'}})
    fireEvent.click(screen.getByRole('button', {name: /send message/i}))
    expect(mockApi.createSalesInquiry).not.toHaveBeenCalled()
    expect(await screen.findByText(/valid.*email/i)).toBeInTheDocument()
  })

  it('submits the inquiry and shows a confirmation', async () => {
    mockApi.createSalesInquiry.mockResolvedValue({message: 'ok'})
    renderDialog()
    fillValidForm()
    fireEvent.click(screen.getByRole('button', {name: /send message/i}))

    await waitFor(() =>
      expect(mockApi.createSalesInquiry).toHaveBeenCalledWith(
        expect.objectContaining({
          name: 'Ada Lovelace',
          email: 'ada@acme.com',
          company: 'Acme Corp',
          message: 'We need a dedicated SLA.',
        }),
      ),
    )
    expect(await screen.findByText(/in touch/i)).toBeInTheDocument()
  })

  it('submits the honeypot value when it is filled', async () => {
    mockApi.createSalesInquiry.mockResolvedValue({message: 'ok'})
    renderDialog()
    fillValidForm()
    fireEvent.change(screen.getByLabelText(/website/i), {target: {value: 'https://bot.example'}})
    fireEvent.click(screen.getByRole('button', {name: /send message/i}))

    await waitFor(() =>
      expect(mockApi.createSalesInquiry).toHaveBeenCalledWith(
        expect.objectContaining({
          website: 'https://bot.example',
        }),
      ),
    )
  })

  it('closes and resets after a successful submission', async () => {
    mockApi.createSalesInquiry.mockResolvedValue({message: 'ok'})
    const {onOpenChange} = renderDialog()
    fillValidForm()
    fireEvent.click(screen.getByRole('button', {name: /send message/i}))

    fireEvent.click(await screen.findByRole('button', {name: /close/i}))
    expect(onOpenChange).toHaveBeenCalledWith(false)
  })

  it('shows a destructive toast when the request fails', async () => {
    mockApi.createSalesInquiry.mockRejectedValue(new Error('boom'))
    renderDialog()
    fillValidForm()
    fireEvent.click(screen.getByRole('button', {name: /send message/i}))

    await waitFor(() =>
      expect(mockToast).toHaveBeenCalledWith(expect.objectContaining({variant: 'destructive'})),
    )
  })
})
