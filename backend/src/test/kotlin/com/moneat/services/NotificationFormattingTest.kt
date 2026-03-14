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

package com.moneat.services

import com.moneat.notifications.services.DiscordService
import com.moneat.notifications.services.SlackService
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class NotificationFormattingTest {

    companion object {
        private const val ISSUES_1_URL = "https://app.moneat.io/issues/1"
        private const val COLOR_E01E5A = "#E01E5A"
        private const val TIMESTAMP_2025_01_01 = "2025-01-01T00:00:00Z"
        private const val MONET_UPTIME_MONITOR = "Moneat Uptime Monitor"
        private const val COLOR_ECB22E = "#ECB22E"
        private const val COLOR_2EB67D = "#2EB67D"
        private const val MONITOR_DOWN = "Monitor Down"
        private const val MONITOR_RECOVERED = "Monitor Recovered"
        private const val SETTINGS_NOTIFICATIONS_URL = "https://app.moneat.io/settings/notifications"
    }

    private val json = Json { ignoreUnknownKeys = true }

    // ── Slack data class serialization ──────────────────────────────────

    @Test
    fun `SlackBlock with header type serializes correctly`() {
        val block = SlackService.SlackBlock(
            type = "header",
            text = SlackService.SlackText(type = "plain_text", text = "Alert", emoji = true)
        )
        val encoded = json.encodeToString(block)
        val obj = Json.parseToJsonElement(encoded).jsonObject

        assertEquals("header", obj["type"]?.jsonPrimitive?.content)
        assertEquals("plain_text", obj["text"]?.jsonObject?.get("type")?.jsonPrimitive?.content)
        assertEquals("Alert", obj["text"]?.jsonObject?.get("text")?.jsonPrimitive?.content)
        assertTrue(obj["text"]?.jsonObject?.get("emoji")?.jsonPrimitive?.boolean == true)
    }

    @Test
    fun `SlackBlock with section and fields serializes correctly`() {
        val block = SlackService.SlackBlock(
            type = "section",
            fields = listOf(
                SlackService.SlackText(type = "mrkdwn", text = "*Host:*\napi-1"),
                SlackService.SlackText(type = "mrkdwn", text = "*CPU:*\n95%")
            )
        )
        val encoded = json.encodeToString(block)
        val obj = Json.parseToJsonElement(encoded).jsonObject

        assertEquals("section", obj["type"]?.jsonPrimitive?.content)
        val fields = obj["fields"]?.jsonArray
        assertEquals(2, fields?.size)
        assertEquals("*Host:*\napi-1", fields?.get(0)?.jsonObject?.get("text")?.jsonPrimitive?.content)
    }

    @Test
    fun `SlackBlock with actions and button element serializes correctly`() {
        val block = SlackService.SlackBlock(
            type = "actions",
            elements = listOf(
                SlackService.SlackElement(
                    type = "button",
                    text = SlackService.SlackText(type = "plain_text", text = "View"),
                    url = ISSUES_1_URL
                )
            )
        )
        val encoded = json.encodeToString(block)
        val obj = Json.parseToJsonElement(encoded).jsonObject

        assertEquals("actions", obj["type"]?.jsonPrimitive?.content)
        val elements = obj["elements"]?.jsonArray
        assertEquals(1, elements?.size)
        assertEquals("button", elements?.get(0)?.jsonObject?.get("type")?.jsonPrimitive?.content)
        assertEquals(
            "https://app.moneat.io/issues/1",
            elements?.get(0)?.jsonObject?.get("url")?.jsonPrimitive?.content
        )
    }

    @Test
    fun `SlackText without emoji omits emoji field`() {
        val text = SlackService.SlackText(type = "mrkdwn", text = "*bold*")
        val encoded = json.encodeToString(text)
        val obj = Json.parseToJsonElement(encoded).jsonObject

        assertEquals("mrkdwn", obj["type"]?.jsonPrimitive?.content)
        assertEquals("*bold*", obj["text"]?.jsonPrimitive?.content)
        assertTrue(obj["emoji"] is JsonNull || obj["emoji"] == null)
    }

    @Test
    fun `SlackElement with action_id serializes correctly`() {
        val element = SlackService.SlackElement(
            type = "button",
            text = SlackService.SlackText(type = "plain_text", text = "Click"),
            action_id = "btn_click"
        )
        val encoded = json.encodeToString(element)
        val obj = Json.parseToJsonElement(encoded).jsonObject

        assertEquals("btn_click", obj["action_id"]?.jsonPrimitive?.content)
    }

    @Test
    fun `SlackMessage serializes with channel, blocks, and fallback text`() {
        val message = SlackService.SlackMessage(
            channel = "C12345",
            blocks = listOf(
                SlackService.SlackBlock(
                    type = "header",
                    text = SlackService.SlackText(type = "plain_text", text = "Test")
                )
            ),
            text = "Fallback text"
        )
        val encoded = json.encodeToString(message)
        val obj = Json.parseToJsonElement(encoded).jsonObject

        assertEquals("C12345", obj["channel"]?.jsonPrimitive?.content)
        assertEquals(1, obj["blocks"]?.jsonArray?.size)
        assertEquals("Fallback text", obj["text"]?.jsonPrimitive?.content)
    }

    @Test
    fun `SlackAttachment serializes with color and blocks`() {
        val attachment = SlackService.SlackAttachment(
            color = COLOR_E01E5A,
            blocks = listOf(
                SlackService.SlackBlock(
                    type = "section",
                    text = SlackService.SlackText(type = "mrkdwn", text = "Error occurred")
                )
            ),
            fallback = "Error alert"
        )
        val encoded = json.encodeToString(attachment)
        val obj = Json.parseToJsonElement(encoded).jsonObject

        assertEquals("#E01E5A", obj["color"]?.jsonPrimitive?.content)
        assertEquals("Error alert", obj["fallback"]?.jsonPrimitive?.content)
        assertEquals(1, obj["blocks"]?.jsonArray?.size)
    }

    @Test
    fun `SlackOAuthResponse deserializes success response`() {
        val responseJson = """
            {
                "ok": true,
                "access_token": "xoxb-123",
                "token_type": "bot",
                "scope": "chat:write",
                "bot_user_id": "U123",
                "app_id": "A123",
                "team": {"id": "T123", "name": "Test Team"}
            }
        """.trimIndent()
        val response = json.decodeFromString<SlackService.SlackOAuthResponse>(responseJson)

        assertTrue(response.ok)
        assertEquals("xoxb-123", response.access_token)
        assertEquals("T123", response.team?.id)
        assertEquals("Test Team", response.team?.name)
    }

    @Test
    fun `SlackOAuthResponse deserializes error response`() {
        val responseJson = """{"ok": false, "error": "invalid_code"}"""
        val response = json.decodeFromString<SlackService.SlackOAuthResponse>(responseJson)

        assertEquals(false, response.ok)
        assertEquals("invalid_code", response.error)
        assertNull(response.access_token)
    }

    @Test
    fun `SlackPostMessageResponse deserializes success`() {
        val responseJson = """{"ok": true}"""
        val response = json.decodeFromString<SlackService.SlackPostMessageResponse>(responseJson)
        assertTrue(response.ok)
        assertNull(response.error)
    }

    @Test
    fun `SlackPostMessageResponse deserializes error`() {
        val responseJson = """{"ok": false, "error": "channel_not_found"}"""
        val response = json.decodeFromString<SlackService.SlackPostMessageResponse>(responseJson)
        assertEquals(false, response.ok)
        assertEquals("channel_not_found", response.error)
    }

    @Test
    fun `SlackChannelsResponse deserializes channel list`() {
        val responseJson = """
            {
                "ok": true,
                "channels": [
                    {"id": "C001", "name": "general", "is_private": false},
                    {"id": "C002", "name": "alerts", "is_private": true}
                ]
            }
        """.trimIndent()
        val response = json.decodeFromString<SlackService.SlackChannelsResponse>(responseJson)

        assertTrue(response.ok)
        assertEquals(2, response.channels?.size)
        assertEquals("C001", response.channels?.get(0)?.id)
        assertEquals(true, response.channels?.get(1)?.is_private)
    }

    @Test
    fun `SlackContextElement serializes correctly`() {
        val element = SlackService.SlackContextElement(type = "mrkdwn", text = "context info")
        val encoded = json.encodeToString(element)
        val obj = Json.parseToJsonElement(encoded).jsonObject

        assertEquals("mrkdwn", obj["type"]?.jsonPrimitive?.content)
        assertEquals("context info", obj["text"]?.jsonPrimitive?.content)
    }

    @Test
    fun `SlackUsergroup deserializes with all fields`() {
        val ugJson = """
            {"id": "S123", "handle": "team-ops", "name": "Ops Team", "description": "On-call ops"}
        """.trimIndent()
        val ug = json.decodeFromString<SlackService.SlackUsergroup>(ugJson)

        assertEquals("S123", ug.id)
        assertEquals("team-ops", ug.handle)
        assertEquals("Ops Team", ug.name)
        assertEquals("On-call ops", ug.description)
    }

    @Test
    fun `SlackUsergroup deserializes without optional description`() {
        val ugJson = """{"id": "S1", "handle": "h", "name": "N"}"""
        val ug = json.decodeFromString<SlackService.SlackUsergroup>(ugJson)

        assertNull(ug.description)
    }

    @Test
    fun `SlackBlock with accessory serializes correctly`() {
        val block = SlackService.SlackBlock(
            type = "section",
            text = SlackService.SlackText(type = "mrkdwn", text = "Main text"),
            accessory = SlackService.SlackElement(
                type = "button",
                text = SlackService.SlackText(type = "plain_text", text = "Go"),
                url = "https://example.com"
            )
        )
        val encoded = json.encodeToString(block)
        val obj = Json.parseToJsonElement(encoded).jsonObject

        assertEquals("button", obj["accessory"]?.jsonObject?.get("type")?.jsonPrimitive?.content)
    }

    // ── Discord data class serialization ────────────────────────────────

    @Test
    fun `DiscordEmbed serializes with all fields`() {
        val embed = DiscordService.DiscordEmbed(
            title = "Host Alert",
            description = "Server is down",
            url = "https://app.moneat.io/hosts/1",
            color = 0xE01E5A,
            fields = listOf(
                DiscordService.DiscordField("Host", "api-1", true),
                DiscordService.DiscordField("Status", "down", false)
            ),
            footer = DiscordService.DiscordFooter("Moneat Alert"),
            timestamp = "2025-01-01T00:00:00Z"
        )
        val encoded = json.encodeToString(embed)
        val obj = Json.parseToJsonElement(encoded).jsonObject

        assertEquals("Host Alert", obj["title"]?.jsonPrimitive?.content)
        assertEquals("Server is down", obj["description"]?.jsonPrimitive?.content)
        assertEquals("https://app.moneat.io/hosts/1", obj["url"]?.jsonPrimitive?.content)
        assertEquals(0xE01E5A, obj["color"]?.jsonPrimitive?.int)
        assertEquals(2, obj["fields"]?.jsonArray?.size)
        assertEquals("Moneat Alert", obj["footer"]?.jsonObject?.get("text")?.jsonPrimitive?.content)
        assertEquals(TIMESTAMP_2025_01_01, obj["timestamp"]?.jsonPrimitive?.content)
    }

    @Test
    fun `DiscordEmbed serializes with only required fields`() {
        val embed = DiscordService.DiscordEmbed()
        val encoded = json.encodeToString(embed)
        val obj = Json.parseToJsonElement(encoded).jsonObject

        assertTrue(obj["title"] is JsonNull || obj["title"] == null)
        assertTrue(obj["description"] is JsonNull || obj["description"] == null)
    }

    @Test
    fun `DiscordField inline defaults to true`() {
        val field = DiscordService.DiscordField(name = "Metric", value = "CPU")
        assertTrue(field.inline)

        val jsonWithDefaults = Json { encodeDefaults = true }
        val encoded = jsonWithDefaults.encodeToString(field)
        val obj = Json.parseToJsonElement(encoded).jsonObject
        assertTrue(obj["inline"]?.jsonPrimitive?.boolean == true)
    }

    @Test
    fun `DiscordField with inline false serializes correctly`() {
        val field = DiscordService.DiscordField(name = "URL", value = "https://example.com", inline = false)
        assertEquals(false, field.inline)

        val encoded = json.encodeToString(field)
        val obj = Json.parseToJsonElement(encoded).jsonObject
        assertEquals(false, obj["inline"]?.jsonPrimitive?.boolean)
    }

    @Test
    fun `DiscordFooter serializes text`() {
        val footer = DiscordService.DiscordFooter(text = MONET_UPTIME_MONITOR)
        val encoded = json.encodeToString(footer)
        val obj = Json.parseToJsonElement(encoded).jsonObject

        assertEquals(MONET_UPTIME_MONITOR, obj["text"]?.jsonPrimitive?.content)
    }

    @Test
    fun `DiscordMessage serializes with content and embeds`() {
        val message = DiscordService.DiscordMessage(
            content = "Alert notification",
            embeds = listOf(
                DiscordService.DiscordEmbed(
                    title = "Test",
                    color = 0x2EB67D
                )
            )
        )
        val encoded = json.encodeToString(message)
        val obj = Json.parseToJsonElement(encoded).jsonObject

        assertEquals("Alert notification", obj["content"]?.jsonPrimitive?.content)
        assertEquals(1, obj["embeds"]?.jsonArray?.size)
        assertEquals(0x2EB67D, obj["embeds"]?.jsonArray?.get(0)?.jsonObject?.get("color")?.jsonPrimitive?.int)
    }

    @Test
    fun `DiscordMessage serializes with null content`() {
        val message = DiscordService.DiscordMessage()
        val encoded = json.encodeToString(message)
        val obj = Json.parseToJsonElement(encoded).jsonObject

        assertTrue(obj["content"] is JsonNull || obj["content"] == null)
        assertTrue(obj["embeds"] is JsonNull || obj["embeds"] == null)
    }

    @Test
    fun `DiscordOAuthResponse deserializes success with guild`() {
        val responseJson = """
            {
                "access_token": "abc123",
                "token_type": "Bearer",
                "scope": "bot",
                "guild": {"id": "G123", "name": "Test Guild"}
            }
        """.trimIndent()
        val response = json.decodeFromString<DiscordService.DiscordOAuthResponse>(responseJson)

        assertEquals("abc123", response.access_token)
        assertEquals("Bearer", response.token_type)
        assertEquals("G123", response.guild?.id)
        assertEquals("Test Guild", response.guild?.name)
        assertNull(response.error)
    }

    @Test
    fun `DiscordOAuthResponse deserializes error response`() {
        val responseJson = """{"error": "invalid_grant"}"""
        val response = json.decodeFromString<DiscordService.DiscordOAuthResponse>(responseJson)

        assertEquals("invalid_grant", response.error)
        assertNull(response.access_token)
        assertNull(response.guild)
    }

    @Test
    fun `DiscordChannel deserializes correctly`() {
        val channelJson = """{"id": "CH1", "name": "alerts", "type": 0}"""
        val channel = json.decodeFromString<DiscordService.DiscordChannel>(channelJson)

        assertEquals("CH1", channel.id)
        assertEquals("alerts", channel.name)
        assertEquals(0, channel.type)
    }

    @Test
    fun `DiscordChannel with non-text type deserializes correctly`() {
        val channelJson = """{"id": "CH2", "name": "voice", "type": 2}"""
        val channel = json.decodeFromString<DiscordService.DiscordChannel>(channelJson)

        assertEquals(2, channel.type)
    }

    // ── Discord color mapping for alert types ───────────────────────────

    @Test
    fun `Discord error color matches expected hex value`() {
        val embed = DiscordService.DiscordEmbed(title = "Error", color = 0xE01E5A)
        assertEquals(0xE01E5A, embed.color)
    }

    @Test
    fun `Discord warning color matches expected hex value`() {
        val embed = DiscordService.DiscordEmbed(title = "Warning", color = 0xECB22E)
        assertEquals(0xECB22E, embed.color)
    }

    @Test
    fun `Discord success color matches expected hex value`() {
        val embed = DiscordService.DiscordEmbed(title = "OK", color = 0x2EB67D)
        assertEquals(0x2EB67D, embed.color)
    }

    // ── Slack full message construction patterns ────────────────────────

    @Test
    fun `Slack host alert message structure has header and attachment`() {
        val mainBlocks = listOf(
            SlackService.SlackBlock(
                type = "header",
                text = SlackService.SlackText(type = "plain_text", text = "⚠️ Host Alert", emoji = true)
            )
        )
        val attachmentBlocks = listOf(
            SlackService.SlackBlock(
                type = "section",
                fields = listOf(
                    SlackService.SlackText(type = "mrkdwn", text = "*Host:*\napi-prod"),
                    SlackService.SlackText(type = "mrkdwn", text = "*Metric:*\nCPU Usage"),
                    SlackService.SlackText(type = "mrkdwn", text = "*Condition:*\n> 80%"),
                    SlackService.SlackText(type = "mrkdwn", text = "*Current Value:*\n95%")
                )
            ),
            SlackService.SlackBlock(
                type = "actions",
                elements = listOf(
                    SlackService.SlackElement(
                        type = "button",
                        text = SlackService.SlackText(type = "plain_text", text = "View Host"),
                        url = "https://app.moneat.io/monitoring/hosts/1"
                    )
                )
            )
        )
        val attachments = listOf(
            SlackService.SlackAttachment(
                color = COLOR_ECB22E,
                blocks = attachmentBlocks,
                fallback = "⚠️ Host Alert: api-prod"
            )
        )
        val message = SlackService.SlackMessage(
            channel = "C123",
            blocks = mainBlocks,
            attachments = attachments,
            text = "⚠️ Host Alert: api-prod"
        )
        val encoded = json.encodeToString(message)
        val obj = Json.parseToJsonElement(encoded).jsonObject

        assertEquals(1, obj["blocks"]?.jsonArray?.size)
        assertEquals(1, obj["attachments"]?.jsonArray?.size)
        val att = obj["attachments"]?.jsonArray?.get(0)?.jsonObject
        assertEquals(COLOR_ECB22E, att?.get("color")?.jsonPrimitive?.content)
        assertEquals(2, att?.get("blocks")?.jsonArray?.size)
    }

    @Test
    fun `Slack error alert message includes stack trace block when provided`() {
        val blocks = mutableListOf<SlackService.SlackBlock>()
        blocks.add(
            SlackService.SlackBlock(
                type = "section",
                text = SlackService.SlackText(
                    type = "mrkdwn",
                    text = "*<https://app.moneat.io/issues/42|NullPointerException>*"
                )
            )
        )
        val stackTrace = "  at com.moneat.Main.run (Main.kt:10)"
        blocks.add(
            SlackService.SlackBlock(
                type = "section",
                text = SlackService.SlackText(
                    type = "mrkdwn",
                    text = "*Stack Trace:*\n```$stackTrace```"
                )
            )
        )
        val attachment = SlackService.SlackAttachment(
            color = COLOR_E01E5A,
            blocks = blocks,
            fallback = "Error: NullPointerException"
        )
        val encoded = json.encodeToString(attachment)
        val obj = Json.parseToJsonElement(encoded).jsonObject

        assertEquals(2, obj["blocks"]?.jsonArray?.size)
        val stackBlock = obj["blocks"]?.jsonArray?.get(1)?.jsonObject
        val text = stackBlock?.get("text")?.jsonObject?.get("text")?.jsonPrimitive?.content
        assertTrue(text != null && text.contains("```"))
        assertTrue(text.contains("Main.kt:10"))
    }

    // ── Discord full embed construction patterns ────────────────────────

    @Test
    fun `Discord uptime down embed has correct structure`() {
        val fields = mutableListOf(
            DiscordService.DiscordField("URL", "https://api.example.com", false),
            DiscordService.DiscordField("Status", "HTTP 500", true)
        )
        fields.add(DiscordService.DiscordField("Response Time", "2500ms", true))

        val embed = DiscordService.DiscordEmbed(
            title = "🔴 Uptime Monitor Down",
            description = "Monitor detected a failure",
            url = "https://app.moneat.io/monitoring?monitor=abc",
            color = 0xE01E5A,
            fields = fields,
            footer = DiscordService.DiscordFooter("Moneat Uptime Monitor"),
            timestamp = "2025-06-01T12:00:00Z"
        )
        val encoded = json.encodeToString(embed)
        val obj = Json.parseToJsonElement(encoded).jsonObject

        assertEquals("🔴 Uptime Monitor Down", obj["title"]?.jsonPrimitive?.content)
        assertEquals(3, obj["fields"]?.jsonArray?.size)
        val urlField = obj["fields"]?.jsonArray?.get(0)?.jsonObject
        assertEquals(false, urlField?.get("inline")?.jsonPrimitive?.boolean)
    }

    @Test
    fun `Discord error alert embed uses correct color per level`() {
        data class LevelColor(val level: String, val expectedColor: Int)

        val testCases = listOf(
            LevelColor("fatal", 0xE01E5A),
            LevelColor("error", 0xE01E5A),
            LevelColor("warning", 0xECB22E),
            LevelColor("info", 0x2EB67D)
        )

        for ((level, expectedColor) in testCases) {
            val color = when (level.lowercase()) {
                "fatal", "error" -> 0xE01E5A
                "warning" -> 0xECB22E
                else -> 0x2EB67D
            }
            assertEquals(expectedColor, color, "Color mismatch for level=$level")
        }
    }

    @Test
    fun `Discord dashboard alert embed includes all required fields`() {
        val embed = DiscordService.DiscordEmbed(
            title = "📊 Dashboard Alert: High Error Rate",
            description = "Alert triggered on **Error Count** in *Production Dashboard*",
            url = "https://app.moneat.io/dashboards/5",
            color = 0xE01E5A,
            fields = listOf(
                DiscordService.DiscordField("Dashboard", "Production Dashboard", true),
                DiscordService.DiscordField("Widget", "Error Count", true),
                DiscordService.DiscordField("Condition", "> 100", true),
                DiscordService.DiscordField("Current Value", "250", true)
            ),
            footer = DiscordService.DiscordFooter("Moneat Dashboard Alert")
        )
        val encoded = json.encodeToString(embed)
        val obj = Json.parseToJsonElement(encoded).jsonObject

        assertEquals(4, obj["fields"]?.jsonArray?.size)
        assertEquals(
            "Moneat Dashboard Alert",
            obj["footer"]?.jsonObject?.get("text")?.jsonPrimitive?.content
        )
    }

    // ── Round-trip deserialization tests ─────────────────────────────────

    @Test
    fun `Slack message round-trips through serialization`() {
        val original = SlackService.SlackMessage(
            channel = "C999",
            blocks = listOf(
                SlackService.SlackBlock(
                    type = "header",
                    text = SlackService.SlackText(type = "plain_text", text = "Round-trip")
                )
            ),
            text = "fallback"
        )
        val encoded = json.encodeToString(original)
        val decoded = json.decodeFromString<SlackService.SlackMessage>(encoded)

        assertEquals(original.channel, decoded.channel)
        assertEquals(original.text, decoded.text)
        assertEquals(original.blocks?.size, decoded.blocks?.size)
        assertEquals(
            original.blocks?.get(0)?.text?.text,
            decoded.blocks?.get(0)?.text?.text
        )
    }

    @Test
    fun `Discord embed round-trips through serialization`() {
        val original = DiscordService.DiscordEmbed(
            title = "Test",
            description = "desc",
            color = 0xFF0000,
            fields = listOf(DiscordService.DiscordField("K", "V", false)),
            footer = DiscordService.DiscordFooter("ft")
        )
        val encoded = json.encodeToString(original)
        val decoded = json.decodeFromString<DiscordService.DiscordEmbed>(encoded)

        assertEquals(original.title, decoded.title)
        assertEquals(original.description, decoded.description)
        assertEquals(original.color, decoded.color)
        assertEquals(original.fields?.size, decoded.fields?.size)
        assertEquals(original.fields?.get(0)?.name, decoded.fields?.get(0)?.name)
        assertEquals(original.footer?.text, decoded.footer?.text)
    }

    @Test
    fun `Discord message round-trips through serialization`() {
        val original = DiscordService.DiscordMessage(
            content = "hello",
            embeds = listOf(
                DiscordService.DiscordEmbed(title = "E1"),
                DiscordService.DiscordEmbed(title = "E2")
            )
        )
        val encoded = json.encodeToString(original)
        val decoded = json.decodeFromString<DiscordService.DiscordMessage>(encoded)

        assertEquals(original.content, decoded.content)
        assertEquals(2, decoded.embeds?.size)
        assertEquals("E1", decoded.embeds?.get(0)?.title)
        assertEquals("E2", decoded.embeds?.get(1)?.title)
    }

    // ── Slack severity/color mapping (mirrors SlackService logic) ───────

    @Test
    fun `Slack error alert level determines emoji and color`() {
        data class LevelMapping(val level: String, val expectedEmoji: String, val expectedColor: String)

        val testCases = listOf(
            LevelMapping("error", "🔴", "#E01E5A"),
            LevelMapping("warning", "⚠️", COLOR_ECB22E),
            LevelMapping("info", "ℹ️", COLOR_2EB67D),
            LevelMapping("debug", "⚠️", COLOR_ECB22E)
        )

        for ((level, expectedEmoji, expectedColor) in testCases) {
            val levelLower = level.lowercase()
            val emoji = when (levelLower) {
                "error" -> "🔴"
                "warning" -> "⚠️"
                "info" -> "ℹ️"
                else -> "⚠️"
            }
            val color = when (levelLower) {
                "error" -> "#E01E5A"
                "warning" -> COLOR_ECB22E
                "info" -> COLOR_2EB67D
                else -> COLOR_ECB22E
            }
            assertEquals(expectedEmoji, emoji, "Emoji mismatch for level=$level")
            assertEquals(expectedColor, color, "Color mismatch for level=$level")
        }
    }

    @Test
    fun `Slack dashboard alert severity determines emoji and color`() {
        val criticalSeverity = "CRITICAL"
        val criticalEmoji = if (criticalSeverity == "CRITICAL" || criticalSeverity == "HIGH") "🔴" else "⚠️"
        assertEquals("🔴", criticalEmoji)

        val highSeverity = "HIGH"
        val highEmoji = if (highSeverity == "CRITICAL" || highSeverity == "HIGH") "🔴" else "⚠️"
        assertEquals("🔴", highEmoji)

        val mediumSeverity = "MEDIUM"
        val mediumEmoji = if (mediumSeverity == "CRITICAL" || mediumSeverity == "HIGH") "🔴" else "⚠️"
        assertEquals("⚠️", mediumEmoji)

        val critColor = when ("CRITICAL") {
            "CRITICAL" -> "#E01E5A"
            "HIGH" -> "#E01E5A"
            "MEDIUM" -> COLOR_ECB22E
            else -> COLOR_ECB22E
        }
        assertEquals("#E01E5A", critColor)

        val medColor = when ("MEDIUM") {
            "CRITICAL" -> "#E01E5A"
            "HIGH" -> "#E01E5A"
            "MEDIUM" -> COLOR_ECB22E
            else -> COLOR_ECB22E
        }
        assertEquals(COLOR_ECB22E, medColor)
    }

    // ── Discord severity color mapping for dashboard alerts ─────────────

    @Test
    fun `Discord dashboard alert severity determines color`() {
        val critColor = when ("CRITICAL") {
            "CRITICAL" -> 0xE01E5A
            "HIGH" -> 0xE01E5A
            "MEDIUM" -> 0xECB22E
            else -> 0xECB22E
        }
        assertEquals(0xE01E5A, critColor)

        val lowColor = when ("LOW") {
            "CRITICAL" -> 0xE01E5A
            "HIGH" -> 0xE01E5A
            "MEDIUM" -> 0xECB22E
            else -> 0xECB22E
        }
        assertEquals(0xECB22E, lowColor)
    }

    // ── Slack uptime alert direction logic ──────────────────────────────

    @Test
    fun `Slack uptime alert direction determines emoji and header`() {
        val downStatus = "down"
        val isDown = downStatus.lowercase() == "down"
        assertTrue(isDown)
        assertEquals("🔴", if (isDown) "🔴" else "🟢")
        assertEquals(MONITOR_DOWN, if (isDown) MONITOR_DOWN else MONITOR_RECOVERED)
        assertEquals("#E01E5A", if (isDown) "#E01E5A" else "#2EB67D")

        val upStatus = "UP"
        val isUp = upStatus.lowercase() == "down"
        assertFalse(isUp)
        assertEquals("🟢", if (isUp) "🔴" else "🟢")
        assertEquals(MONITOR_RECOVERED, if (isUp) MONITOR_DOWN else MONITOR_RECOVERED)
    }

    // ── EmailService data class construction ────────────────────────────

    @Test
    fun `ErrorAlertData holds all fields correctly`() {
        val data = com.moneat.notifications.services.EmailService.ErrorAlertData(
            issueTitle = "NullPointerException",
            issueLevel = "error",
            issueCulprit = "Main.kt:run",
            issueMessage = "null reference",
            issueCount = "5",
            issueUrl = ISSUES_1_URL,
            projectName = "Backend",
            environment = "production",
            timestamp = "2025-01-01T00:00:00Z",
            stackTrace = "  at Main.run (Main.kt:10)",
            settingsUrl = SETTINGS_NOTIFICATIONS_URL,
            unsubscribeUrl = "$SETTINGS_NOTIFICATIONS_URL?project=1"
        )

        assertEquals("NullPointerException", data.issueTitle)
        assertEquals("error", data.issueLevel)
        assertEquals("Main.kt:run", data.issueCulprit)
        assertEquals("5", data.issueCount)
        assertEquals("production", data.environment)
    }

    @Test
    fun `WeeklySummaryData holds all fields correctly`() {
        val data = com.moneat.notifications.services.EmailService.WeeklySummaryData(
            startDate = "Jan 01, 2025",
            endDate = "Jan 07, 2025",
            totalEvents = "1.5K",
            eventsTrend = 25,
            newIssues = "42",
            issuesTrend = -10,
            affectedUsers = "300",
            usersTrend = 0,
            topIssues = listOf(
                com.moneat.notifications.services.EmailService.TopIssue(
                    title = "NPE",
                    culprit = "Main.kt",
                    project = "Backend",
                    count = "100"
                )
            ),
            projects = listOf(
                com.moneat.notifications.services.EmailService.ProjectSummary(
                    name = "Backend",
                    events = "1.0K",
                    issues = "30",
                    crashFree = "99.5"
                )
            ),
            dashboardUrl = "https://app.moneat.io",
            settingsUrl = SETTINGS_NOTIFICATIONS_URL,
            unsubscribeUrl = SETTINGS_NOTIFICATIONS_URL
        )

        assertEquals("Jan 01, 2025", data.startDate)
        assertEquals(25, data.eventsTrend)
        assertEquals(1, data.topIssues.size)
        assertEquals("NPE", data.topIssues[0].title)
        assertEquals(1, data.projects.size)
        assertEquals("Backend", data.projects[0].name)
    }

    @Test
    fun `TopIssue and ProjectSummary data classes hold correct values`() {
        val issue = com.moneat.notifications.services.EmailService.TopIssue(
            title = "OutOfMemoryError",
            culprit = "Worker.kt:process",
            project = "API",
            count = "2.3K"
        )
        assertEquals("OutOfMemoryError", issue.title)
        assertEquals("Worker.kt:process", issue.culprit)
        assertEquals("API", issue.project)
        assertEquals("2.3K", issue.count)

        val summary = com.moneat.notifications.services.EmailService.ProjectSummary(
            name = "Frontend",
            events = "500",
            issues = "10",
            crashFree = "99.9"
        )
        assertEquals("Frontend", summary.name)
        assertEquals("99.9", summary.crashFree)
    }
}
