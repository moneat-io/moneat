// Moneat - observability platform
// Copyright (C) 2026 Moneat
//
// This program is free software: you can redistribute it and/or modify
// it under the terms of the GNU Affero General Public License as published by
// the Free Software Foundation, either version 3 of the License, or
// (at your option) any later version.

import {createFileRoute} from '@tanstack/react-router'
import {ServiceExplorer} from '@/components/profiling/ServiceExplorer'

export const Route = createFileRoute('/profiles/service/$service')({
  component: ServiceExplorerPage,
})

function ServiceExplorerPage() {
  const {service} = Route.useParams()
  return <ServiceExplorer service={service} />
}
