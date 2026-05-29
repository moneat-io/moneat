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

package com.moneat.datadog.services

import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockkObject
import io.mockk.unmockkObject
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ProfileMergeServiceTest {

    @BeforeTest
    fun setup() {
        mockkObject(ProfileIngestionService)
        mockkObject(ProfileStorageService)
        mockkObject(ProfileFlamegraphParser)
    }

    @AfterTest
    fun teardown() {
        unmockkObject(ProfileFlamegraphParser)
        unmockkObject(ProfileStorageService)
        unmockkObject(ProfileIngestionService)
    }

    private fun singleProfileFlamegraph(): JsonObject = buildJsonObject {
        put(
            "frames",
            buildJsonArray {
                add(
                    buildJsonObject {
                        put("name", "main")
                        put("value", 10)
                        put(
                            "children",
                            buildJsonArray {
                                add(
                                    buildJsonObject {
                                        put("name", "work")
                                        put("value", 6)
                                        put("children", buildJsonArray {})
                                    },
                                )
                            },
                        )
                    },
                )
            },
        )
        put(
            "sampleTypes",
            buildJsonArray {
                add(
                    buildJsonObject {
                        put("key", "cpu")
                        put("label", "CPU")
                        put("unit", "samples")
                    },
                )
            },
        )
        put(
            "threads",
            buildJsonArray {
                add(
                    buildJsonObject {
                        put("id", "t1")
                        put("label", "main")
                        put("samples", 10)
                    },
                )
            },
        )
        put("selectedSampleType", "cpu")
        put("unit", "samples")
        put("totalSamples", 10)
    }

    private fun mergeQuery(
        sampleType: String? = null,
    ) = ProfileMergeFlamegraphQuery(
        organizationId = 1,
        filters = ProfileQueryFilters(service = "api", profileType = "jfr"),
        window = ProfileTimeWindow(fromMs = 0, toMs = 1000),
        sampleType = sampleType,
        thread = null,
        maxProfiles = 25,
    )

    @Test
    fun `mergeFlamegraph sums frame values and unions dimensions across profiles`() {
        coEvery {
            ProfileIngestionService.selectProfilesForMerge(any())
        } returns ProfileMergeSelection(
            totalInWindow = 2,
            candidates = listOf(
                ProfileMergeCandidate("p1", "key1", "jfr", "datadog"),
                ProfileMergeCandidate("p2", "key2", "jfr", "datadog"),
            ),
        )
        every { ProfileStorageService.read(any()) } returns "payload".toByteArray()
        every {
            ProfileFlamegraphParser.parse(any(), any(), any(), any(), any())
        } returns singleProfileFlamegraph()

        val out = runBlocking {
            ProfileMergeService.mergeFlamegraph(mergeQuery(sampleType = "cpu"))
        }

        assertEquals(2, out["mergedCount"]!!.jsonPrimitive.int)
        assertEquals(2, out["totalCount"]!!.jsonPrimitive.int)
        assertEquals(20, out["totalSamples"]!!.jsonPrimitive.int)

        val main = out["frames"]!!.jsonArray[0].jsonObject
        assertEquals("main", main["name"]!!.jsonPrimitive.content)
        assertEquals(20, main["value"]!!.jsonPrimitive.int)
        val work = main["children"]!!.jsonArray[0].jsonObject
        assertEquals(12, work["value"]!!.jsonPrimitive.int)

        // Threads are summed by id; sample types are unioned by key.
        val threads = out["threads"]!!.jsonArray
        assertEquals(1, threads.size)
        assertEquals(20, threads[0].jsonObject["samples"]!!.jsonPrimitive.int)
        val sampleTypes = out["sampleTypes"]!!.jsonArray
        assertEquals(1, sampleTypes.size)
        assertEquals("cpu", sampleTypes[0].jsonObject["key"]!!.jsonPrimitive.content)
    }

    @Test
    fun `mergeFlamegraph returns empty result when no profiles match`() {
        coEvery {
            ProfileIngestionService.selectProfilesForMerge(any())
        } returns ProfileMergeSelection(totalInWindow = 0, candidates = emptyList())

        val out = runBlocking {
            ProfileMergeService.mergeFlamegraph(mergeQuery())
        }

        assertTrue(out["frames"]!!.jsonArray.isEmpty())
        assertEquals(0, out["mergedCount"]!!.jsonPrimitive.int)
    }
}
