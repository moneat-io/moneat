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

package com.moneat.config

import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText

/** Treat failed HTTP status or ClickHouse error bodies as failures (for [com.moneat.utils.suspendRunCatching]). */
internal suspend fun requireClickHouse2xx(response: HttpResponse, context: String) {
    val body = response.bodyAsText()
    check(!response.isClickHouseError(body)) {
        "$context failed: HTTP ${response.status.value}: ${body.take(200)}"
    }
}
