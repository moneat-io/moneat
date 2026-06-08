import {fireEvent, screen, waitFor} from '@testing-library/react'
import {beforeEach, describe, expect, it, vi} from 'vitest'
import {renderWithQueryClient} from '@/test/utils'
import {CloudAccountSetup} from '../CloudAccountSetup'

const {mockApi, mockToast} = vi.hoisted(() => ({
  mockApi: {
    getCloudSourceSetupPreview: vi.fn(),
    getCloudSources: vi.fn(),
    createCloudSource: vi.fn(),
    syncCloudSource: vi.fn(),
  },
  mockToast: vi.fn(),
}))

vi.mock('@/lib/api', () => ({
  api: mockApi,
  formatErrorForLogging: (error: unknown) => String(error),
}))

vi.mock('@/hooks/useToast', () => ({
  useToast: () => ({toast: mockToast}),
}))

const EXTERNAL_ID_CONDITION_KEY = ['sts', 'ExternalId'].join(':')

describe('CloudAccountSetup', () => {
  beforeEach(() => {
    vi.clearAllMocks()
    mockApi.getCloudSourceSetupPreview.mockResolvedValue({
      provider: 'aws',
      externalId: 'mnt-ext-test',
      principal: 'arn:aws:iam::499432741914:root',
      snippetLabel: 'Trust policy',
      snippetLanguage: 'json',
      snippet: `{"Condition":{"StringEquals":{"${EXTERNAL_ID_CONDITION_KEY}":"mnt-ext-test"}}}`,
    })
    mockApi.getCloudSources.mockResolvedValue([])
    mockApi.createCloudSource.mockResolvedValue({
      id: 1,
      provider: 'aws',
      displayName: 'AWS 123456789012',
      status: 'healthy',
      config: {accountId: '123456789012', roleName: 'MoneatIntegrationRole'},
      collectMetrics: true,
      collectInventory: true,
      collectCost: false,
      collectLogs: false,
      externalId: 'mnt-ext-test',
      lastSyncAt: '2026-06-07T12:00:00Z',
      lastError: null,
      createdAt: '2026-06-07T12:00:00Z',
      updatedAt: '2026-06-07T12:00:00Z',
    })
  })

  it('renders backend setup preview without a logs option', async () => {
    renderWithQueryClient(<CloudAccountSetup />)

    expect(await screen.findByText('mnt-ext-test')).toBeInTheDocument()
    expect(screen.getByText('arn:aws:iam::499432741914:root')).toBeInTheDocument()
    expect(screen.queryByText('Logs')).not.toBeInTheDocument()
  })

  it('creates a cloud source with logs disabled', async () => {
    renderWithQueryClient(<CloudAccountSetup />)

    fireEvent.change(await screen.findByLabelText('Account ID'), {
      target: {value: '123456789012'},
    })
    fireEvent.change(screen.getByLabelText('IAM role name'), {
      target: {value: 'MoneatIntegrationRole'},
    })
    fireEvent.click(screen.getByRole('button', {name: 'Connect'}))

    await waitFor(() => expect(mockApi.createCloudSource).toHaveBeenCalledTimes(1))
    expect(mockApi.createCloudSource).toHaveBeenCalledWith({
      provider: 'aws',
      displayName: 'AWS 123456789012',
      config: {accountId: '123456789012', roleName: 'MoneatIntegrationRole'},
      collectMetrics: true,
      collectInventory: true,
      collectCost: false,
      collectLogs: false,
    })
  })
})
