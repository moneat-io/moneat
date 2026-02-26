// Moneat - observability platform
// Copyright (C) 2026 Moneat
//
// This program is free software: you can redistribute it and/or modify
// it under the terms of the GNU Affero General Public License as published by
// the Free Software Foundation, either version 3 of the License, or
// (at your option) any later version.

import {createFileRoute} from '@tanstack/react-router'
import {ProfileList} from '@/components/profiling/ProfileList'
import {useState} from 'react'

export const Route = createFileRoute('/profiles/')({
  component: ProfilesIndexPage,
})

function ProfilesIndexPage() {
  const [serviceFilter, setServiceFilter] = useState('')
  const [typeFilter, setTypeFilter] = useState('')

  return (
    <div className="p-6 space-y-5">
      <div>
        <h1 className="text-2xl font-bold tracking-tight">Profiles</h1>
        <p className="text-muted-foreground text-sm mt-0.5">
          Continuous profiling data from your applications
        </p>
      </div>
      <ProfileList
        serviceFilter={serviceFilter}
        onServiceFilterChange={setServiceFilter}
        typeFilter={typeFilter}
        onTypeFilterChange={setTypeFilter}
      />
    </div>
  )
}
