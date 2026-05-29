// Moneat - observability platform
// Copyright (C) 2026 Moneat
//
// This program is free software: you can redistribute it and/or modify
// it under the terms of the GNU Affero General Public License as published by
// the Free Software Foundation, either version 3 of the License, or
// (at your option) any later version.

import type React from 'react'
import {afterEach, beforeEach, describe, expect, it, vi} from 'vitest'
import {fireEvent, render, screen, waitFor, within} from '@testing-library/react'
import {Layers} from 'lucide-react'
import {ProfileList} from '../ProfileList'
import {ProfileServiceList} from '../ProfileServiceList'
import {ProfileStatCard} from '../ProfileStatCard'
import {ProfilingEmptyState} from '../ProfilingEmptyState'
import {ServiceExplorer} from '../ServiceExplorer'
import {renderWithQueryClient} from '@/test/utils'
import {Route as ProfileDetailRoute} from '@/routes/profiles.$profileId'
import {Route as ProfilesIndexRoute} from '@/routes/profiles.index'
import {Route as ProfileServiceRoute} from '@/routes/profiles.service.$service'

const NOW_MS = 1_700_000_000_000
const RANGE_24H_START = NOW_MS - 24 * 60 * 60 * 1000

const mockRouteParams = vi.hoisted(() => ({
  value: {} as Record<string, string>,
}))

const mockApi = vi.hoisted(() => ({
  downloadProfile: vi.fn(),
  getMergedFlamegraph: vi.fn(),
  getProfile: vi.fn(),
  getProfileFlamegraph: vi.fn(),
  getProfiles: vi.fn(),
  getProfileServices: vi.fn(),
  getProfileTimeseries: vi.fn(),
  isAuthenticated: vi.fn(),
}))

vi.mock('@/lib/api', () => ({api: mockApi}))
vi.mock('@/lib/demo', () => ({
  getNow: () => NOW_MS,
  getNowDate: () => new Date(NOW_MS),
}))

vi.mock('@tanstack/react-router', () => ({
  createFileRoute: () => (
    options: {component: React.ComponentType} & Record<string, unknown>,
  ) => ({
    ...options,
    options,
    useParams: () => mockRouteParams.value,
  }),
  Link: ({
    children,
    to,
    params,
    className,
  }: {
    children: React.ReactNode
    to?: string
    params?: Record<string, string>
    className?: string
  }) => {
    const href = Object.entries(params ?? {}).reduce(
      (acc, [key, value]) => acc.replace(`$${key}`, value),
      to ?? '#',
    )
    return (
      <a href={href} className={className}>
        {children}
      </a>
    )
  },
}))

vi.mock('recharts', () => ({
  Bar: ({children}: {children?: React.ReactNode}) => <div data-testid="bar">{children}</div>,
  BarChart: ({
    children,
    onClick,
  }: {
    children?: React.ReactNode
    onClick?: (state: unknown) => void
  }) => (
    <div
      data-testid="bar-chart"
      onClick={() =>
        onClick?.({activePayload: [{payload: {ts: RANGE_24H_START, count: 2}}]})
      }
    >
      {children}
    </div>
  ),
  Cell: () => <span data-testid="cell" />,
  ResponsiveContainer: ({children}: {children?: React.ReactNode}) => (
    <div data-testid="responsive-container">{children}</div>
  ),
  Tooltip: () => null,
  XAxis: () => null,
  YAxis: () => null,
}))

vi.mock('@/components/profiling/Flamegraph', () => ({
  Flamegraph: ({
    emptyMessage,
    meta,
    onSampleTypeChange,
    onThreadChange,
    service,
  }: {
    emptyMessage?: string
    meta?: React.ReactNode
    onSampleTypeChange?: (value: string | null) => void
    onThreadChange?: (value: string | null) => void
    service?: string
  }) => (
    <section aria-label="mock flamegraph">
      <p>{service}</p>
      <p>{emptyMessage}</p>
      {meta}
      <button type="button" onClick={() => onSampleTypeChange?.('wall')}>
        Pick sample type
      </button>
      <button type="button" onClick={() => onThreadChange?.('main')}>
        Pick thread
      </button>
    </section>
  ),
}))

const profile = {
  profileId: 'prof-1',
  host: 'host-a',
  service: 'checkout',
  env: 'prod',
  version: '1.2.3',
  runtime: 'jvm',
  language: 'java',
  profileType: 'jfr',
  startTime: '2026-05-29T14:00:00Z',
  endTime: '2026-05-29T14:01:00Z',
  durationNs: 60_000_000_000,
  sizeBytes: 2_048,
  tags: {},
  source: 'datadog',
}

const workerProfile = {
  ...profile,
  profileId: 'prof-2',
  service: 'worker',
  profileType: 'pprof',
  language: 'go',
  sizeBytes: 4_096,
}

const checkoutSummary = {
  service: 'checkout',
  languages: ['java'],
  runtimes: ['jvm'],
  environments: ['prod', 'staging'],
  types: [
    {profileType: 'jfr', count: 4},
    {profileType: 'pprof', count: 1},
  ],
  hostCount: 3,
  profileCount: 5,
  totalSizeBytes: 8_192,
  firstSeen: '2026-05-29T13:00:00Z',
  lastSeen: new Date(NOW_MS - 2_000).toISOString(),
  avgDurationNs: 30_000_000_000,
  series: [
    {ts: RANGE_24H_START, count: 1},
    {ts: RANGE_24H_START + 60_000, count: 2},
  ],
}

const workerSummary = {
  ...checkoutSummary,
  service: 'worker',
  languages: [],
  runtimes: ['go'],
  environments: ['prod'],
  types: [{profileType: 'pprof', count: 2}],
  hostCount: 1,
  profileCount: 2,
  lastSeen: 'not-a-date',
  series: [],
}

const servicesResponse = {
  services: [checkoutSummary, workerSummary],
  totalProfiles: 7,
  totalSizeBytes: 12_288,
  serviceCount: 2,
  hostCount: 4,
  typeCount: 2,
}

function resetProfileApiMocks() {
  mockApi.downloadProfile.mockResolvedValue(undefined)
  mockApi.getProfile.mockResolvedValue(profile)
  mockApi.getProfileFlamegraph.mockResolvedValue({
    frames: [{name: 'root', value: 10, children: []}],
    sampleTypes: [{key: 'wall', label: 'Wall', unit: 'samples'}],
    threads: [{id: 'main', label: 'main', samples: 12}],
    selectedSampleType: 'wall',
    selectedThread: null,
    unit: 'samples',
  })
  mockApi.getProfiles.mockResolvedValue({
    profiles: [profile, workerProfile],
    totalCount: 2,
  })
  mockApi.getProfileServices.mockResolvedValue(servicesResponse)
  mockApi.getProfileTimeseries.mockResolvedValue({
    bucketSeconds: 60,
    points: [
      {ts: RANGE_24H_START, count: 2, sizeBytes: 2048},
      {ts: RANGE_24H_START + 60_000, count: 1, sizeBytes: 1024},
    ],
  })
  mockApi.getMergedFlamegraph.mockResolvedValue({
    frames: [{name: 'root', value: 10, children: []}],
    mergedCount: 2,
    totalCount: 5,
    sampleTypes: [{key: 'wall', label: 'Wall', unit: 'samples'}],
    threads: [{id: 'main', label: 'main', samples: 12}],
    selectedSampleType: 'wall',
    selectedThread: null,
    unit: 'samples',
  })
  mockApi.isAuthenticated.mockReturnValue(true)
}

function getRouteComponent(route: unknown): React.ComponentType {
  const candidate = route as {
    component?: React.ComponentType
    options?: {component?: React.ComponentType}
  }
  const component = candidate.component ?? candidate.options?.component
  if (!component) throw new Error('Route component missing')
  return component
}

beforeEach(() => {
  sessionStorage.setItem('authenticated', 'true')
  mockRouteParams.value = {}
  resetProfileApiMocks()
})

afterEach(() => {
  vi.clearAllMocks()
  sessionStorage.clear()
  localStorage.clear()
})

describe('Profile overview widgets', () => {
  it('renders the profile stat card and empty state', () => {
    render(
      <>
        <ProfileStatCard
          label="Services"
          value="12"
          icon={<Layers className="h-3.5 w-3.5" />}
        />
        <ProfilingEmptyState />
      </>,
    )

    expect(screen.getByText('Services')).toBeInTheDocument()
    expect(screen.getByText('12')).toBeInTheDocument()
    expect(screen.getByText('No profiles yet')).toBeInTheDocument()
    expect(screen.getByRole('link', {name: /Read the setup guide/})).toHaveAttribute(
      'href',
      'https://moneat.io/docs/sdk-setup',
    )
  })
})

describe('ProfileList', () => {
  it('renders filters, summary stats, table rows, and download actions', async () => {
    const onServiceFilterChange = vi.fn()
    const onTypeFilterChange = vi.fn()

    renderWithQueryClient(
      <ProfileList
        serviceFilter="checkout"
        onServiceFilterChange={onServiceFilterChange}
        typeFilter="jfr"
        onTypeFilterChange={onTypeFilterChange}
      />,
    )

    expect(await screen.findByRole('link', {name: /checkout/})).toBeInTheDocument()
    expect(mockApi.getProfiles).toHaveBeenCalledWith({
      service: 'checkout',
      type: 'jfr',
      limit: 50,
    })
    expect(screen.getByText('Total Profiles')).toBeInTheDocument()
    expect(screen.getByText('Avg Duration')).toBeInTheDocument()
    expect(screen.getByText('java')).toBeInTheDocument()
    expect(screen.getByText('worker')).toBeInTheDocument()

    fireEvent.change(screen.getByPlaceholderText('Filter by service...'), {
      target: {value: 'api'},
    })
    expect(onServiceFilterChange).toHaveBeenCalledWith('api')

    fireEvent.click(screen.getByRole('button', {name: 'Download profile prof-1'}))
    expect(mockApi.downloadProfile).toHaveBeenCalledWith('prof-1', undefined, 'jfr')
  })

  it('uses embedded scope filters and renders the compact empty state', async () => {
    mockApi.getProfiles.mockResolvedValueOnce({profiles: [], totalCount: 0})

    renderWithQueryClient(
      <ProfileList
        embedded
        scope={{
          service: 'checkout',
          env: 'prod',
          type: 'jfr',
          from: 10,
          to: 20,
        }}
      />,
    )

    expect(await screen.findByText('No profiles in this window.')).toBeInTheDocument()
    expect(mockApi.getProfiles).toHaveBeenCalledWith({
      service: 'checkout',
      env: 'prod',
      type: 'jfr',
      from: 10,
      to: 20,
      limit: 100,
    })
  })
})

describe('ProfileServiceList', () => {
  it('renders service rollups and filters the card grid', async () => {
    const onServiceFilterChange = vi.fn()

    renderWithQueryClient(
      <ProfileServiceList
        serviceFilter=""
        onServiceFilterChange={onServiceFilterChange}
      />,
    )

    expect(await screen.findByRole('link', {name: /checkout/})).toBeInTheDocument()
    expect(screen.getByText('Total Size')).toBeInTheDocument()
    expect(screen.getByText('java')).toBeInTheDocument()
    expect(screen.getByText('go')).toBeInTheDocument()
    expect(screen.getByText('jfr 4')).toBeInTheDocument()

    fireEvent.change(screen.getByPlaceholderText('Filter by service...'), {
      target: {value: 'worker'},
    })
    expect(onServiceFilterChange).toHaveBeenCalledWith('worker')
  })

  it('shows the no-match message when the local filter excludes every service', async () => {
    renderWithQueryClient(
      <ProfileServiceList
        serviceFilter="missing"
        onServiceFilterChange={vi.fn()}
      />,
    )

    expect(await screen.findByText('No services match "missing".')).toBeInTheDocument()
  })
})

describe('ServiceExplorer', () => {
  it('loads profile aggregations and opens the scoped raw profile browser', async () => {
    renderWithQueryClient(<ServiceExplorer service="checkout" />)

    expect(await screen.findByRole('heading', {name: 'checkout'})).toBeInTheDocument()
    expect(screen.getByText('Merged flamegraph')).toBeInTheDocument()
    expect(screen.getByLabelText('mock flamegraph')).toHaveTextContent(
      'Aggregated from',
    )

    expect(mockApi.getProfileTimeseries).toHaveBeenCalledWith({
      service: 'checkout',
      env: undefined,
      type: 'jfr',
      from: RANGE_24H_START,
      to: NOW_MS,
      buckets: 48,
    })
    expect(mockApi.getMergedFlamegraph).toHaveBeenCalledWith({
      service: 'checkout',
      env: undefined,
      type: 'jfr',
      from: RANGE_24H_START,
      to: NOW_MS,
      sampleType: null,
      thread: null,
      maxProfiles: 25,
    })

    fireEvent.click(screen.getByTestId('bar-chart'))
    await waitFor(() =>
      expect(mockApi.getMergedFlamegraph).toHaveBeenLastCalledWith({
        service: 'checkout',
        env: undefined,
        type: 'jfr',
        from: RANGE_24H_START,
        to: RANGE_24H_START + 60_000,
        sampleType: null,
        thread: null,
        maxProfiles: 25,
      }),
    )

    fireEvent.click(screen.getByRole('button', {name: /Browse individual profiles/}))
    const table = await screen.findByRole('table')
    expect(within(table).getByRole('link', {name: /checkout/})).toBeInTheDocument()
    expect(mockApi.getProfiles).toHaveBeenLastCalledWith({
      service: 'checkout',
      env: undefined,
      type: 'jfr',
      from: RANGE_24H_START,
      to: RANGE_24H_START + 60_000,
      limit: 100,
    })
  })

  it('renders fallback metadata for services without finite timestamps', async () => {
    renderWithQueryClient(<ServiceExplorer service="worker" />)

    expect(await screen.findByRole('heading', {name: 'worker'})).toBeInTheDocument()
    expect(screen.getByText('go')).toBeInTheDocument()
    expect(screen.getAllByText('—').length).toBeGreaterThan(0)
  })
})

describe('profile routes', () => {
  it('switches the profiles index between services and all profiles', async () => {
    const ProfilesIndexComponent = getRouteComponent(ProfilesIndexRoute)

    renderWithQueryClient(<ProfilesIndexComponent />)

    expect(await screen.findByRole('heading', {name: 'Profiles'})).toBeInTheDocument()
    expect(await screen.findByRole('link', {name: /checkout/})).toBeInTheDocument()

    fireEvent.click(screen.getByRole('button', {name: 'All profiles'}))
    expect(await screen.findByRole('table')).toBeInTheDocument()
    expect(mockApi.getProfiles).toHaveBeenCalledWith({
      service: undefined,
      type: undefined,
      limit: 50,
    })
  })

  it('renders a profile detail route and forwards download/profile queries', async () => {
    mockRouteParams.value = {profileId: 'prof-1'}
    const ProfileDetailComponent = getRouteComponent(ProfileDetailRoute)

    renderWithQueryClient(<ProfileDetailComponent />)

    expect(await screen.findByRole('heading', {name: 'checkout'})).toBeInTheDocument()
    expect(screen.getByText('prof-1')).toBeInTheDocument()
    expect(screen.getByLabelText('mock flamegraph')).toHaveTextContent('Service')

    expect(mockApi.getProfile).toHaveBeenCalledWith('prof-1')
    expect(mockApi.getProfileFlamegraph).toHaveBeenCalledWith('prof-1', {
      sampleType: null,
      thread: null,
    })
    expect(mockApi.getProfiles).toHaveBeenCalledWith({
      service: 'checkout',
      type: 'jfr',
      limit: 50,
    })

    fireEvent.click(screen.getByRole('button', {name: /Download JFR/}))
    expect(mockApi.downloadProfile).toHaveBeenCalledWith('prof-1', undefined, 'jfr')
  })

  it('renders the service explorer route with the service route param', async () => {
    mockRouteParams.value = {service: 'checkout'}
    const ProfileServiceComponent = getRouteComponent(ProfileServiceRoute)

    renderWithQueryClient(<ProfileServiceComponent />)

    expect(await screen.findByRole('heading', {name: 'checkout'})).toBeInTheDocument()
    expect(mockApi.getMergedFlamegraph).toHaveBeenCalledWith({
      service: 'checkout',
      env: undefined,
      type: 'jfr',
      from: RANGE_24H_START,
      to: NOW_MS,
      sampleType: null,
      thread: null,
      maxProfiles: 25,
    })
  })
})
