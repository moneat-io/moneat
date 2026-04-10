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

package com.moneat.utils

import io.lettuce.core.RedisException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.SerializationException
import mu.KotlinLogging
import java.io.IOException
import java.sql.SQLException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class RecoverOnExpectedFailuresTest {

    private val logger = KotlinLogging.logger {}

    // ──── Happy path ────

    @Test
    fun `returns block result when no exception is thrown`() = runBlocking {
        val result = recoverOnExpectedFailures(logger, "op", "fallback") { "success" }
        assertEquals("success", result)
    }

    @Test
    fun `works with non-string fallback type`() = runBlocking {
        val result = recoverOnExpectedFailures(logger, "op", -1) { 42 }
        assertEquals(42, result)
    }

    // ──── CancellationException propagates ────

    @Test
    fun `rethrows CancellationException`() {
        assertFailsWith<CancellationException> {
            runBlocking {
                recoverOnExpectedFailures(logger, "op", "fallback") {
                    throw CancellationException("cancelled")
                }
            }
        }
    }

    // ──── Expected failure types return fallback ────

    @Test
    fun `returns fallback on IOException`() = runBlocking {
        val result = recoverOnExpectedFailures(logger, "op", "fallback") {
            throw IOException("io error")
        }
        assertEquals("fallback", result)
    }

    @Test
    fun `returns fallback on SQLException`() = runBlocking {
        val result = recoverOnExpectedFailures(logger, "op", "fallback") {
            throw SQLException("sql error")
        }
        assertEquals("fallback", result)
    }

    @Test
    fun `returns fallback on SerializationException`() = runBlocking {
        val result = recoverOnExpectedFailures(logger, "op", "fallback") {
            throw SerializationException("json error")
        }
        assertEquals("fallback", result)
    }

    @Test
    fun `returns fallback on IllegalStateException`() = runBlocking {
        val result = recoverOnExpectedFailures(logger, "op", "fallback") {
            throw IllegalStateException("bad state")
        }
        assertEquals("fallback", result)
    }

    @Test
    fun `returns fallback on IllegalArgumentException`() = runBlocking {
        val result = recoverOnExpectedFailures(logger, "op", "fallback") {
            throw IllegalArgumentException("bad arg")
        }
        assertEquals("fallback", result)
    }

    @Test
    fun `returns fallback on RedisException`() = runBlocking {
        val result = recoverOnExpectedFailures(logger, "op", "fallback") {
            throw RedisException("redis error")
        }
        assertEquals("fallback", result)
    }
}
