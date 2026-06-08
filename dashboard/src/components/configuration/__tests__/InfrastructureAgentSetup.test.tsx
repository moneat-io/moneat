import {fireEvent, screen, waitFor} from '@testing-library/react'
import {beforeEach, describe, expect, it, vi} from 'vitest'
import {renderWithQueryClient} from '@/test/utils'
import {InfrastructureAgentSetup} from '../InfrastructureAgentSetup'

const {mockApi, mockToast} = vi.hoisted(() => ({
  mockApi: {
    isAuthenticated: vi.fn(),
    getAgentApiKeys: vi.fn(),
    createAgentApiKey: vi.fn(),
  },
  mockToast: vi.fn(),
}))

vi.mock('@/lib/api', () => ({
  api: mockApi,
}))

vi.mock('@/hooks/useToast', () => ({
  useToast: () => ({toast: mockToast}),
}))

describe('InfrastructureAgentSetup', () => {
  beforeEach(() => {
    vi.clearAllMocks()
    mockApi.isAuthenticated.mockReturnValue(true)
    mockApi.getAgentApiKeys.mockResolvedValue({
      keys: [{id: 1, name: 'Existing agent', keyPrefix: 'mna_live', createdAt: '2026-06-07T12:00:00Z'}],
    })
    mockApi.createAgentApiKey.mockResolvedValue({key: 'mna_new_secret'})
  })

  it('generates live install artifacts and embeds a newly created key', async () => {
    const {container} = renderWithQueryClient(<InfrastructureAgentSetup />)

    expect(await screen.findByText(/1 agent key already exist/)).toBeInTheDocument()
    expect(container.textContent).toContain('YOUR_AGENT_KEY')

    fireEvent.click(screen.getByRole('button', {name: 'Run'}))
    expect(container.textContent).toContain('docker run')

    fireEvent.click(screen.getByRole('button', {name: /Kubernetes/}))

    expect(container.textContent).toContain('helm install')

    fireEvent.click(screen.getByLabelText('Logs'))
    fireEvent.click(screen.getByRole('button', {name: 'values.yaml'}))

    expect(container.textContent).toContain('DD_LOGS_CONFIG_LOGS_DD_URL')

    fireEvent.change(screen.getByLabelText('Agent key name'), {
      target: {value: 'Production cluster'},
    })
    fireEvent.click(screen.getByRole('button', {name: /Create key/}))

    await waitFor(() => expect(mockApi.createAgentApiKey).toHaveBeenCalledWith('Production cluster'))
    await waitFor(() => expect(container.textContent).toContain('mna_new_secret'))
    expect(mockToast).toHaveBeenCalledWith(expect.objectContaining({
      title: 'Agent key created',
    }))
  })

  it('shows verification steps from the output tabs', async () => {
    renderWithQueryClient(<InfrastructureAgentSetup />)

    await screen.findByText(/Generate an organization agent key|agent key already exist/)
    fireEvent.click(screen.getByRole('button', {name: 'Verify'}))

    expect(screen.getByText(/Save the config/)).toBeInTheDocument()
    expect(screen.getByText(/Hosts appear in Infrastructure/)).toBeInTheDocument()
  })
})
