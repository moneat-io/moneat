// Moneat - observability platform
// Copyright (C) 2026 Moneat
//
// This program is free software: you can redistribute it and/or modify
// it under the terms of the GNU Affero General Public License as published by
// the Free Software Foundation, either version 3 of the License, or
// (at your option) any later version.

import {createFileRoute} from '@tanstack/react-router'
import {DdProfileList} from '@/components/datadog/DdProfileList'

export const Route = createFileRoute('/dd-profiles/')({
  component: DdProfilesIndexPage,
})

function DdProfilesIndexPage() {
  return (
    <div className="p-6 space-y-6">
      <div>
        <h1 className="text-2xl font-bold">DD-Compatible Profiles</h1>
        <p className="text-muted-foreground text-sm mt-1">
          Continuous profiling data from Datadog-compatible agents
        </p>
      </div>
      <DdProfileList />
    </div>
  )
}
