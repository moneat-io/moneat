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
import {PageHeader} from '@/components/ui/page-header'
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
      <PageHeader
        title="Profiles"
        description="Continuous profiling data from your applications"
        actions={
          <div className="inline-flex rounded-md border p-0.5 text-xs shrink-0">
            <ViewToggle active={view === 'services'} onClick={() => setView('services')}>
              Services
            </ViewToggle>
            <ViewToggle active={view === 'all'} onClick={() => setView('all')}>
              All profiles
            </ViewToggle>
          </div>
        }
      />

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
