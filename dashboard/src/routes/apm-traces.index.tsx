// Moneat - observability platform
// Copyright (C) 2026 Moneat
//
// This program is free software: you can redistribute it and/or modify
// it under the terms of the GNU Affero General Public License as published by
// the Free Software Foundation, either version 3 of the License, or
// (at your option) any later version.

import {createFileRoute} from '@tanstack/react-router'
import {TraceList} from '@/components/apm/TraceList'
import {ServiceMap} from '@/components/apm/ServiceMap'
import {Tabs, TabsContent, TabsList, TabsTrigger} from '@/components/ui/tabs'

export const Route = createFileRoute('/apm-traces/')({
  component: ApmTracesIndexPage,
})

function ApmTracesIndexPage() {
  return (
    <div className="p-6 space-y-6">
      <div>
        <h1 className="text-2xl font-bold">APM Traces</h1>
        <p className="text-muted-foreground text-sm mt-1">
          Application performance monitoring traces
        </p>
      </div>

      <Tabs defaultValue="traces">
        <TabsList>
          <TabsTrigger value="traces">Traces</TabsTrigger>
          <TabsTrigger value="services">Service Map</TabsTrigger>
        </TabsList>
        <TabsContent value="traces" className="mt-4">
          <TraceList />
        </TabsContent>
        <TabsContent value="services" className="mt-4">
          <ServiceMap />
        </TabsContent>
      </Tabs>
    </div>
  )
}
