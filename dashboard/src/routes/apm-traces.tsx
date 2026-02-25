// Moneat - observability platform
// Copyright (C) 2026 Moneat
//
// This program is free software: you can redistribute it and/or modify
// it under the terms of the GNU Affero General Public License as published by
// the Free Software Foundation, either version 3 of the License, or
// (at your option) any later version.

import {createFileRoute, Outlet, redirect} from '@tanstack/react-router'
import {api} from '@/lib/api'

export const Route = createFileRoute('/apm-traces')({
  beforeLoad: async () => {
    if (!api.isAuthenticated()) {
      throw redirect({to: '/login'})
    }
  },
  component: ApmTracesLayout,
})

function ApmTracesLayout() {
  return <Outlet />
}
