// Moneat - observability platform
// Copyright (C) 2026 Moneat
//
// This program is free software: you can redistribute it and/or modify
// it under the terms of the GNU Affero General Public License as published by
// the Free Software Foundation, either version 3 of the License, or
// (at your option) any later version.

import {createFileRoute, Link, redirect} from '@tanstack/react-router'
import {api} from '@/lib/api'
import {ContainerList} from '@/components/monitoring/ContainerList'
import {ArrowLeft} from 'lucide-react'
import {Button} from '@/components/ui/button'

export const Route = createFileRoute('/monitoring/containers')({
  beforeLoad: () => {
    if (!api.isAuthenticated()) {
      throw redirect({to: '/login'})
    }
  },
  component: MonitoringContainersPage,
})

function MonitoringContainersPage() {
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
        <h1 className="text-2xl font-bold">Containers</h1>
        <p className="text-muted-foreground text-sm mt-1">
          All containers across your infrastructure
        </p>
      </div>

      <ContainerList />
    </div>
  )
}
