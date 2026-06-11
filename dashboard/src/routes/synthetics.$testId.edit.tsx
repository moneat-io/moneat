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

import {createFileRoute} from '@tanstack/react-router'
import {useQuery} from '@tanstack/react-query'
import {api} from '@/lib/api'
import {SyntheticBuilder} from '@/components/synthetics/SyntheticBuilder'

export const Route = createFileRoute('/synthetics/$testId/edit')({
  component: EditSyntheticTest,
})

function EditSyntheticTest() {
  const {testId} = Route.useParams()
  const {data: test, isLoading} = useQuery({
    queryKey: ['synthetic-test', testId],
    queryFn: () => api.getSyntheticTest(testId),
  })
  if (isLoading) {
    return <div className="flex h-full items-center justify-center text-sm text-muted-foreground">Loading…</div>
  }
  if (!test) {
    return <div className="flex h-full items-center justify-center text-sm text-muted-foreground">Test not found</div>
  }
  return <SyntheticBuilder mode="edit" initial={test} />
}
