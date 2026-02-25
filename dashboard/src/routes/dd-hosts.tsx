// Moneat - observability platform
// Copyright (C) 2026 Moneat
//
// This program is free software: you can redistribute it and/or modify
// it under the terms of the GNU Affero General Public License as published by
// the Free Software Foundation, either version 3 of the License, or
// (at your option) any later version.

import {createFileRoute, redirect} from '@tanstack/react-router'
import {api} from '@/lib/api'
import {HostList} from '@/components/datadog/HostList'

export const Route = createFileRoute('/dd-hosts')({
  beforeLoad: async () => {
    if (!api.isAuthenticated()) {
      throw redirect({to: '/login'})
    }
  },
  component: DdHostsPage,
})

function DdHostsPage() {
  return (
    <div className="p-6 space-y-6">
      <div>
        <h1 className="text-2xl font-bold">DD-Compatible Hosts</h1>
        <p className="text-muted-foreground text-sm mt-1">
          Infrastructure hosts reporting via DD-compatible agents
        </p>
      </div>

      <HostList />
    </div>
  )
}
