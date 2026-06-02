// Moneat - observability platform
// Copyright (C) 2026 Moneat
//
// This program is free software: you can redistribute it and/or modify
// it under the terms of the GNU Affero General Public License as published by
// the Free Software Foundation, either version 3 of the License, or
// (at your option) any later version.

import {createFileRoute, redirect} from '@tanstack/react-router'
import {Network} from 'lucide-react'
import {api} from '@/lib/api'
import {ServiceMap} from '@/components/apm/ServiceMap'
import {PageHeader} from '@/components/ui/page-header'

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
      <PageHeader
        icon={Network}
        title="Service map"
        description="Service dependency graph from trace telemetry"
      />
      <ServiceMap />
    </div>
  )
}
