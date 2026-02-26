// Moneat - observability platform
// Copyright (C) 2026 Moneat
//
// This program is free software: you can redistribute it and/or modify
// it under the terms of the GNU Affero General Public License as published by
// the Free Software Foundation, either version 3 of the License, or
// (at your option) any later version.

import {createFileRoute, redirect} from '@tanstack/react-router'
import {api} from '@/lib/api'
import {ProcessExplorer} from '@/components/monitoring/ProcessExplorer'

export const Route = createFileRoute('/monitoring/processes')({
  beforeLoad: () => {
    if (!api.isAuthenticated()) {
      throw redirect({to: '/login'})
    }
  },
  component: MonitoringProcessesPage,
})

function MonitoringProcessesPage() {
  return (
    <div className="container mx-auto px-4 py-4">
      <ProcessExplorer />
    </div>
  )
}
