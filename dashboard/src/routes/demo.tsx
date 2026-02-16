// Moneat - Mobile-First Error Monitoring Platform
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

import { createFileRoute, redirect } from '@tanstack/react-router'
import { api } from '@/lib/api'
import { setDemoEpoch } from '@/lib/demo'

export const Route = createFileRoute('/demo')({
  beforeLoad: async () => {
    try {
      // Call demo login endpoint - backend will set httpOnly cookie automatically
      const response = await api.demoLogin()
      
      // Set the demo epoch for time virtualization
      setDemoEpoch(response.demoEpochMs)
      
      // Small delay to ensure cookie is set before redirect
      await new Promise(resolve => setTimeout(resolve, 100))
    } catch (error) {
      console.error('Demo login failed:', error)
      // Redirect to login on failure
      throw redirect({ to: '/login', search: { error: 'demo_failed' } })
    }
    
    // Redirect to the projects page on success (specific authenticated route)
    throw redirect({ to: '/projects' })
  },
  component: () => {
    // This component should never render due to beforeLoad redirect
    return <div>Loading demo...</div>
  },
})
