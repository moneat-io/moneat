// Moneat - observability platform
// Copyright (C) 2026 Moneat
//
// This program is free software: you can redistribute it and/or modify
// it under the terms of the GNU Affero General Public License as published by
// the Free Software Foundation, either version 3 of the License, or
// (at your option) any later version.

import {createFileRoute, redirect} from '@tanstack/react-router'
import {api} from '@/lib/api'
import {ServiceMap} from '@/components/apm/ServiceMap'

export const Route = createFileRoute('/monitoring/service-map')({
  beforeLoad: () => {
    if (!api.isAuthenticated()) {
      throw redirect({to: '/login'})
    }
  },
  component: MonitoringServiceMapPage,
})

function MonitoringServiceMapPage() {
  return (
    <div className="container mx-auto px-4 py-4 space-y-4">
      <div>
        <h1 className="text-2xl font-bold tracking-tight">Service Map</h1>
        <p className="text-muted-foreground text-sm mt-0.5">
          Service dependency graph from trace telemetry
        </p>
      </div>
      <ServiceMap />
    </div>
  )
}
