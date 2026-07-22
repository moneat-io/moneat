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

package com.moneat.ingestion.queue

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals

class IngestionBatchLimiterTest {
    @Test
    fun `limits concurrent ingestion batches to configured default`() = runBlocking {
        val active = AtomicInteger()
        val maximum = AtomicInteger()

        (1..6).map {
            async(Dispatchers.Default) {
                IngestionBatchLimiter.withPermit {
                    val current = active.incrementAndGet()
                    maximum.accumulateAndGet(current, ::maxOf)
                    delay(50)
                    active.decrementAndGet()
                }
            }
        }.awaitAll()

        assertEquals(2, maximum.get())
    }
}
