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
import {BookOpen, ShieldCheck, Zap} from 'lucide-react'

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
      <div className="flex items-center justify-between">
        <div className="flex items-center gap-3">
          <div className="flex items-center justify-center h-10 w-10 rounded-lg bg-gradient-to-br from-rose-500 to-pink-600">
            <Zap className="h-5 w-5 text-white" />
          </div>
          <div>
            <h1 className="text-2xl font-bold tracking-tight">Events</h1>
            <p className="text-muted-foreground mt-1">Agent events and service checks</p>
          </div>
        </div>
        <a href="/docs/datadog-agent/" target="_blank" rel="noreferrer"
          className="inline-flex items-center gap-1.5 text-sm text-muted-foreground hover:text-foreground transition-colors">
          <BookOpen className="h-4 w-4" />
          View docs
        </a>
      </div>
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
