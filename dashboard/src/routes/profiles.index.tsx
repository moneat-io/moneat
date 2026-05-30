// Moneat - observability platform
// Copyright (C) 2026 Moneat
//
// This program is free software: you can redistribute it and/or modify
// it under the terms of the GNU Affero General Public License as published by
// the Free Software Foundation, either version 3 of the License, or
// (at your option) any later version.

import {createFileRoute} from '@tanstack/react-router'
import {useState} from 'react'
import {ProfileServiceList} from '@/components/profiling/ProfileServiceList'
import {ProfileList} from '@/components/profiling/ProfileList'
import {cn} from '@/lib/utils'

export const Route = createFileRoute('/profiles/')({
  component: ProfilesIndexPage,
})

type ProfilesView = 'services' | 'all'

function ProfilesIndexPage() {
  const [view, setView] = useState<ProfilesView>('services')
  const [serviceFilter, setServiceFilter] = useState('')
  const [typeFilter, setTypeFilter] = useState('')

  return (
    <div className="p-3 space-y-2">
      <div className="flex items-start justify-between gap-2">
        <div>
          <h1 className="text-lg font-bold tracking-tight">Profiles</h1>
          <p className="text-muted-foreground text-xs mt-0.5">
            Continuous profiling data from your applications
          </p>
        </div>
        <div className="inline-flex rounded-md border p-0.5 text-xs shrink-0">
          <ViewToggle active={view === 'services'} onClick={() => setView('services')}>
            Services
          </ViewToggle>
          <ViewToggle active={view === 'all'} onClick={() => setView('all')}>
            All profiles
          </ViewToggle>
        </div>
      </div>

      {view === 'services' ? (
        <ProfileServiceList
          serviceFilter={serviceFilter}
          onServiceFilterChange={setServiceFilter}
        />
      ) : (
        <ProfileList
          serviceFilter={serviceFilter}
          onServiceFilterChange={setServiceFilter}
          typeFilter={typeFilter}
          onTypeFilterChange={setTypeFilter}
        />
      )}
    </div>
  )
}

function ViewToggle({
  active,
  onClick,
  children,
}: {
  active: boolean
  onClick: () => void
  children: React.ReactNode
}) {
  return (
    <button
      type="button"
      onClick={onClick}
      className={cn(
        'px-2.5 py-1 rounded font-medium transition-colors',
        active
          ? 'bg-secondary text-secondary-foreground'
          : 'text-muted-foreground hover:text-foreground',
      )}
    >
      {children}
    </button>
  )
}
