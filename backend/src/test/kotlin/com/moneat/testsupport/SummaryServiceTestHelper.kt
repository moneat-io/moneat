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
import com.moneat.monitor.services.MonitorService
import com.moneat.uptime.services.UptimeService
import io.mockk.every
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.transactions.TransactionManager

/**
 * Reduces duplication in SummaryService tests that use MockHttpServer + ClickHouse.
 * Handles common setup: ClickHouseClient init, TransactionManager, seedOrgAndProject,
 * and default empty mock returns for monitorService.listHosts and uptimeService.listMonitors.
 */
inline fun <T> withSummaryServiceMockServer(
    noinline handler: (HttpExchange) -> Unit,
    db: Database?,
    orgId: Int,
    seedOrgAndProject: () -> Unit,
    monitorService: MonitorService,
    uptimeService: UptimeService,
    block: (MockHttpServer) -> T
): T {
    return MockHttpServer(handler).use { server ->
        ClickHouseClient.close()
        ClickHouseClient.init(server.baseUrl, "test", "default", "")
        TransactionManager.defaultDatabase = db
        seedOrgAndProject()
        every { monitorService.listHosts(orgId) } returns emptyList()
        every { uptimeService.listMonitors(orgId) } returns emptyList()
        block(server)
    }
}
