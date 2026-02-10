package com.moneat.demo

import com.moneat.config.ClickHouseClient
import com.moneat.config.EnvConfig
import com.moneat.models.*
import io.ktor.client.statement.*
import io.ktor.http.*
import org.jetbrains.exposed.sql.Database
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.transaction
import org.mindrot.jbcrypt.BCrypt
import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.*
import kotlin.random.Random

/**
 * Demo Data Seeder for Moneat
 * 
 * Seeds realistic, screenshot-ready demo data:
 * - Demo user and organization
 * - Multiple realistic projects
 * - Varied error issues with actual stack traces
 * - Events with realistic context (devices, OS versions, users)
 * - Release tracking
 */
object DemoDataSeeder {
    
    private fun hashPassword(password: String): String {
        return BCrypt.hashpw(password, BCrypt.gensalt())
    }

    private fun generateKey(): String {
        return UUID.randomUUID().toString().replace("-", "")
    }
    
    private val random = Random(42) // Deterministic seed for consistent demo data
    
    private val androidDevices = listOf(
        "Samsung Galaxy S23", "Google Pixel 8", "OnePlus 11", 
        "Samsung Galaxy A54", "Xiaomi 13 Pro"
    )
    
    private val iosDevices = listOf(
        "iPhone 15 Pro", "iPhone 14", "iPhone 13", "iPad Air", "iPad Pro 11\""
    )
    
    private val androidVersions = listOf("14", "13", "12", "11")
    private val iosVersions = listOf("17.3", "17.2", "16.5", "16.4")
    
    private val userEmails = listOf(
        "sarah.johnson@example.com", "mike.chen@example.com", 
        "alex.rivera@example.com", "priya.patel@example.com",
        "john.smith@example.com", "emma.williams@example.com"
    )

    private fun randomTime(daysAgo: Int): Instant {
        val hoursAgo = daysAgo * 24 + random.nextInt(24)
        return Instant.now().minus(hoursAgo.toLong(), ChronoUnit.HOURS)
    }

    suspend fun seed() {
        println("Starting demo data seeding...")
        
        // Initialize environment config
        EnvConfig.initialize()
        
        // Connect to database
        val dbUrl = EnvConfig.get("POSTGRES_URL") 
            ?: "jdbc:postgresql://localhost:5499/moneat"
        val dbUser = EnvConfig.get("POSTGRES_USER") ?: "moneat"
        val dbPassword = EnvConfig.get("POSTGRES_PASSWORD") ?: "moneat_dev_password"
        
        Database.connect(
            url = dbUrl,
            driver = "org.postgresql.Driver",
            user = dbUser,
            password = dbPassword
        )
        
        println("Connected to database: $dbUrl")
        
        // Initialize ClickHouse client
        val clickhouseUrl = EnvConfig.get("CLICKHOUSE_URL") ?: "http://localhost:8123"
        val clickhouseDb = EnvConfig.get("CLICKHOUSE_DATABASE") ?: "moneat"
        val clickhouseUser = EnvConfig.get("CLICKHOUSE_USER") ?: "moneat"
        val clickhousePassword = EnvConfig.get("CLICKHOUSE_PASSWORD") ?: "moneat_dev_password"
        
        ClickHouseClient.init(clickhouseUrl, clickhouseDb, clickhouseUser, clickhousePassword)
        println("Connected to ClickHouse: $clickhouseUrl")
        
        val (_, orgId, projects) = transaction {
            // Check if already seeded
            val existingUser = Users.selectAll().where { Users.email eq "demo@moneat.dev" }.firstOrNull()
            if (existingUser != null) {
                println("Demo data already exists. Fetching existing data...")
                val userId = existingUser[Users.id]
                val membership = Memberships.selectAll().where { Memberships.user_id eq userId }.firstOrNull()
                if (membership != null) {
                    val orgId = membership[Memberships.organization_id]
                    // Fetch existing projects
                    val existingProjects = Projects.selectAll().where { Projects.organization_id eq orgId }
                        .associate { row ->
                            val framework = row[Projects.framework] ?: "unknown"
                            val frameworkKey = when (framework) {
                                "react-native" -> "react-native"
                                "ios" -> "ios"
                                "android" -> "android"
                                else -> framework.lowercase()
                            }
                            val projectId = row[Projects.id]
                            val publicKey = ProjectKeys.selectAll()
                                .where { ProjectKeys.project_id eq projectId }
                                .firstOrNull()
                                ?.get(ProjectKeys.public_key) ?: ""
                            frameworkKey to Pair(projectId, publicKey)
                        }
                    return@transaction Triple(userId, orgId, existingProjects)
                }
                return@transaction Triple(0, 0, emptyMap<String, Pair<Long, String>>())
            }
            
            println("Creating demo user...")
            val passwordHash = hashPassword("demo123")
            
            val userId = Users.insert {
                it[email] = "demo@moneat.dev"
                it[password_hash] = passwordHash
                it[name] = "Demo User"
                it[email_verified] = true
                it[onboarding_completed] = true
            } get Users.id
            
            println("Created user: $userId")
            
            // Create organization
            println("Creating demo organization...")
            val orgId = Organizations.insert {
                it[name] = "Acme Mobile Inc"
                it[slug] = "acme-mobile"
                it[company_size] = "11-50"
            } get Organizations.id
            
            println("Created organization: $orgId")
            
            // Add membership
            Memberships.insert {
                it[user_id] = userId
                it[organization_id] = orgId
                it[role] = "owner"
            }
            
            // Create projects with keys
            println("Creating demo projects...")
            val projects = mutableMapOf<String, Pair<Long, String>>()
            
            // Android Shopping App
            val androidProjectId = Projects.insert {
                it[organization_id] = orgId
                it[name] = "Acme Shopping - Android"
                it[slug] = "acme-shopping-android"
                it[framework] = "android"
            } get Projects.id
            
            val androidPublicKey = generateKey()
            ProjectKeys.insert {
                it[project_id] = androidProjectId
                it[public_key] = androidPublicKey
                it[secret_key] = generateKey()
                it[is_active] = true
            }
            projects["android"] = androidProjectId to androidPublicKey
            
            // iOS Shopping App
            val iosProjectId = Projects.insert {
                it[organization_id] = orgId
                it[name] = "Acme Shopping - iOS"
                it[slug] = "acme-shopping-ios"
                it[framework] = "ios"
            } get Projects.id
            
            val iosPublicKey = generateKey()
            ProjectKeys.insert {
                it[project_id] = iosProjectId
                it[public_key] = iosPublicKey
                it[secret_key] = generateKey()
                it[is_active] = true
            }
            projects["ios"] = iosProjectId to iosPublicKey
            
            // React Native App
            val rnProjectId = Projects.insert {
                it[organization_id] = orgId
                it[name] = "Acme Shopping - React Native"
                it[slug] = "acme-shopping-rn"
                it[framework] = "react-native"
            } get Projects.id
            
            val rnPublicKey = generateKey()
            ProjectKeys.insert {
                it[project_id] = rnProjectId
                it[public_key] = rnPublicKey
                it[secret_key] = generateKey()
                it[is_active] = true
            }
            projects["react-native"] = rnProjectId to rnPublicKey
            
            println("Created projects: Android=$androidProjectId, iOS=$iosProjectId, RN=$rnProjectId")
            
            // Create releases
            println("Creating releases...")
            val releases = listOf("1.0.0", "1.1.0", "1.2.0", "1.2.1", "1.3.0")
            releases.forEachIndexed { index, version ->
                val timestamp = randomTime(30 - index * 5)
                Releases.insert {
                    it[project_id] = androidProjectId
                    it[Releases.version] = version
                    it[created_at] = timestamp.epochSecond
                }
                Releases.insert {
                    it[project_id] = iosProjectId
                    it[Releases.version] = version
                    it[created_at] = timestamp.epochSecond
                }
            }
            
            Triple(userId, orgId, projects)
        }
        
        if (projects.isEmpty()) {
            println("❌ No projects found. Cannot seed data.")
            return
        }
        
        // Check if issues already exist in ClickHouse
        val db = ClickHouseClient.getDatabase()
        val issueCountResult = ClickHouseClient.executeWithFormat(
            "SELECT count() as cnt FROM $db.issues",
            "TabSeparated"
        )
        val issueCount = issueCountResult.trim().toLongOrNull() ?: 0
        
        // Seed ClickHouse data (issues and events) only if not already seeded
        if (issueCount == 0L) {
            println("\nSeeding ClickHouse data (issues and events)...")
            seedClickHouseData(projects)
        } else {
            println("\nIssues already exist ($issueCount found). Skipping issue/event seeding.")
        }
        
        // Always seed logs data (they're time-sensitive for demo)
        println("\nSeeding log data...")
        seedLogData(projects)
        
        // Always seed uptime monitors and monitoring systems (or update if they exist)
        println("\nSeeding uptime monitors...")
        seedUptimeMonitors(orgId)
        
        // Seed monitoring systems
        println("\nSeeding monitoring systems...")
        seedMonitoringSystems(orgId)
        
        val backendUrl = EnvConfig.get("BACKEND_URL", "http://localhost:8080")
        val backendHost = backendUrl.removePrefix("http://").removePrefix("https://")
        
        println("\n=== DEMO SETUP COMPLETE ===")
        println("Demo User:")
        println("  Email: demo@moneat.dev")
        println("  Password: demo123")
        println("\nOrganization: Acme Mobile Inc")
        println("\nProjects:")
        projects.forEach { (name, pair) ->
            val (projectId, publicKey) = pair
            println("  - $name (ID: $projectId)")
            println("    DSN: http://$publicKey@$backendHost/$projectId")
        }
        println("\nNext steps:")
        println("1. Login at http://localhost:3000 with demo@moneat.dev / demo123")
        println("2. Browse the realistic error data in the dashboard")
        println("3. Take screenshots for documentation!")
        println("=========================\n")
    }
    
    private suspend fun seedClickHouseData(projects: Map<String, Pair<Long, String>>) {
        val db = ClickHouseClient.getDatabase()
        
        // Define realistic issues for each platform
        val androidIssues = listOf(
            IssueTemplate(
                "NullPointerException in ProductDetailFragment",
                "java.lang.NullPointerException",
                "Attempt to invoke virtual method 'java.lang.String com.acme.Product.getName()' on a null object reference",
                "android",
                listOf(
                    "at com.acme.shopping.ui.ProductDetailFragment.updateUI(ProductDetailFragment.kt:87)",
                    "at com.acme.shopping.ui.ProductDetailFragment.onViewCreated(ProductDetailFragment.kt:45)",
                    "at androidx.fragment.app.Fragment.performViewCreated(Fragment.java:2987)",
                    "at androidx.fragment.app.FragmentStateManager.createView(FragmentStateManager.java:551)"
                ),
                eventCount = 127,
                userCount = 23,
                level = "error"
            ),
            IssueTemplate(
                "OutOfMemoryError loading product images",
                "java.lang.OutOfMemoryError",
                "Failed to allocate a 8294400 byte allocation with 4194304 free bytes and 3MB until OOM",
                "android",
                listOf(
                    "at android.graphics.BitmapFactory.nativeDecodeStream(Native Method)",
                    "at android.graphics.BitmapFactory.decodeStreamInternal(BitmapFactory.java:746)",
                    "at com.acme.shopping.util.ImageLoader.loadBitmap(ImageLoader.kt:34)",
                    "at com.acme.shopping.ui.ProductListAdapter.onBindViewHolder(ProductListAdapter.kt:56)"
                ),
                eventCount = 43,
                userCount = 12,
                level = "fatal"
            ),
            IssueTemplate(
                "NetworkOnMainThreadException in checkout",
                "android.os.NetworkOnMainThreadException",
                "Network operation on main thread",
                "android",
                listOf(
                    "at android.os.StrictMode\$AndroidBlockGuardPolicy.onNetwork(StrictMode.java:1605)",
                    "at java.net.Inet6AddressImpl.lookupHostByName(Inet6AddressImpl.java:117)",
                    "at com.acme.shopping.api.CheckoutService.processPayment(CheckoutService.kt:89)",
                    "at com.acme.shopping.ui.CheckoutActivity.onPayButtonClick(CheckoutActivity.kt:145)"
                ),
                eventCount = 8,
                userCount = 6,
                level = "error"
            ),
            IssueTemplate(
                "IllegalStateException: Fragment not attached",
                "java.lang.IllegalStateException",
                "Fragment CartFragment not attached to a context",
                "android",
                listOf(
                    "at androidx.fragment.app.Fragment.requireContext(Fragment.java:954)",
                    "at com.acme.shopping.ui.CartFragment.updateCartTotal(CartFragment.kt:123)",
                    "at com.acme.shopping.ui.CartFragment\$observeCart\$1.invoke(CartFragment.kt:78)"
                ),
                eventCount = 34,
                userCount = 18,
                level = "error"
            )
        )
        
        val iosIssues = listOf(
            IssueTemplate(
                "Fatal error: Index out of range",
                "Swift.fatalError",
                "Fatal error: Index out of range",
                "cocoa",
                listOf(
                    "ProductListViewController.swift:45 - updateProduct(_:)",
                    "ProductListViewController.swift:89 - tableView(_:didSelectRowAt:)",
                    "UIKit - UITableView._selectRowAtIndexPath(_:animated:scrollPosition:notifyDelegate:)"
                ),
                eventCount = 56,
                userCount = 14,
                level = "fatal"
            ),
            IssueTemplate(
                "NSInvalidArgumentException: Unrecognized selector",
                "NSInvalidArgumentException",
                "unrecognized selector sent to instance 0x600000abc123",
                "cocoa",
                listOf(
                    "CoreFoundation - __exceptionPreprocess",
                    "libobjc.A.dylib - objc_exception_throw",
                    "CheckoutViewController.swift:67 - processPayment()",
                    "UIKit - UIApplication.sendAction(_:to:from:for:)"
                ),
                eventCount = 22,
                userCount = 9,
                level = "error"
            ),
            IssueTemplate(
                "Network request timeout",
                "NSURLError",
                "The request timed out",
                "cocoa",
                listOf(
                    "NetworkService.swift:123 - fetchProducts(completion:)",
                    "ProductRepository.swift:45 - loadProducts()",
                    "CFNetwork - URLSession:task:didCompleteWithError:"
                ),
                eventCount = 91,
                userCount = 31,
                level = "warning"
            )
        )
        
        val rnIssues = listOf(
            IssueTemplate(
                "TypeError: Cannot read property 'name' of undefined",
                "TypeError",
                "Cannot read property 'name' of undefined",
                "javascript",
                listOf(
                    "at ProductDetail.render (ProductDetail.js:45:12)",
                    "at finishClassComponent (react-reconciler.js:234:11)",
                    "at updateClassComponent (react-reconciler.js:189:23)"
                ),
                eventCount = 203,
                userCount = 47,
                level = "error"
            ),
            IssueTemplate(
                "Invariant Violation: Element type is invalid",
                "InvariantViolation",
                "Element type is invalid: expected a string or a class/function but got: undefined",
                "javascript",
                listOf(
                    "at invariant (invariant.js:42:15)",
                    "at ReactElement (ReactElement.js:289:5)",
                    "at CartScreen.js:23:10"
                ),
                eventCount = 15,
                userCount = 8,
                level = "error"
            )
        )
        
        // Seed Android issues
        val (androidProjectId, _) = projects["android"]!!
        androidIssues.forEach { template ->
            seedIssue(db, androidProjectId, template)
        }
        
        // Seed iOS issues
        val (iosProjectId, _) = projects["ios"]!!
        iosIssues.forEach { template ->
            seedIssue(db, iosProjectId, template)
        }
        
        // Seed React Native issues
        val (rnProjectId, _) = projects["react-native"]!!
        rnIssues.forEach { template ->
            seedIssue(db, rnProjectId, template)
        }
        
        println("✅ Seeded ${androidIssues.size + iosIssues.size + rnIssues.size} issues with realistic events")
    }
    
    private suspend fun seedIssue(db: String, projectId: Long, template: IssueTemplate) {
        val issueId = UUID.randomUUID().toString()
        val culprit = template.stackTrace.firstOrNull() ?: "unknown"
        val firstSeen = randomTime(random.nextInt(7, 30))
        val lastSeen = randomTime(random.nextInt(0, 3))
        
        // Insert issue
        val issueQuery = """
            INSERT INTO $db.issues (
                issue_id, project_id, fingerprint, title, culprit, level,
                first_seen, last_seen, event_count, user_count, status
            ) VALUES (
                '$issueId',
                $projectId,
                '${UUID.randomUUID().toString().replace("-", "")}',
                '${template.title.replace("'", "''")}',
                '${culprit.replace("'", "''")}',
                '${template.level}',
                '${firstSeen.epochSecond}',
                '${lastSeen.epochSecond}',
                ${template.eventCount},
                ${template.userCount},
                'unresolved'
            )
        """.trimIndent()
        
        ClickHouseClient.execute(issueQuery)
        
        // Insert events for this issue
        val eventCount = minOf(template.eventCount, 50) // Insert subset of events
        repeat(eventCount) { i ->
            val eventId = UUID.randomUUID().toString()
            val timestamp = if (i < 5) {
                randomTime(random.nextInt(0, 2)) // Recent events
            } else {
                randomTime(random.nextInt(2, 30)) // Older events
            }
            
            val device = when (template.platform) {
                "android" -> androidDevices.random(random)
                "cocoa" -> iosDevices.random(random)
                else -> "Web Browser"
            }
            
            val osVersion = when (template.platform) {
                "android" -> "Android ${androidVersions.random(random)}"
                "cocoa" -> "iOS ${iosVersions.random(random)}"
                else -> "N/A"
            }
            
            val userEmail = userEmails.random(random)
            val stackTraceJson = template.stackTrace.joinToString(",") { 
                "\"${it.replace("'", "''")}\""
            }
            
            val eventQuery = """
                INSERT INTO $db.events (
                    event_id, project_id, issue_id, timestamp, received_at, event_type,
                    platform, level, message, exception_type, exception_value,
                    stack_trace, environment, release, user_id, user_email,
                    device_model, os_name, os_version
                ) VALUES (
                    '$eventId',
                    $projectId,
                    '$issueId',
                    toDateTime64(${timestamp.epochSecond}, 3, 'UTC'),
                    toDateTime64(${timestamp.epochSecond}, 3, 'UTC'),
                    'error',
                    '${template.platform}',
                    '${template.level}',
                    '${template.title.replace("'", "''")}',
                    '${template.exceptionType.replace("'", "''")}',
                    '${template.exceptionValue.replace("'", "''")}',
                    '[$stackTraceJson]',
                    'production',
                    '1.${random.nextInt(0, 4)}.${random.nextInt(0, 2)}',
                    '${UUID.randomUUID()}',
                    '$userEmail',
                    '$device',
                    '${if (template.platform == "android") "Android" else if (template.platform == "cocoa") "iOS" else "JavaScript"}',
                    '$osVersion'
                )
            """.trimIndent()
            
            ClickHouseClient.execute(eventQuery)
        }
    }
    
    private suspend fun seedLogData(projects: Map<String, Pair<Long, String>>) {
        val db = ClickHouseClient.getDatabase()
        val (androidProjectId, _) = projects["android"] ?: return
        
        // Services generating logs
        val services = listOf("api-server", "auth-service", "payment-processor", "notification-service", "cache-service")
        val environments = listOf("production", "staging")
        val hosts = listOf("api-prod-1", "api-prod-2", "api-prod-3", "worker-prod-1", "worker-prod-2")
        val levels = listOf(
            "info" to 60, "warn" to 25, "error" to 10, "debug" to 5
        )
        
        // Log message templates
        val logTemplates = listOf(
            Triple("info", "HTTP {method} {path} completed in {ms}ms with status {status}", 
                mapOf("method" to listOf("GET", "POST", "PUT"), "path" to listOf("/api/products", "/api/orders", "/api/users"), 
                      "ms" to listOf("45", "123", "234", "56", "789"), "status" to listOf("200", "201", "204"))),
            Triple("info", "User {user} authenticated successfully", 
                mapOf("user" to userEmails)),
            Triple("warn", "Cache miss for key: {key}", 
                mapOf("key" to listOf("product:123", "user:456", "session:789abc", "cart:def123"))),
            Triple("warn", "Rate limit approaching for IP {ip}: {count}/{limit} requests", 
                mapOf("ip" to listOf("192.168.1.100", "10.0.0.45", "172.16.0.23"), "count" to listOf("950", "980", "990"), "limit" to listOf("1000"))),
            Triple("error", "Database connection timeout after {timeout}s for query: {query}", 
                mapOf("timeout" to listOf("30", "45", "60"), "query" to listOf("SELECT * FROM orders", "UPDATE users SET", "INSERT INTO products"))),
            Triple("error", "Payment processing failed for order {orderId}: {reason}", 
                mapOf("orderId" to listOf("ORD-12345", "ORD-67890", "ORD-45678"), "reason" to listOf("card_declined", "insufficient_funds", "expired_card"))),
            Triple("error", "Failed to send notification to user {userId}: {error}", 
                mapOf("userId" to userEmails.map { it.substringBefore("@") }, "error" to listOf("device_not_registered", "network_timeout", "invalid_token"))),
            Triple("debug", "Redis command executed: {command} in {ms}ms", 
                mapOf("command" to listOf("GET product:123", "SET session:abc", "HGETALL user:456"), "ms" to listOf("2", "5", "12", "8")))
        )
        
        // Generate realistic logs with timestamps spread over last 15 minutes
        val now = Instant.now()
        val logCount = 250 // Generate 250 logs
        
        repeat(logCount) { i ->
            // Weight towards more recent logs
            val minutesAgo = when {
                i < 100 -> random.nextInt(0, 3)    // Last 3 minutes: 100 logs
                i < 200 -> random.nextInt(3, 8)    // 3-8 minutes ago: 100 logs
                else -> random.nextInt(8, 15)      // 8-15 minutes ago: 50 logs
            }
            val secondsOffset = random.nextInt(0, 60)
            val timestamp = now.minus((minutesAgo * 60 + secondsOffset).toLong(), ChronoUnit.SECONDS)
            
            val (templateLevel, messageTemplate, placeholders) = logTemplates.random(random)
            
            // Determine actual level based on weighted distribution
            val level = levels.let { options ->
                val total = options.sumOf { it.second }
                val rand = random.nextInt(total)
                var acc = 0
                options.first { 
                    acc += it.second
                    rand < acc
                }.first
            }
            
            // Use template's level if it's error/warn, otherwise use the random level
            val finalLevel = if (templateLevel in listOf("error", "warn")) templateLevel else level
            
            // Fill in placeholders
            var message = messageTemplate
            placeholders.forEach { (key, values) ->
                val value = values.random(random)
                message = message.replace("{$key}", value)
            }
            
            val logId = UUID.randomUUID().toString()
            val service = services.random(random)
            val environment = environments.random(random)
            val host = hosts.random(random)
            val traceId = UUID.randomUUID().toString().replace("-", "")
            val spanId = traceId.substring(0, 16)
            
            // Add some facets/tags for filtering
            val tags = mutableMapOf<String, String>()
            tags["service"] = service
            tags["environment"] = environment
            tags["version"] = "1.${random.nextInt(0, 5)}.${random.nextInt(0, 10)}"
            
            if (random.nextFloat() < 0.3) {
                tags["user_id"] = userEmails.random(random)
            }
            if (random.nextFloat() < 0.4) {
                tags["request_id"] = UUID.randomUUID().toString()
            }
            
            // Format tags as ClickHouse map
            val tagsJson = if (tags.isEmpty()) {
                "map()"
            } else {
                val pairs = tags.entries.joinToString(",") { (k, v) -> 
                    "'$k','${v.replace("'", "\\'")}'"
                }
                "map($pairs)"
            }
            
            val logQuery = """
                INSERT INTO $db.logs (
                    log_id, project_id, timestamp, received_at, level, message, body,
                    service, environment, host, source, trace_id, span_id, tags,
                    container_name, container_id, container_image, resource_attributes
                ) VALUES (
                    '$logId',
                    $androidProjectId,
                    toDateTime64(${timestamp.epochSecond}, 3, 'UTC'),
                    now64(3),
                    '$finalLevel',
                    '${message.replace("'", "\\'")}',
                    '${message.replace("'", "\\'")}',
                    '$service',
                    '$environment',
                    '$host',
                    'sdk',
                    '$traceId',
                    '$spanId',
                    $tagsJson,
                    '',
                    '',
                    '',
                    map()
                )
            """.trimIndent()
            
            try {
                val response = ClickHouseClient.execute(logQuery)
                if (!response.status.isSuccess()) {
                    val errorBody = response.bodyAsText()
                    println("❌ Error inserting log (attempt $i): ${response.status} - $errorBody")
                    if (i == 0) {
                        // Print the first failing query for debugging
                        println("Failing query: $logQuery")
                    }
                }
            } catch (e: Exception) {
                println("❌ Exception inserting log: ${e.message}")
                if (i == 0) {
                    e.printStackTrace()
                }
            }
        }
        
        println("✅ Seeded $logCount realistic log entries (spread over last 15 minutes)")
    }
    
    private suspend fun seedUptimeMonitors(organizationId: Int) {
        val db = ClickHouseClient.getDatabase()
        
        // Create realistic uptime monitors
        val monitors = listOf(
            Triple("Production API", "https://api.acme.com/health", 60),
            Triple("Website Homepage", "https://www.acme.com", 120),
            Triple("Payment Gateway", "https://payments.acme.com/status", 300),
            Triple("Mobile API", "https://mobile-api.acme.com/ping", 60),
            Triple("CDN Edge Server", "https://cdn.acme.com/healthz", 180)
        )
        
        val monitorIds = transaction {
            monitors.map { (name, url, intervalSeconds) ->
                val monitorId = UUID.randomUUID()
                val pushToken = UUID.randomUUID().toString().replace("-", "")
                val createdAt = Instant.now().minus(random.nextInt(7, 30).toLong(), ChronoUnit.DAYS)
                val lastCheckAt = Instant.now().minus(random.nextInt(0, 300).toLong(), ChronoUnit.SECONDS)
                
                UptimeMonitors.insert {
                    it[UptimeMonitors.id] = monitorId
                    it[UptimeMonitors.organizationId] = organizationId
                    it[UptimeMonitors.name] = name
                    it[UptimeMonitors.type] = "http"
                    it[UptimeMonitors.active] = true
                    it[UptimeMonitors.url] = url
                    it[UptimeMonitors.intervalSeconds] = intervalSeconds
                    it[UptimeMonitors.timeoutSeconds] = 30
                    it[UptimeMonitors.retries] = 2
                    it[UptimeMonitors.method] = "GET"
                    it[UptimeMonitors.status] = if (random.nextFloat() < 0.9) "up" else "down"
                    it[UptimeMonitors.lastCheckAt] = kotlinx.datetime.Instant.fromEpochMilliseconds(lastCheckAt.toEpochMilli())
                    it[UptimeMonitors.consecutiveFailures] = if (random.nextFloat() < 0.9) 0 else random.nextInt(1, 5)
                    it[UptimeMonitors.pushToken] = pushToken
                    it[UptimeMonitors.createdAt] = kotlinx.datetime.Instant.fromEpochMilliseconds(createdAt.toEpochMilli())
                    it[UptimeMonitors.updatedAt] = kotlinx.datetime.Instant.fromEpochMilliseconds(Instant.now().toEpochMilli())
                }
                
                Triple(monitorId, intervalSeconds, monitors.indexOf(Triple(name, url, intervalSeconds)))
            }
        }
        
        // Add heartbeat history to ClickHouse (outside transaction)
        monitorIds.forEach { (monitorId, intervalSeconds, _) ->
            val heartbeatCount = random.nextInt(20, 50)
            repeat(heartbeatCount) { i ->
                val heartbeatTime = Instant.now().minus((i * intervalSeconds).toLong(), ChronoUnit.SECONDS)
                val isUp = random.nextFloat() < 0.95 // 95% success rate
                
                val heartbeatQuery = """
                    INSERT INTO $db.uptime_heartbeats (
                        monitor_id, timestamp, status, response_time_ms, status_code, message, ping_ms
                    ) VALUES (
                        '$monitorId',
                        toDateTime64(${heartbeatTime.epochSecond}, 3, 'UTC'),
                        ${if (isUp) 1 else 0},
                        ${if (isUp) random.nextInt(30, 300) else -1},
                        ${if (isUp) 200 else random.nextInt(500, 504)},
                        '${if (isUp) "OK" else "Connection timeout"}',
                        ${if (isUp) random.nextInt(10, 100).toFloat() else -1f}
                    )
                """.trimIndent()
                
                ClickHouseClient.execute(heartbeatQuery)
            }
        }
        
        println("✅ Seeded ${monitors.size} uptime monitors with heartbeat history")
    }
    
    private suspend fun seedMonitoringSystems(organizationId: Int) {
        val systemsList = listOf(
            Triple("api-prod-1.acme.com", "Ubuntu 22.04 LTS", "x86_64"),
            Triple("api-prod-2.acme.com", "Ubuntu 22.04 LTS", "x86_64"),
            Triple("worker-prod-1.acme.com", "Debian 11", "x86_64"),
            Triple("db-primary.acme.com", "Ubuntu 20.04 LTS", "x86_64"),
            Triple("cache-redis-1.acme.com", "Alpine Linux 3.18", "x86_64")
        )
        
        transaction {
            // Create realistic monitoring systems
            systemsList.forEach { (hostname, osInfo, arch) ->
                val systemId = UUID.randomUUID()
                val agentKeyHash = BCrypt.hashpw(UUID.randomUUID().toString(), BCrypt.gensalt())
                val createdAt = Instant.now().minus(random.nextInt(7, 60).toLong(), ChronoUnit.DAYS)
                val lastSeenAt = Instant.now().minus(random.nextInt(0, 60).toLong(), ChronoUnit.SECONDS)
                
                Systems.insert {
                    it[Systems.id] = systemId
                    it[Systems.organization_id] = organizationId
                    it[Systems.name] = hostname
                    it[Systems.host] = "10.0.${random.nextInt(1, 10)}.${random.nextInt(1, 255)}"
                    it[Systems.agent_key_hash] = agentKeyHash
                    it[Systems.status] = "online"
                    it[Systems.last_seen_at] = kotlinx.datetime.Instant.fromEpochMilliseconds(lastSeenAt.toEpochMilli())
                    it[Systems.agent_version] = "1.2.${random.nextInt(0, 10)}"
                    it[Systems.os] = osInfo
                    it[Systems.arch] = arch
                    it[Systems.created_at] = kotlinx.datetime.Instant.fromEpochMilliseconds(createdAt.toEpochMilli())
                    it[Systems.updated_at] = kotlinx.datetime.Instant.fromEpochMilliseconds(Instant.now().toEpochMilli())
                }
            }
        }
        
        println("✅ Seeded ${systemsList.size} monitoring systems")
    }
    
    private data class IssueTemplate(
        val title: String,
        val exceptionType: String,
        val exceptionValue: String,
        val platform: String,
        val stackTrace: List<String>,
        val eventCount: Int,
        val userCount: Int,
        val level: String
    )
}

suspend fun main() {
    DemoDataSeeder.seed()
}
