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

import kotlinx.serialization.json.*
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class JsonResponsesTest {

    // MessageResponse tests
    @Test
    fun `MessageResponse serializes correctly`() {
        val response = MessageResponse("success")
        assertEquals("success", response.message)
    }

    @Test
    fun `MessageResponse handles empty message`() {
        val response = MessageResponse("")
        assertEquals("", response.message)
    }

    // ErrorResponse tests
    @Test
    fun `ErrorResponse serializes with error message`() {
        val response = ErrorResponse("something went wrong")
        assertEquals("something went wrong", response.error)
    }

    @Test
    fun `ErrorResponse allows null error`() {
        val response = ErrorResponse(null)
        assertEquals(null, response.error)
    }

    // DetailedErrorResponse tests
    @Test
    fun `DetailedErrorResponse has both error and message`() {
        val response = DetailedErrorResponse(error = "NOT_FOUND", message = "User not found")
        assertEquals("NOT_FOUND", response.error)
        assertEquals("User not found", response.message)
    }

    // BooleanResponse tests
    @Test
    fun `BooleanResponse with true`() {
        val response = BooleanResponse(available = true)
        assertTrue(response.available)
    }

    @Test
    fun `BooleanResponse with false`() {
        val response = BooleanResponse(available = false)
        assertFalse(response.available)
    }

    // DemoLoginResponse tests
    @Test
    fun `DemoLoginResponse has token and epochMs`() {
        val response = DemoLoginResponse(token = "jwt-token", demoEpochMs = 1700000000000L)
        assertEquals("jwt-token", response.token)
        assertEquals(1700000000000L, response.demoEpochMs)
    }

    // messageJson tests
    @Test
    fun `messageJson creates JsonObject with message key`() {
        val json = messageJson("hello")
        assertEquals("hello", json["message"]?.jsonPrimitive?.content)
    }

    @Test
    fun `messageJson handles special characters`() {
        val json = messageJson("hello \"world\" & <test>")
        assertEquals("hello \"world\" & <test>", json["message"]?.jsonPrimitive?.content)
    }

    // errorJson tests
    @Test
    fun `errorJson creates JsonObject with error key`() {
        val json = errorJson("bad request")
        assertEquals("bad request", json["error"]?.jsonPrimitive?.content)
    }

    // booleanJson tests
    @Test
    fun `booleanJson creates JsonObject with custom key and true`() {
        val json = booleanJson("exists", true)
        assertTrue(json["exists"]?.jsonPrimitive?.boolean == true)
    }

    @Test
    fun `booleanJson creates JsonObject with custom key and false`() {
        val json = booleanJson("exists", false)
        assertTrue(json["exists"]?.jsonPrimitive?.boolean == false)
    }

    @Test
    fun `booleanJson supports arbitrary key names`() {
        val json = booleanJson("isActive", true)
        assertTrue(json.containsKey("isActive"))
        assertTrue(json["isActive"]?.jsonPrimitive?.boolean == true)
    }
}
