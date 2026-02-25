// Moneat - observability platform
// Copyright (C) 2026 Moneat
//
// This program is free software: you can redistribute it and/or modify
// it under the terms of the GNU Affero General Public License as published by
// the Free Software Foundation, either version 3 of the License, or
// (at your option) any later version.

import {createFileRoute} from '@tanstack/react-router'
import {ProcessExplorer} from '@/components/datadog/ProcessExplorer'
import {ContainerList} from '@/components/datadog/ContainerList'
import {NetworkConnections} from '@/components/datadog/NetworkConnections'
import {Tabs, TabsContent, TabsList, TabsTrigger} from '@/components/ui/tabs'

export const Route = createFileRoute('/dd-infra/')({
  component: DdInfraIndexPage,
})

function DdInfraIndexPage() {
  return (
    <div className="p-6 space-y-6">
      <div>
        <h1 className="text-2xl font-bold">DD-Compatible Infrastructure</h1>
        <p className="text-muted-foreground text-sm mt-1">
          Process, container, and network monitoring from DD-compatible agents
        </p>
      </div>

      <Tabs defaultValue="processes">
        <TabsList>
          <TabsTrigger value="processes">Processes</TabsTrigger>
          <TabsTrigger value="containers">Containers</TabsTrigger>
          <TabsTrigger value="connections">Network</TabsTrigger>
        </TabsList>
        <TabsContent value="processes" className="mt-4">
          <ProcessExplorer />
        </TabsContent>
        <TabsContent value="containers" className="mt-4">
          <ContainerList />
        </TabsContent>
        <TabsContent value="connections" className="mt-4">
          <NetworkConnections />
        </TabsContent>
      </Tabs>
    </div>
  )
}
