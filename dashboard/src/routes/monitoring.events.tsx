// Moneat - observability platform
// Copyright (C) 2026 Moneat
//
// This program is free software: you can redistribute it and/or modify
// it under the terms of the GNU Affero General Public License as published by
// the Free Software Foundation, either version 3 of the License, or
// (at your option) any later version.

import {createFileRoute, Link, redirect} from '@tanstack/react-router'
import {api} from '@/lib/api'
import {EventStream} from '@/components/monitoring/EventStream'
import {ServiceCheckList} from '@/components/monitoring/ServiceCheckList'
import {Tabs, TabsContent, TabsList, TabsTrigger} from '@/components/ui/tabs'
import {ArrowLeft} from 'lucide-react'
import {Button} from '@/components/ui/button'

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
    <div className="p-6 space-y-6">
      <div className="flex items-center gap-2 mb-4">
        <Button
          variant="ghost"
          size="sm"
          asChild
          className="gap-2 text-muted-foreground hover:text-foreground"
        >
          <Link to="/monitoring">
            <ArrowLeft className="h-4 w-4" />
            Back to Monitoring
          </Link>
        </Button>
      </div>
      <div>
        <h1 className="text-2xl font-bold">Events</h1>
        <p className="text-muted-foreground text-sm mt-1">
          Infrastructure events and service checks
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
