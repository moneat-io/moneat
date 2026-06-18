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

package com.moneat.logs

/**
 * Permission keys owned by the logs domain. When advanced RBAC is available
 * these keys are resolved by the shared permission bridge; otherwise routes
 * fall back to coarse organization roles.
 */
object LogPermissions {
    const val READ = "logs:read"
    const val MANAGE = "logs:manage"
    const val LIVE_TAIL = "logs:live-tail"
    const val METRICS = "logs:metrics"
    const val MONITORS = "logs:monitors"
}
