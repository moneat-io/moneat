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

import {Badge} from '@/components/ui/badge'
import {CheckCircle2, Clock, Pause, XCircle} from 'lucide-react'

export function getStatusBadge(status: string) {
  switch (status) {
    case 'up':
      return <Badge variant="success"><CheckCircle2 className="mr-1 h-3 w-3" />Up</Badge>
    case 'down':
      return <Badge variant="danger"><XCircle className="mr-1 h-3 w-3" />Down</Badge>
    case 'paused':
      return <Badge variant="neutral"><Pause className="mr-1 h-3 w-3" />Paused</Badge>
    case 'pending':
    default:
      return <Badge variant="info"><Clock className="mr-1 h-3 w-3" />Pending</Badge>
  }
}
