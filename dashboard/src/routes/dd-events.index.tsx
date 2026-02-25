// Moneat - observability platform
// Copyright (C) 2026 Moneat
//
// This program is free software: you can redistribute it and/or modify
// it under the terms of the GNU Affero General Public License as published by
// the Free Software Foundation, either version 3 of the License, or
// (at your option) any later version.

import {createFileRoute} from '@tanstack/react-router'
import {EventStream} from '@/components/datadog/EventStream'
import {ServiceCheckList} from '@/components/datadog/ServiceCheckList'
import {Tabs, TabsContent, TabsList, TabsTrigger} from '@/components/ui/tabs'

export const Route = createFileRoute('/dd-events/')({
  component: DdEventsIndexPage,
})

function DdEventsIndexPage() {
  return (
    <div className="p-6 space-y-6">
      <div>
        <h1 className="text-2xl font-bold">DD-Compatible Events</h1>
        <p className="text-muted-foreground text-sm mt-1">
          Events and service checks from DD-compatible agents
        </p>
      </div>

      <Tabs defaultValue="events">
        <TabsList>
          <TabsTrigger value="events">Events</TabsTrigger>
          <TabsTrigger value="service-checks">Service Checks</TabsTrigger>
        </TabsList>
        <TabsContent value="events" className="mt-4">
          <EventStream />
        </TabsContent>
        <TabsContent value="service-checks" className="mt-4">
          <ServiceCheckList />
        </TabsContent>
      </Tabs>
    </div>
  )
}
