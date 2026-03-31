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

package com.moneat.config

import com.moneat.utils.suspendRunCatching
import io.ktor.client.statement.bodyAsText
import mu.KotlinLogging
import java.io.File

private val logger = KotlinLogging.logger {}

// ── Demo Profile Flamegraph Files ────────────────────────────────────────

internal val demoProfileServices = listOf(
    "api-gateway",
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
        ClickHouseClient.execute(
            "ALTER TABLE profiles DELETE WHERE organization_id = $ORG1"
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
    suspendRunCatching { ClickHouseClient.execute(sql) }
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

@Suppress("LongMethod")
internal fun buildSentryProfile(service: String, seed: Int): String {
    val stacks = SERVICE_STACKS[service] ?: SERVICE_STACKS["api-gateway"]!!
    val frames = stacks.flatMap { it }.distinct()
    val frameIndex = frames.withIndex().associate { (i, name) -> name to i }

    val stackIndices = stacks.map { stack ->
        stack.map { frameIndex[it]!! }
    }

    val framesJson = frames.joinToString(",\n      ") { name ->
        val dot = name.lastIndexOf('.')
        val (module, fn) = if (dot > 0) {
            name.substring(0, dot) to name.substring(dot + 1)
        } else {
            "" to name
        }
        """{"function":"$fn","module":"$module"}"""
    }

    val stacksJson = stackIndices.joinToString(",") { idxList ->
        "[${idxList.joinToString(",")}]"
    }

    val sampleCount = 80 + (seed * 17) % 40
    val samplesJson = (0 until sampleCount).joinToString(",") { n ->
        val stackId = n % stackIndices.size
        """{"stack_id":$stackId}"""
    }

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
    "api-gateway" to listOf(
        listOf(
            "encoding/json.Marshal",
            "github.com/acme/api-gateway/handlers.handleListProducts",
            "github.com/gorilla/mux.ServeHTTP",
            "net/http.serverHandler.ServeHTTP",
            "net/http.(*conn).serve",
            "runtime.goexit"
        ),
        listOf(
            "github.com/acme/api-gateway/middleware.validateJWT",
            "github.com/acme/api-gateway/middleware.AuthMiddleware",
            "github.com/gorilla/mux.ServeHTTP",
            "net/http.serverHandler.ServeHTTP",
            "net/http.(*conn).serve",
            "runtime.goexit"
        ),
        listOf(
            "io.ReadAll",
            "net/http.(*Request).ParseForm",
            "github.com/acme/api-gateway/handlers.handleCheckout",
            "github.com/gorilla/mux.ServeHTTP",
            "net/http.serverHandler.ServeHTTP",
            "net/http.(*conn).serve",
            "runtime.goexit"
        ),
        listOf(
            "compress/gzip.(*Writer).Write",
            "github.com/acme/api-gateway/middleware.GzipResponseWriter.Write",
            "github.com/acme/api-gateway/handlers.handleSearch",
            "github.com/gorilla/mux.ServeHTTP",
            "net/http.serverHandler.ServeHTTP",
            "net/http.(*conn).serve",
            "runtime.goexit"
        ),
        listOf(
            "crypto/tls.(*Conn).Read",
            "net/http.(*persistConn).readLoop",
            "runtime.goexit"
        )
    ),
    "user-service" to listOf(
        listOf(
            "database/sql.(*DB).QueryRow",
            "github.com/acme/user-service/repo.(*UserRepo).FindByID",
            "github.com/acme/user-service/handlers.GetUser",
            "net/http.serverHandler.ServeHTTP",
            "net/http.(*conn).serve",
            "runtime.goexit"
        ),
        listOf(
            "golang.org/x/crypto/bcrypt.GenerateFromPassword",
            "github.com/acme/user-service/handlers.CreateUser",
            "net/http.serverHandler.ServeHTTP",
            "net/http.(*conn).serve",
            "runtime.goexit"
        ),
        listOf(
            "github.com/go-redis/redis.(*Client).Get",
            "github.com/acme/user-service/cache.GetUserSession",
            "github.com/acme/user-service/handlers.GetUser",
            "net/http.serverHandler.ServeHTTP",
            "net/http.(*conn).serve",
            "runtime.goexit"
        ),
        listOf(
            "encoding/json.Marshal",
            "github.com/acme/user-service/handlers.ListUsers",
            "net/http.serverHandler.ServeHTTP",
            "net/http.(*conn).serve",
            "runtime.goexit"
        )
    ),
    "order-service" to listOf(
        listOf(
            "database/sql.(*Tx).Exec",
            "github.com/acme/order-service/repo.(*OrderRepo).Create",
            "github.com/acme/order-service/handlers.PlaceOrder",
            "net/http.serverHandler.ServeHTTP",
            "net/http.(*conn).serve",
            "runtime.goexit"
        ),
        listOf(
            "net/http.(*Client).Do",
            "github.com/acme/order-service/client.(*PaymentClient).Charge",
            "github.com/acme/order-service/handlers.PlaceOrder",
            "net/http.serverHandler.ServeHTTP",
            "net/http.(*conn).serve",
            "runtime.goexit"
        ),
        listOf(
            "github.com/acme/order-service/events.(*Publisher).Emit",
            "github.com/acme/order-service/handlers.PlaceOrder",
            "net/http.serverHandler.ServeHTTP",
            "net/http.(*conn).serve",
            "runtime.goexit"
        ),
        listOf(
            "database/sql.(*DB).QueryRow",
            "github.com/acme/order-service/repo.(*OrderRepo).GetByID",
            "github.com/acme/order-service/handlers.GetOrder",
            "net/http.serverHandler.ServeHTTP",
            "net/http.(*conn).serve",
            "runtime.goexit"
        )
    ),
    "product-service" to listOf(
        listOf(
            "github.com/go-redis/redis.(*Client).Get",
            "github.com/acme/product-service/cache.GetProduct",
            "github.com/acme/product-service/handlers.GetProduct",
            "net/http.serverHandler.ServeHTTP",
            "net/http.(*conn).serve",
            "runtime.goexit"
        ),
        listOf(
            "github.com/olivere/elastic.(*SearchService).Do",
            "github.com/acme/product-service/search.Query",
            "github.com/acme/product-service/handlers.SearchProducts",
            "net/http.serverHandler.ServeHTTP",
            "net/http.(*conn).serve",
            "runtime.goexit"
        ),
        listOf(
            "database/sql.(*DB).Query",
            "github.com/acme/product-service/repo.(*ProductRepo).ListByCategory",
            "github.com/acme/product-service/handlers.ListProducts",
            "net/http.serverHandler.ServeHTTP",
            "net/http.(*conn).serve",
            "runtime.goexit"
        ),
        listOf(
            "encoding/json.Marshal",
            "github.com/acme/product-service/handlers.GetProduct",
            "net/http.serverHandler.ServeHTTP",
            "net/http.(*conn).serve",
            "runtime.goexit"
        )
    ),
    "inventory-service" to listOf(
        listOf(
            "sync.(*Mutex).Lock",
            "github.com/acme/inventory-service/stock.(*Manager).Reserve",
            "github.com/acme/inventory-service/handlers.ReserveStock",
            "net/http.serverHandler.ServeHTTP",
            "net/http.(*conn).serve",
            "runtime.goexit"
        ),
        listOf(
            "database/sql.(*Tx).Exec",
            "github.com/acme/inventory-service/repo.(*StockRepo).Decrement",
            "github.com/acme/inventory-service/stock.(*Manager).Reserve",
            "github.com/acme/inventory-service/handlers.ReserveStock",
            "net/http.serverHandler.ServeHTTP",
            "net/http.(*conn).serve",
            "runtime.goexit"
        ),
        listOf(
            "database/sql.(*DB).Query",
            "github.com/acme/inventory-service/repo.(*StockRepo).GetLevels",
            "github.com/acme/inventory-service/handlers.GetInventory",
            "net/http.serverHandler.ServeHTTP",
            "net/http.(*conn).serve",
            "runtime.goexit"
        ),
        listOf(
            "github.com/acme/inventory-service/events.(*Consumer).Process",
            "github.com/acme/inventory-service/worker.Run",
            "runtime.goexit"
        )
    )
)
