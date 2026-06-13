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

package com.moneat.datadog

import com.moneat.monitoring.OperationalMetrics
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertContains

class DatadogModuleMetricsTest {
    @BeforeTest
    fun resetBefore() {
        OperationalMetrics.resetForTest()
    }

    @AfterTest
    fun resetAfter() {
        OperationalMetrics.resetForTest()
    }

    @Test
    fun `registerQueueMetrics registers datadog queue gauges`() {
        val method = DatadogModule::class.java.getDeclaredMethod("registerQueueMetrics")
        method.isAccessible = true

        method.invoke(DatadogModule())

        val rendered = OperationalMetrics.scrape()
        assertContains(rendered, "moneat_worker_queue_depth")
        assertContains(rendered, "worker=\"Trace\"")
        assertContains(rendered, "worker=\"DD metric\"")
        assertContains(rendered, "worker=\"DD event\"")
        assertContains(rendered, "worker=\"DD infra\"")
        assertContains(rendered, "worker=\"Security\"")
        assertContains(rendered, "queue_type=\"primary\"")
        assertContains(rendered, "queue_type=\"dlq\"")
    }
}
