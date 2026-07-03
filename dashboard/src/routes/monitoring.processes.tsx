// Moneat - observability platform
// Copyright (C) 2026 Moneat
//
// This program is free software: you can redistribute it and/or modify
// it under the terms of the GNU Affero General Public License as published by
// the Free Software Foundation, either version 3 of the License, or
// (at your option) any later version.

import {createFileRoute} from '@tanstack/react-router'
import {ProcessExplorer} from '@/components/monitoring/ProcessExplorer'

export const Route = createFileRoute('/monitoring/processes')({
  component: MonitoringProcessesPage,
})

function MonitoringProcessesPage() {
  return (
    <div className="container mx-auto px-4 py-4 space-y-4">
      <ProcessExplorer />
    </div>
  )
}
