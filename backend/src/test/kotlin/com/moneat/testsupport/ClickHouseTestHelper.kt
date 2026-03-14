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

package com.moneat.testsupport

import com.moneat.config.ClickHouseClient
import com.sun.net.httpserver.HttpExchange

/**
 * Runs a block with a MockHttpServer and ClickHouseClient initialized against it.
 * Reduces duplication of the common test pattern:
 *   MockHttpServer { ... }.use { server ->
 *     ClickHouseClient.init(server.baseUrl, "test", "default", "")
 *     // test code
 *   }
 */
inline fun <T> withClickHouseMockServer(
    noinline handler: (HttpExchange) -> Unit,
    database: String = "test",
    user: String = "default",
    password: String = "",
    block: (MockHttpServer) -> T
): T {
    return MockHttpServer(handler).use { server ->
        ClickHouseClient.close()
        ClickHouseClient.init(server.baseUrl, database, user, password)
        block(server)
    }
}
