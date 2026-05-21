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

import com.moneat.datadog.models.DatadogHostMeta
import com.moneat.datadog.models.DatadogHostMetadata
import com.moneat.datadog.models.DatadogIntakePayload
import com.moneat.monitor.services.MonitorService
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import mu.KotlinLogging
import org.jetbrains.exposed.v1.core.Column
import org.jetbrains.exposed.v1.core.ColumnType
import org.jetbrains.exposed.v1.core.Table
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.datetime.timestamp
import org.jetbrains.exposed.v1.jdbc.deleteWhere
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.TransactionManager
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.jetbrains.exposed.v1.jdbc.update
import org.koin.core.context.GlobalContext
import org.postgresql.util.PGobject
import kotlin.time.Clock
import kotlin.time.Duration.Companion.minutes
import com.moneat.utils.suspendRunCatching

private val logger = KotlinLogging.logger {}
private const val BYTES_PER_KB = 1024L

/** Parse cpu_cores and processor name from the gohai JSON string sent by the DD agent. */
private fun parseCpuCoresFromGohai(gohai: String): Int {
    if (gohai.isBlank()) return 0
    return suspendRunCatching {
        val root = Json.parseToJsonElement(gohai).jsonObject
        val cpu = root["cpu"]?.jsonObject ?: return 0
        cpu["cpu_logical_processors"]?.jsonPrimitive?.content?.toIntOrNull()
            ?: cpu["cpu_cores"]?.jsonPrimitive?.content?.toIntOrNull()
            ?: 0
    }.getOrElse { _ ->
        0
    }
}

/** Parse total memory in KB from the gohai JSON string sent by the DD agent. */
private fun parseMemoryKbFromGohai(gohai: String): Long {
    if (gohai.isBlank()) return 0L
    return suspendRunCatching {
        val root = Json.parseToJsonElement(gohai).jsonObject
        val memory = root["memory"]?.jsonObject ?: return 0L
        val totalBytes = memory["total"]?.jsonPrimitive?.content?.toLongOrNull() ?: return 0L
        totalBytes / BYTES_PER_KB
    }.getOrElse { _ ->
        0L
    }
}

private fun parseProcessorFromGohai(gohai: String): String {
    if (gohai.isBlank()) return ""
    return suspendRunCatching {
        val root = Json.parseToJsonElement(gohai).jsonObject
        val cpu = root["cpu"]?.jsonObject ?: return ""
        cpu["model_name"]?.jsonPrimitive?.content
            ?: cpu["cpu_brand"]?.jsonPrimitive?.content
            ?: ""
    }.getOrElse { _ ->
        ""
    }
}

private class StringJsonbColumnType : ColumnType<String>() {
    private fun isH2(): Boolean =
        TransactionManager
            .currentOrNull()
            ?.db
            ?.url
            ?.contains("h2", ignoreCase = true) == true

    override fun sqlType(): String = if (isH2()) "TEXT" else "jsonb"

    override fun nonNullValueToString(value: String): String {
        val escaped = value.replace("'", "''")
        return if (isH2()) "'$escaped'" else "'$escaped'::jsonb"
    }

    override fun valueFromDB(value: Any): String = when (value) {
        is PGobject -> value.value ?: "{}"
        is String -> value
        else -> "{}"
    }

    override fun notNullValueToDB(value: String): Any {
        if (isH2()) return value
        return PGobject().apply {
            type = "jsonb"
            this.value = value
        }
    }
}

private fun Table.stringJsonb(name: String): Column<String> =
    registerColumn(name, StringJsonbColumnType())

object DdHostsTable : Table("hosts") {
    val id = integer("id").autoIncrement()
    val organizationId = integer("organization_id")
    val hostname = varchar("hostname", 512)
    val displayName = varchar("display_name", 255).nullable()
    val agentKeyHash = varchar("agent_key_hash", 255).nullable()
    val status = varchar("status", 20).default("pending")
    val os = varchar("os", 128)
    val platform = varchar("platform", 128)
    val arch = varchar("arch", 20).nullable()
    val processor = varchar("processor", 256)
    val cpuCores = integer("cpu_cores")
    val memoryTotalKb = long("memory_total_kb")
    val agentVersion = varchar("agent_version", 64)
    val gohai = text("gohai")
    val tags = stringJsonb("tags").default("{}")
    val firstSeenAt = timestamp("first_seen_at")
    val lastSeenAt = timestamp("last_seen_at")

    override val primaryKey = PrimaryKey(id)
}

data class DdHostInfo(
    val id: Int,
    val organizationId: Int,
    val hostname: String,
    val os: String,
    val platform: String,
    val processor: String,
    val cpuCores: Int,
    val memoryTotalKb: Long,
    val agentVersion: String,
    val tags: Map<String, String>,
    val firstSeenAt: String,
    val lastSeenAt: String,
    val isOnline: Boolean
)

object DatadogHostService {

    private val json = Json {
        ignoreUnknownKeys = true
    }

    fun upsertFromMetadata(
        organizationId: Int,
        metadata: DatadogHostMetadata
    ) {
        logger.debug {
            "upsertFromMetadata called: hostname=${metadata.hostname}, " +
                "gohai.length=${metadata.gohai.length}, " +
                "hostMeta=${metadata.hostMeta}, " +
                "agentVersion=${metadata.agentVersion}"
        }
        if (metadata.hostname.isBlank()) return
        val tags = DatadogMetricService.parseDdTagList(metadata.tags)
        val tagsJson = json.encodeToString(tags)
        val meta = metadata.hostMeta ?: DatadogHostMeta()
        val gohaiCpuCores = if (meta.cpuCores > 0) meta.cpuCores else parseCpuCoresFromGohai(metadata.gohai)
        val gohaiProcessor = meta.processor.ifBlank { parseProcessorFromGohai(metadata.gohai) }
        val gohaiMemoryKb = if (meta.memoryTotalKb > 0) meta.memoryTotalKb else parseMemoryKbFromGohai(metadata.gohai)
        val now = Clock.System.now()

        transaction {
            val existing = DdHostsTable.selectAll()
                .where {
                    (DdHostsTable.organizationId eq organizationId) and
                        (DdHostsTable.hostname eq metadata.hostname)
                }
                .firstOrNull()

            if (existing != null) {
                DdHostsTable.update({
                    (DdHostsTable.organizationId eq organizationId) and
                        (DdHostsTable.hostname eq metadata.hostname)
                }) {
                    it[status] = "up"
                    it[lastSeenAt] = now
                    if (metadata.os.isNotBlank() || meta.os.isNotBlank()) {
                        it[os] = metadata.os.ifBlank { meta.os }
                    }
                    if (metadata.platform.isNotBlank() || meta.platform.isNotBlank()) {
                        it[platform] = metadata.platform.ifBlank {
                            meta.platform
                        }
                    }
                    if (gohaiProcessor.isNotBlank()) {
                        it[processor] = metadata.processor.ifBlank {
                            gohaiProcessor
                        }
                    }
                    if (gohaiCpuCores > 0) it[cpuCores] = gohaiCpuCores
                    if (gohaiMemoryKb > 0) it[memoryTotalKb] = gohaiMemoryKb
                    if (metadata.agentVersion.isNotBlank()) {
                        it[agentVersion] = metadata.agentVersion
                    }
                    if (metadata.gohai.isNotBlank()) it[gohai] = metadata.gohai
                    if (metadata.tags.isNotEmpty()) {
                        it[DdHostsTable.tags] = tagsJson
                    }
                }
            } else {
                if (!isHostQuotaAvailable(organizationId)) {
                    logger.info {
                        "Host quota exceeded for org $organizationId, " +
                            "skipping registration of ${metadata.hostname}"
                    }
                    return@transaction
                }
                DdHostsTable.insert {
                    it[DdHostsTable.organizationId] = organizationId
                    it[hostname] = metadata.hostname
                    it[status] = "up"
                    it[os] = metadata.os.ifBlank { meta.os }
                    it[DdHostsTable.platform] =
                        metadata.platform.ifBlank { meta.platform }
                    it[processor] = metadata.processor.ifBlank {
                        gohaiProcessor
                    }
                    it[cpuCores] = gohaiCpuCores
                    it[memoryTotalKb] = gohaiMemoryKb
                    it[agentVersion] = metadata.agentVersion
                    it[gohai] = metadata.gohai
                    it[DdHostsTable.tags] = tagsJson
                    it[firstSeenAt] = now
                    it[lastSeenAt] = now
                }
            }
        }

        logger.debug {
            "Upserted DD host ${metadata.hostname} for org $organizationId: " +
                "cpuCores=$gohaiCpuCores, memoryKb=$gohaiMemoryKb, processor=$gohaiProcessor"
        }
    }

    fun upsertFromIntake(
        organizationId: Int,
        payload: DatadogIntakePayload
    ) {
        logger.debug {
            "upsertFromIntake called: hostname=${payload.meta?.hostname}, " +
                "gohai.length=${payload.gohai.length}, " +
                "meta.os=${payload.meta?.os}, " +
                "meta.platform=${payload.meta?.platform}, " +
                "agentVersion=${payload.meta?.agentVersion}"
        }
        val hostname = payload.meta?.hostname ?: return
        if (hostname.isBlank()) return
        val tags = DatadogMetricService.parseDdTagList(
            payload.hostTags?.system ?: emptyList()
        )
        val tagsJson = json.encodeToString(tags)
        val now = Clock.System.now()
        val agentVersion = payload.meta.agentVersion
        val gohaiCpuCores = parseCpuCoresFromGohai(payload.gohai)
        val gohaiProcessor = parseProcessorFromGohai(payload.gohai)
        val gohaiMemoryKb = parseMemoryKbFromGohai(payload.gohai)

        logger.debug {
            "Parsed gohai for $hostname: cpuCores=$gohaiCpuCores, processor=$gohaiProcessor, memoryKb=$gohaiMemoryKb"
        }

        val didUpsert = transaction {
            val existing = DdHostsTable.selectAll()
                .where {
                    (
                        DdHostsTable.organizationId eq
                            organizationId
                        ) and
                        (DdHostsTable.hostname eq hostname)
                }
                .firstOrNull()

            if (existing != null) {
                DdHostsTable.update({
                    (DdHostsTable.organizationId eq organizationId) and
                        (DdHostsTable.hostname eq hostname)
                }) {
                    it[status] = "up"
                    if (payload.meta.os.isNotBlank()) it[os] = payload.meta.os
                    if (payload.meta.platform.isNotBlank()) {
                        it[platform] = payload.meta.platform
                    }
                    if (agentVersion.isNotBlank()) {
                        it[DdHostsTable.agentVersion] = agentVersion
                    }
                    if (payload.gohai.isNotBlank()) it[gohai] = payload.gohai
                    if (payload.hostTags != null) it[DdHostsTable.tags] = tagsJson
                    it[lastSeenAt] = now
                    if (gohaiCpuCores > 0) it[cpuCores] = gohaiCpuCores
                    if (gohaiProcessor.isNotBlank()) it[processor] = gohaiProcessor
                    if (gohaiMemoryKb > 0) it[memoryTotalKb] = gohaiMemoryKb
                }
                true
            } else {
                val hasMeta = payload.meta.os.isNotBlank() ||
                    payload.meta.platform.isNotBlank() ||
                    agentVersion.isNotBlank() ||
                    gohaiCpuCores > 0 || gohaiProcessor.isNotBlank() || gohaiMemoryKb > 0L
                if (!hasMeta) {
                    logger.debug {
                        "Skipping host insert for $hostname " +
                            "from intake: no usable metadata"
                    }
                    return@transaction false
                }
                if (!isHostQuotaAvailable(organizationId)) {
                    logger.info {
                        "Host quota exceeded for org $organizationId, " +
                            "skipping registration of $hostname"
                    }
                    return@transaction false
                }
                DdHostsTable.insert {
                    it[DdHostsTable.organizationId] = organizationId
                    it[DdHostsTable.hostname] = hostname
                    it[status] = "up"
                    it[os] = payload.meta.os
                    it[platform] = payload.meta.platform
                    it[processor] = gohaiProcessor
                    it[cpuCores] = gohaiCpuCores
                    it[memoryTotalKb] = gohaiMemoryKb
                    it[DdHostsTable.agentVersion] = agentVersion
                    it[gohai] = payload.gohai
                    it[DdHostsTable.tags] = tagsJson
                    it[firstSeenAt] = now
                    it[lastSeenAt] = now
                }
                true
            }
        }

        if (didUpsert) {
            logger.debug {
                "Upserted DD host $hostname from intake for org $organizationId: " +
                    "cpuCores=$gohaiCpuCores, memoryKb=$gohaiMemoryKb, processor=$gohaiProcessor"
            }
        }
    }

    /**
     * Lightweight update: only touch last_seen_at and status for known hosts.
     * Called from metric series routes so the host stays "online" even when
     * metadata/intake payloads arrive with blank hostnames.
     */
    fun touchHostLastSeen(organizationId: Int, hostnames: Set<String>) {
        if (hostnames.isEmpty()) return
        val now = Clock.System.now()
        transaction {
            for (hostname in hostnames) {
                DdHostsTable.update({
                    (DdHostsTable.organizationId eq organizationId) and
                        (DdHostsTable.hostname eq hostname)
                }) {
                    it[lastSeenAt] = now
                    it[status] = "up"
                }
            }
        }
    }

    fun listHosts(organizationId: Int): List<DdHostInfo> {
        return transaction {
            DdHostsTable.selectAll()
                .where { DdHostsTable.organizationId eq organizationId }
                .orderBy(DdHostsTable.lastSeenAt)
                .map { row -> rowToDdHostInfo(row) }
        }
    }

    fun getHost(organizationId: Int, hostId: Int): DdHostInfo? {
        return transaction {
            DdHostsTable.selectAll()
                .where {
                    (DdHostsTable.organizationId eq organizationId) and
                        (DdHostsTable.id eq hostId)
                }
                .map { row -> rowToDdHostInfo(row) }
                .firstOrNull()
        }
    }

    fun deleteHost(organizationId: Int, hostId: Int): Boolean {
        return transaction {
            val deleted = DdHostsTable.deleteWhere {
                (DdHostsTable.organizationId eq organizationId) and
                    (DdHostsTable.id eq hostId)
            }
            deleted > 0
        }
    }

    private fun rowToDdHostInfo(row: org.jetbrains.exposed.v1.core.ResultRow): DdHostInfo {
        val tagsStr = row[DdHostsTable.tags]
        val tags = suspendRunCatching {
            json.decodeFromString<Map<String, String>>(tagsStr)
        }.getOrElse { _ ->
            emptyMap()
        }
        val lastSeenAt = row[DdHostsTable.lastSeenAt]
        val isOnline = (Clock.System.now() - lastSeenAt) < 5.minutes
        return DdHostInfo(
            id = row[DdHostsTable.id],
            organizationId = row[DdHostsTable.organizationId],
            hostname = row[DdHostsTable.hostname],
            os = row[DdHostsTable.os],
            platform = row[DdHostsTable.platform],
            processor = row[DdHostsTable.processor],
            cpuCores = row[DdHostsTable.cpuCores],
            memoryTotalKb = row[DdHostsTable.memoryTotalKb],
            agentVersion = row[DdHostsTable.agentVersion],
            tags = tags,
            firstSeenAt = row[DdHostsTable.firstSeenAt].toString(),
            lastSeenAt = lastSeenAt.toString(),
            isOnline = isOnline
        )
    }

    private fun isHostQuotaAvailable(organizationId: Int): Boolean {
        return suspendRunCatching {
            val monitorService: MonitorService = GlobalContext.get().get()
            monitorService.checkHostQuota(organizationId)
        }.getOrDefault(true)
    }
}
