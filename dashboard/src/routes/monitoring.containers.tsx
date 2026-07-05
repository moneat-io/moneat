// Moneat - observability platform
// Copyright (C) 2026 Moneat
//
// This program is free software: you can redistribute it and/or modify
// it under the terms of the GNU Affero General Public License as published by
// the Free Software Foundation, either version 3 of the License, or
// (at your option) any later version.

import {createFileRoute} from '@tanstack/react-router'
import {ContainerList} from '@/components/monitoring/ContainerList'

export const Route = createFileRoute('/monitoring/containers')({
  component: MonitoringContainersPage,
})

function MonitoringContainersPage() {
  return (
    <div className="container mx-auto px-4 py-4 space-y-4">
      <ContainerList />
    </div>
  )
}
