// Moneat - observability platform
// Copyright (C) 2026 Moneat
//
// This program is free software: you can redistribute it and/or modify
// it under the terms of the GNU Affero General Public License as published by
// the Free Software Foundation, either version 3 of the License, or
// (at your option) any later version.
//
// This program is distributed in the hope that it will be useful,
// but WITHOUT ANY WARRANTY; without even the implied warranty of
// MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the
// GNU Affero General Public License for more details.
//
// You should have received a copy of the GNU Affero General Public License
// along with this program. If not, see <https://www.gnu.org/licenses/>.

// The Infrastructure landing is the source-neutral Resource Catalog: a unified
// inventory of hosts, containers, pods, services, cloud resources and network
// devices. Per-kind browsing is a facet of the catalog; the classic host
// inventory now lives at /monitoring/hosts.

import {createFileRoute, redirect} from '@tanstack/react-router'
import {api} from '@/lib/api'
import {ResourceCatalog} from '@/components/monitoring/catalog/ResourceCatalog'

export const Route = createFileRoute('/monitoring/')({
  beforeLoad: () => {
    if (!api.isAuthenticated()) {
      throw redirect({to: '/login'})
    }
  },
  component: ResourceCatalog,
})
