// Moneat - observability platform
// Copyright (C) 2026 Moneat
//
// This program is free software: you can redistribute it and/or modify
// it under the terms of the GNU Affero General Public License as published by
// the Free Software Foundation, either version 3 of the License, or
// (at your option) any later version.

import {createFileRoute} from '@tanstack/react-router'
import {DdTraceList} from '@/components/datadog/DdTraceList'
import {DdServiceMap} from '@/components/datadog/DdServiceMap'
import {Tabs, TabsContent, TabsList, TabsTrigger} from '@/components/ui/tabs'

export const Route = createFileRoute('/dd-traces/')({
  component: DdTracesIndexPage,
})

function DdTracesIndexPage() {
  return (
    <div className="p-6 space-y-6">
      <div>
        <h1 className="text-2xl font-bold">DD-Compatible Traces</h1>
        <p className="text-muted-foreground text-sm mt-1">
          APM traces from Datadog-compatible agents
        </p>
      </div>

      <Tabs defaultValue="traces">
        <TabsList>
          <TabsTrigger value="traces">Traces</TabsTrigger>
          <TabsTrigger value="services">Service Map</TabsTrigger>
        </TabsList>
        <TabsContent value="traces" className="mt-4">
          <DdTraceList />
        </TabsContent>
        <TabsContent value="services" className="mt-4">
          <DdServiceMap />
        </TabsContent>
      </Tabs>
    </div>
  )
}
