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

@file:Suppress("MagicNumber")

package com.moneat.config

import com.moneat.utils.suspendRunCatching
import io.ktor.client.statement.bodyAsText
import mu.KotlinLogging
import java.io.File

private val logger = KotlinLogging.logger {}

private const val DEMO_PROFILE_SVC_API_GATEWAY = "api-gateway"
private const val PROFILE_FRAME_ENCODING_JSON_MARSHAL = "encoding/json.Marshal"
private const val PROFILE_FRAME_GORILLA_MUX_SERVE_HTTP = "github.com/gorilla/mux.ServeHTTP"
private const val PROFILE_FRAME_NET_HTTP_SERVER_HANDLER_SERVE_HTTP = "net/http.serverHandler.ServeHTTP"
private const val PROFILE_FRAME_NET_HTTP_CONN_SERVE = "net/http.(*conn).serve"
private const val PROFILE_FRAME_RUNTIME_GOEXIT = "runtime.goexit"
private const val PROFILE_FRAME_ORDER_PLACE_ORDER = "github.com/acme/order-service/handlers.PlaceOrder"

// ── Demo Profile Flamegraph Files ────────────────────────────────────────

internal val demoProfileServices = listOf(
    DEMO_PROFILE_SVC_API_GATEWAY,
    "user-service",
    "order-service",
    "product-service",
    "inventory-service",
)

internal suspend fun ensureDemoProfileFiles() {
    if (!EnvConfig.Demo.enabled) return
    val ids = (1..DEMO_PROFILE_COUNT).map { n ->
        "00000000-0000-4000-8000-" + n.toString().padStart(12, '0')
    }
    writeDemoProfileFiles(ids, demoProfileServices)
    ensureDemoProfileRows(ids)
}

internal suspend fun ensureDemoProfileRows(profileIds: List<String>) {
    // Safety: this method only touches the demo org (organization_id = toUInt64(-1))
    // and only runs when DEMO_ENABLED=true
    if (!EnvConfig.Demo.enabled) return
    val firstId = profileIds.firstOrNull() ?: return
    val check = suspendRunCatching {
        ClickHouseClient.execute(
            """
            SELECT count() FROM profiles
            WHERE organization_id = $ORG1
              AND toString(profile_id) = '$firstId'
            """.trimIndent()
        ).bodyAsText().trim().toLongOrNull() ?: 0
    }.getOrElse { 0L }

    if (check > 0) return

    // Old rows exist with random IDs — purge and re-insert
    suspendRunCatching {
        requireClickHouse2xx(
            ClickHouseClient.execute(
                "ALTER TABLE profiles DELETE WHERE organization_id = $ORG1"
            ),
            "Purge old demo profiles"
        )
    }.onFailure {
        logger.warn { "Purge old demo profiles failed (non-fatal): ${it.message}" }
    }

    val profileTypes = listOf("cpu", "heap", "allocs", "goroutine", "block")
    val profileHosts = listOf(
        "prod-web-01",
        "prod-api-01",
        "prod-worker-01",
        "prod-web-02",
        "prod-api-01",
    )

    val values = profileIds.mapIndexed { i, uuid ->
        val svc = demoProfileServices[i % demoProfileServices.size]
        val typ = profileTypes[i % profileTypes.size]
        val host = profileHosts[i % profileHosts.size]
        val storageKey = "-1/$uuid.profile.json"
        val minutesAgo = (i * 47) % 1440
        """(
            toUUID('$uuid'), $ORG1,
            '$host', '$svc', 'production', '1.3.0',
            'go1.21', 'go', '$typ',
            now() - INTERVAL $minutesAgo MINUTE,
            now() - INTERVAL $minutesAgo MINUTE + INTERVAL 60 SECOND,
            60000000000,
            '$storageKey',
            map('service', '$svc', 'env', 'production'),
            ${50_000 + (i * 31_337) % 450_000},
            'sentry'
        )"""
    }.joinToString(",\n")

    val sql = """
        INSERT INTO profiles (
            profile_id, organization_id, host, service,
            env, version, runtime, language, profile_type,
            start_time, end_time, duration_ns,
            storage_key, tags, size_bytes, source
        ) VALUES
        $values
    """.trimIndent()
    suspendRunCatching {
        requireClickHouse2xx(ClickHouseClient.execute(sql), "Re-insert demo profiles")
    }
        .onFailure { logger.warn { "Re-insert demo profiles failed (non-fatal): ${it.message}" } }
        .onSuccess { logger.info { "Re-inserted ${profileIds.size} demo profile rows" } }
}

internal fun writeDemoProfileFiles(
    profileIds: List<String>,
    services: List<String>
) {
    val storagePath = EnvConfig.get(
        "PROFILE_STORAGE_PATH",
        "/var/lib/moneat/profiles"
    )
    val orgDir = File(storagePath, "-1")
    if (!orgDir.exists() && !orgDir.mkdirs()) {
        logger.warn {
            "Cannot create profile dir $orgDir, skipping demo profile files"
        }
        return
    }

    for ((i, uuid) in profileIds.withIndex()) {
        val svc = services[i % services.size]
        val file = File(orgDir, "$uuid.profile.json")
        if (file.exists()) continue
        runCatching {
            file.writeText(buildSentryProfile(svc, i))
        }.onFailure {
            logger.warn { "Write demo profile $uuid failed (non-fatal): ${it.message}" }
        }
    }
    logger.info { "Wrote ${profileIds.size} demo profile files to $orgDir" }
}

internal fun cleanDemoProfileFiles() {
    val storagePath = EnvConfig.get(
        "PROFILE_STORAGE_PATH",
        "/var/lib/moneat/profiles"
    )
    val orgDir = File(storagePath, "-1")
    if (!orgDir.isDirectory) return
    runCatching {
        orgDir.listFiles()
            ?.filter { it.name.endsWith(".profile.json") }
            ?.forEach { it.delete() }
    }.onFailure {
        logger.warn { "Clean demo profiles failed (non-fatal): ${it.message}" }
    }
}

private fun profileFrameJson(name: String): String {
    val dot = name.lastIndexOf('.')
    val (module, fn) = if (dot > 0) {
        name.substring(0, dot) to name.substring(dot + 1)
    } else {
        "" to name
    }
    return """{"function":"$fn","module":"$module"}"""
}

private fun buildProfileFramesJson(frames: List<String>): String =
    frames.joinToString(",\n      ") { profileFrameJson(it) }

private fun buildProfileStacksJson(stackIndices: List<List<Int>>): String =
    stackIndices.joinToString(",") { idxList -> "[${idxList.joinToString(",")}]" }

private fun buildProfileSamplesJson(sampleCount: Int, stackCount: Int): String =
    (0 until sampleCount).joinToString(",") { n ->
        val stackId = n % stackCount
        """{"stack_id":$stackId}"""
    }

internal fun buildSentryProfile(service: String, seed: Int): String {
    val stacks = SERVICE_STACKS[service] ?: SERVICE_STACKS[DEMO_PROFILE_SVC_API_GATEWAY]!!
    val frames = stacks.flatMap { it }.distinct()
    val frameIndex = frames.withIndex().associate { (i, name) -> name to i }
    val stackIndices = stacks.map { stack -> stack.map { frameIndex[it]!! } }
    val sampleCount = 80 + (seed * 17) % 40
    val framesJson = buildProfileFramesJson(frames)
    val stacksJson = buildProfileStacksJson(stackIndices)
    val samplesJson = buildProfileSamplesJson(sampleCount, stackIndices.size)

    return """
        {
          "profile": {
            "frames": [$framesJson],
            "stacks": [$stacksJson],
            "samples": [$samplesJson]
          }
        }
    """.trimIndent()
}

// Realistic Go call stacks per service (leaf-first order).
// Each inner list is one stack: [leaf, ..., root].
private val SERVICE_STACKS = mapOf(
    DEMO_PROFILE_SVC_API_GATEWAY to listOf(
        listOf(
            PROFILE_FRAME_ENCODING_JSON_MARSHAL,
            "github.com/acme/api-gateway/handlers.handleListProducts",
            PROFILE_FRAME_GORILLA_MUX_SERVE_HTTP,
            PROFILE_FRAME_NET_HTTP_SERVER_HANDLER_SERVE_HTTP,
            PROFILE_FRAME_NET_HTTP_CONN_SERVE,
            PROFILE_FRAME_RUNTIME_GOEXIT
        ),
        listOf(
            "github.com/acme/api-gateway/middleware.validateJWT",
            "github.com/acme/api-gateway/middleware.AuthMiddleware",
            PROFILE_FRAME_GORILLA_MUX_SERVE_HTTP,
            PROFILE_FRAME_NET_HTTP_SERVER_HANDLER_SERVE_HTTP,
            PROFILE_FRAME_NET_HTTP_CONN_SERVE,
            PROFILE_FRAME_RUNTIME_GOEXIT
        ),
        listOf(
            "io.ReadAll",
            "net/http.(*Request).ParseForm",
            "github.com/acme/api-gateway/handlers.handleCheckout",
            PROFILE_FRAME_GORILLA_MUX_SERVE_HTTP,
            PROFILE_FRAME_NET_HTTP_SERVER_HANDLER_SERVE_HTTP,
            PROFILE_FRAME_NET_HTTP_CONN_SERVE,
            PROFILE_FRAME_RUNTIME_GOEXIT
        ),
        listOf(
            "compress/gzip.(*Writer).Write",
            "github.com/acme/api-gateway/middleware.GzipResponseWriter.Write",
            "github.com/acme/api-gateway/handlers.handleSearch",
            PROFILE_FRAME_GORILLA_MUX_SERVE_HTTP,
            PROFILE_FRAME_NET_HTTP_SERVER_HANDLER_SERVE_HTTP,
            PROFILE_FRAME_NET_HTTP_CONN_SERVE,
            PROFILE_FRAME_RUNTIME_GOEXIT
        ),
        listOf(
            "crypto/tls.(*Conn).Read",
            "net/http.(*persistConn).readLoop",
            PROFILE_FRAME_RUNTIME_GOEXIT
        )
    ),
    "user-service" to listOf(
        listOf(
            "database/sql.(*DB).QueryRow",
            "github.com/acme/user-service/repo.(*UserRepo).FindByID",
            "github.com/acme/user-service/handlers.GetUser",
            PROFILE_FRAME_NET_HTTP_SERVER_HANDLER_SERVE_HTTP,
            PROFILE_FRAME_NET_HTTP_CONN_SERVE,
            PROFILE_FRAME_RUNTIME_GOEXIT
        ),
        listOf(
            "golang.org/x/crypto/bcrypt.GenerateFromPassword",
            "github.com/acme/user-service/handlers.CreateUser",
            PROFILE_FRAME_NET_HTTP_SERVER_HANDLER_SERVE_HTTP,
            PROFILE_FRAME_NET_HTTP_CONN_SERVE,
            PROFILE_FRAME_RUNTIME_GOEXIT
        ),
        listOf(
            "github.com/go-redis/redis.(*Client).Get",
            "github.com/acme/user-service/cache.GetUserSession",
            "github.com/acme/user-service/handlers.GetUser",
            PROFILE_FRAME_NET_HTTP_SERVER_HANDLER_SERVE_HTTP,
            PROFILE_FRAME_NET_HTTP_CONN_SERVE,
            PROFILE_FRAME_RUNTIME_GOEXIT
        ),
        listOf(
            PROFILE_FRAME_ENCODING_JSON_MARSHAL,
            "github.com/acme/user-service/handlers.ListUsers",
            PROFILE_FRAME_NET_HTTP_SERVER_HANDLER_SERVE_HTTP,
            PROFILE_FRAME_NET_HTTP_CONN_SERVE,
            PROFILE_FRAME_RUNTIME_GOEXIT
        )
    ),
    "order-service" to listOf(
        listOf(
            "database/sql.(*Tx).Exec",
            "github.com/acme/order-service/repo.(*OrderRepo).Create",
            PROFILE_FRAME_ORDER_PLACE_ORDER,
            PROFILE_FRAME_NET_HTTP_SERVER_HANDLER_SERVE_HTTP,
            PROFILE_FRAME_NET_HTTP_CONN_SERVE,
            PROFILE_FRAME_RUNTIME_GOEXIT
        ),
        listOf(
            "net/http.(*Client).Do",
            "github.com/acme/order-service/client.(*PaymentClient).Charge",
            PROFILE_FRAME_ORDER_PLACE_ORDER,
            PROFILE_FRAME_NET_HTTP_SERVER_HANDLER_SERVE_HTTP,
            PROFILE_FRAME_NET_HTTP_CONN_SERVE,
            PROFILE_FRAME_RUNTIME_GOEXIT
        ),
        listOf(
            "github.com/acme/order-service/events.(*Publisher).Emit",
            PROFILE_FRAME_ORDER_PLACE_ORDER,
            PROFILE_FRAME_NET_HTTP_SERVER_HANDLER_SERVE_HTTP,
            PROFILE_FRAME_NET_HTTP_CONN_SERVE,
            PROFILE_FRAME_RUNTIME_GOEXIT
        ),
        listOf(
            "database/sql.(*DB).QueryRow",
            "github.com/acme/order-service/repo.(*OrderRepo).GetByID",
            "github.com/acme/order-service/handlers.GetOrder",
            PROFILE_FRAME_NET_HTTP_SERVER_HANDLER_SERVE_HTTP,
            PROFILE_FRAME_NET_HTTP_CONN_SERVE,
            PROFILE_FRAME_RUNTIME_GOEXIT
        )
    ),
    "product-service" to listOf(
        listOf(
            "github.com/go-redis/redis.(*Client).Get",
            "github.com/acme/product-service/cache.GetProduct",
            "github.com/acme/product-service/handlers.GetProduct",
            PROFILE_FRAME_NET_HTTP_SERVER_HANDLER_SERVE_HTTP,
            PROFILE_FRAME_NET_HTTP_CONN_SERVE,
            PROFILE_FRAME_RUNTIME_GOEXIT
        ),
        listOf(
            "github.com/olivere/elastic.(*SearchService).Do",
            "github.com/acme/product-service/search.Query",
            "github.com/acme/product-service/handlers.SearchProducts",
            PROFILE_FRAME_NET_HTTP_SERVER_HANDLER_SERVE_HTTP,
            PROFILE_FRAME_NET_HTTP_CONN_SERVE,
            PROFILE_FRAME_RUNTIME_GOEXIT
        ),
        listOf(
            "database/sql.(*DB).Query",
            "github.com/acme/product-service/repo.(*ProductRepo).ListByCategory",
            "github.com/acme/product-service/handlers.ListProducts",
            PROFILE_FRAME_NET_HTTP_SERVER_HANDLER_SERVE_HTTP,
            PROFILE_FRAME_NET_HTTP_CONN_SERVE,
            PROFILE_FRAME_RUNTIME_GOEXIT
        ),
        listOf(
            PROFILE_FRAME_ENCODING_JSON_MARSHAL,
            "github.com/acme/product-service/handlers.GetProduct",
            PROFILE_FRAME_NET_HTTP_SERVER_HANDLER_SERVE_HTTP,
            PROFILE_FRAME_NET_HTTP_CONN_SERVE,
            PROFILE_FRAME_RUNTIME_GOEXIT
        )
    ),
    "inventory-service" to listOf(
        listOf(
            "sync.(*Mutex).Lock",
            "github.com/acme/inventory-service/stock.(*Manager).Reserve",
            "github.com/acme/inventory-service/handlers.ReserveStock",
            PROFILE_FRAME_NET_HTTP_SERVER_HANDLER_SERVE_HTTP,
            PROFILE_FRAME_NET_HTTP_CONN_SERVE,
            PROFILE_FRAME_RUNTIME_GOEXIT
        ),
        listOf(
            "database/sql.(*Tx).Exec",
            "github.com/acme/inventory-service/repo.(*StockRepo).Decrement",
            "github.com/acme/inventory-service/stock.(*Manager).Reserve",
            "github.com/acme/inventory-service/handlers.ReserveStock",
            PROFILE_FRAME_NET_HTTP_SERVER_HANDLER_SERVE_HTTP,
            PROFILE_FRAME_NET_HTTP_CONN_SERVE,
            PROFILE_FRAME_RUNTIME_GOEXIT
        ),
        listOf(
            "database/sql.(*DB).Query",
            "github.com/acme/inventory-service/repo.(*StockRepo).GetLevels",
            "github.com/acme/inventory-service/handlers.GetInventory",
            PROFILE_FRAME_NET_HTTP_SERVER_HANDLER_SERVE_HTTP,
            PROFILE_FRAME_NET_HTTP_CONN_SERVE,
            PROFILE_FRAME_RUNTIME_GOEXIT
        ),
        listOf(
            "github.com/acme/inventory-service/events.(*Consumer).Process",
            "github.com/acme/inventory-service/worker.Run",
            PROFILE_FRAME_RUNTIME_GOEXIT
        )
    )
)
