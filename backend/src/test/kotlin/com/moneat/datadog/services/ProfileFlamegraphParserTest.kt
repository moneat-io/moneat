// Moneat - observability platform
// Copyright (C) 2026 Moneat
//
// This program is free software: you can redistribute it and/or modify
// it under the terms of the GNU Affero General Public License as published by
// the Free Software Foundation, either version 3 of the License, or
// (at your option) any later version.

package com.moneat.datadog.services

import com.moneat.datadog.buildJfrLikePayload
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ProfileFlamegraphParserTest {

    @Test
    fun `parse returns empty frames for unknown source`() {
        val result = ProfileFlamegraphParser.parse(
            source = "mystery",
            profileType = "cpu",
            data = "whatever".toByteArray(),
            sampleType = null,
            thread = null,
        )
        assertTrue(result["frames"]!!.jsonArray.isEmpty())
    }

    @Test
    fun `parse builds a frame tree from a sentry profile`() {
        val sentry = """
            {"profile":{
              "frames":[{"function":"f0"},{"function":"f1"}],
              "stacks":[[1,0]],
              "samples":[{"stack_id":0},{"stack_id":0}]
            }}
        """.trimIndent().toByteArray()

        val result = ProfileFlamegraphParser.parse(
            source = "sentry",
            profileType = "cpu",
            data = sentry,
            sampleType = null,
            thread = null,
        )

        val frames = result["frames"]!!.jsonArray
        assertEquals(1, frames.size)
        val f0 = frames[0].jsonObject
        assertEquals("f0", f0["name"]!!.jsonPrimitive.content)
        assertEquals(2, f0["value"]!!.jsonPrimitive.content.toInt())
        val f1 = f0["children"]!!.jsonArray[0].jsonObject
        assertEquals("f1", f1["name"]!!.jsonPrimitive.content)
    }

    @Test
    fun `parse returns empty frames for malformed sentry payload`() {
        val result = ProfileFlamegraphParser.parse(
            source = "sentry",
            profileType = "cpu",
            data = "not json".toByteArray(),
            sampleType = null,
            thread = null,
        )
        assertTrue(result["frames"]!!.jsonArray.isEmpty())
    }

    @Test
    fun `parse routes a jfr payload through the datadog parser without crashing`() {
        val result = ProfileFlamegraphParser.parse(
            source = "datadog",
            profileType = "jfr",
            data = buildJfrLikePayload(),
            sampleType = null,
            thread = null,
        )
        // A synthetic JFR payload yields no usable samples, but the call must
        // return the standard shape rather than throwing.
        assertTrue(result.containsKey("frames"))
    }
}
