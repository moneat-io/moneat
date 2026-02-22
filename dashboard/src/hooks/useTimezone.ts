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

import {useQuery, useQueryClient} from '@tanstack/react-query'
import {api} from '@/lib/api'
import {browserTimezone} from '@/lib/date-format'

/**
 * Returns the user's timezone preference (from their profile) with a fallback to the browser timezone.
 * Also provides updateTimezone() to persist a new preference.
 */
export function useTimezone(): { timezone: string; updateTimezone: (tz: string | null) => Promise<void> } {
  const queryClient = useQueryClient()

  const { data: user } = useQuery({
    queryKey: ['currentUser'],
    queryFn: () => api.getCurrentUser(),
    enabled: api.isAuthenticated(),
    staleTime: 5 * 60 * 1000,
  })

  const timezone = user?.timezone ?? browserTimezone()

  async function updateTimezone(tz: string | null) {
    await api.updateUserTimezone(tz)
    queryClient.invalidateQueries({ queryKey: ['currentUser'] })
  }

  return { timezone, updateTimezone }
}
