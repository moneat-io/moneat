// Moneat - observability platform
// Copyright (C) 2026 Moneat
//
// This program is free software: you can redistribute it and/or modify
// it under the terms of the GNU Affero General Public License as published by
// the Free Software Foundation, either version 3 of the License, or
// (at your option) any later version.

import {createFileRoute} from '@tanstack/react-router'
import {ServiceMap} from '@/components/apm/ServiceMap'
import {Badge} from '@/components/ui/badge'

export const Route = createFileRoute('/performance/service-map')({
  component: PerformanceServiceMapPage,
})

function PerformanceServiceMapPage() {
  return (
    <div className="p-6 space-y-5">
      <div>
        <h1 className="text-2xl font-bold tracking-tight">Service Map</h1>
        <p className="text-muted-foreground text-sm mt-0.5">
          Service dependency graph from APM instrumentation
          <Badge variant="outline" className="ml-2 text-[10px] px-1.5 py-0 font-normal">
            Datadog
          </Badge>
        </p>
      </div>
      <ServiceMap />
    </div>
  )
}
