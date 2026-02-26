// Moneat - observability platform
// Copyright (C) 2026 Moneat
//
// This program is free software: you can redistribute it and/or modify
// it under the terms of the GNU Affero General Public License as published by
// the Free Software Foundation, either version 3 of the License, or
// (at your option) any later version.

import {createFileRoute, redirect} from '@tanstack/react-router'
import {api} from '@/lib/api'
import {EventStream} from '@/components/monitoring/EventStream'
import {ServiceCheckList} from '@/components/monitoring/ServiceCheckList'
import {Tabs, TabsContent, TabsList, TabsTrigger} from '@/components/ui/tabs'
import {ShieldCheck, Zap} from 'lucide-react'

export const Route = createFileRoute('/monitoring/events')({
  beforeLoad: () => {
    if (!api.isAuthenticated()) {
      throw redirect({to: '/login'})
    }
  },
  component: MonitoringEventsPage,
})

function MonitoringEventsPage() {
  return (
    <div className="container mx-auto px-4 py-4 space-y-4">
      <Tabs defaultValue="events">
        <TabsList>
          <TabsTrigger value="events" className="gap-2">
            <Zap className="h-3.5 w-3.5" />
            Events
          </TabsTrigger>
          <TabsTrigger value="service-checks" className="gap-2">
            <ShieldCheck className="h-3.5 w-3.5" />
            Service Checks
          </TabsTrigger>
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
