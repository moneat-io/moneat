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
import kotlin.time.Duration.Companion.days
import kotlinx.datetime.Clock
import kotlinx.datetime.DateTimeUnit

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
            
            // Add PRO subscription for demo user (so screenshots don't show upgrade prompts)
            println("Creating PRO subscription for demo org...")
            val now = Clock.System.now()
            val periodEnd = now + 30.days
            Subscriptions.insert {
                it[organization_id] = orgId
                it[plan] = "pro"
                it[status] = "active"
                it[billing_interval] = "monthly"
                it[current_period_start] = now
                it[current_period_end] = periodEnd
                it[payg_budget_cents] = 0
                it[payg_used_units] = 0
                it[payg_used_micros] = 0
                it[pending_meter_units] = 0
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
        
        // Seed performance/transaction data
        println("\nSeeding performance/transaction data...")
        seedTransactionData(projects)
        
        // Seed session replay data
        println("\nSeeding session replay data...")
        seedReplayData(projects)
        
        // Seed user feedback data
        println("\nSeeding user feedback data...")
        seedFeedbackData(projects)
        
        // Always seed uptime monitors and monitoring systems (or update if they exist)
        println("\nSeeding uptime monitors...")
        seedUptimeMonitors(orgId)
        
        // Seed monitoring systems
        println("\nSeeding monitoring systems...")
        seedMonitoringSystems(orgId)
        
        // Seed status pages
        println("\nSeeding status pages...")
        seedStatusPages(orgId)
        
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
                eventCount = 347,
                userCount = 89,
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
                eventCount = 156,
                userCount = 42,
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
                eventCount = 89,
                userCount = 34,
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
                eventCount = 234,
                userCount = 67,
                level = "error"
            ),
            IssueTemplate(
                "SQLiteException: database is locked",
                "android.database.sqlite.SQLiteException",
                "database is locked (code 5 SQLITE_BUSY)",
                "android",
                listOf(
                    "at android.database.sqlite.SQLiteConnection.nativeExecute(Native Method)",
                    "at com.acme.shopping.data.local.CartDao.updateQuantity(CartDao.kt:67)",
                    "at com.acme.shopping.repository.CartRepository.updateItem(CartRepository.kt:89)"
                ),
                eventCount = 178,
                userCount = 54,
                level = "error"
            ),
            IssueTemplate(
                "ConcurrentModificationException in WishlistAdapter",
                "java.util.ConcurrentModificationException",
                "Collection was modified during iteration",
                "android",
                listOf(
                    "at java.util.ArrayList\$Itr.checkForComodification(ArrayList.java:911)",
                    "at com.acme.shopping.ui.WishlistAdapter.notifyDataChanged(WishlistAdapter.kt:123)",
                    "at com.acme.shopping.ui.WishlistFragment.onItemRemoved(WishlistFragment.kt:156)"
                ),
                eventCount = 92,
                userCount = 38,
                level = "error"
            ),
            IssueTemplate(
                "JSONException: No value for 'price'",
                "org.json.JSONException",
                "No value for price",
                "android",
                listOf(
                    "at org.json.JSONObject.get(JSONObject.java:389)",
                    "at com.acme.shopping.api.ProductParser.parseProduct(ProductParser.kt:45)",
                    "at com.acme.shopping.api.ApiClient.fetchProductDetails(ApiClient.kt:234)"
                ),
                eventCount = 267,
                userCount = 71,
                level = "error"
            ),
            IssueTemplate(
                "IndexOutOfBoundsException in SearchResultsAdapter",
                "java.lang.IndexOutOfBoundsException",
                "Index: 15, Size: 12",
                "android",
                listOf(
                    "at java.util.ArrayList.get(ArrayList.java:437)",
                    "at com.acme.shopping.ui.SearchResultsAdapter.onBindViewHolder(SearchResultsAdapter.kt:78)",
                    "at androidx.recyclerview.widget.RecyclerView\$Adapter.onBindViewHolder(RecyclerView.java:7065)"
                ),
                eventCount = 143,
                userCount = 49,
                level = "error"
            ),
            IssueTemplate(
                "ActivityNotFoundException: No Activity found to handle Intent",
                "android.content.ActivityNotFoundException",
                "No Activity found to handle Intent { act=android.intent.action.VIEW dat=acme://product/123 }",
                "android",
                listOf(
                    "at android.app.Instrumentation.checkStartActivityResult(Instrumentation.java:2073)",
                    "at com.acme.shopping.util.DeepLinkHandler.openProduct(DeepLinkHandler.kt:56)",
                    "at com.acme.shopping.MainActivity.handleIntent(MainActivity.kt:234)"
                ),
                eventCount = 67,
                userCount = 28,
                level = "error"
            ),
            IssueTemplate(
                "InflateException: Error inflating class ImageView",
                "android.view.InflateException",
                "Binary XML file line #23: Error inflating class ImageView",
                "android",
                listOf(
                    "at android.view.LayoutInflater.createViewFromTag(LayoutInflater.java:829)",
                    "at com.acme.shopping.ui.ProductListAdapter.onCreateViewHolder(ProductListAdapter.kt:45)",
                    "at androidx.recyclerview.widget.RecyclerView\$Adapter.createViewHolder(RecyclerView.java:7078)"
                ),
                eventCount = 51,
                userCount = 19,
                level = "error"
            ),
            IssueTemplate(
                "Resources\$NotFoundException: Resource ID #0x7f080abc",
                "android.content.res.Resources\$NotFoundException",
                "Resource ID #0x7f080abc",
                "android",
                listOf(
                    "at android.content.res.Resources.getValue(Resources.java:1351)",
                    "at com.acme.shopping.ui.theme.ThemeManager.applyTheme(ThemeManager.kt:89)",
                    "at com.acme.shopping.MainActivity.onCreate(MainActivity.kt:67)"
                ),
                eventCount = 34,
                userCount = 15,
                level = "error"
            ),
            IssueTemplate(
                "TimeoutException: Coroutine timeout",
                "kotlinx.coroutines.TimeoutCancellationException",
                "Timed out waiting for 5000 ms",
                "android",
                listOf(
                    "at kotlinx.coroutines.withTimeout(Timeout.kt:45)",
                    "at com.acme.shopping.api.ApiClient.fetchWithTimeout(ApiClient.kt:123)",
                    "at com.acme.shopping.repository.ProductRepository.loadProducts(ProductRepository.kt:89)"
                ),
                eventCount = 198,
                userCount = 62,
                level = "warning"
            ),
            IssueTemplate(
                "NumberFormatException: For input string '€12.99'",
                "java.lang.NumberFormatException",
                "For input string: '€12.99'",
                "android",
                listOf(
                    "at java.lang.Long.parseLong(Long.java:596)",
                    "at com.acme.shopping.util.PriceFormatter.parsePrice(PriceFormatter.kt:34)",
                    "at com.acme.shopping.ui.CartFragment.calculateTotal(CartFragment.kt:145)"
                ),
                eventCount = 112,
                userCount = 41,
                level = "error"
            ),
            IssueTemplate(
                "SecurityException: Permission denied",
                "java.lang.SecurityException",
                "Permission denial: writing to settings requires android.permission.WRITE_SETTINGS",
                "android",
                listOf(
                    "at android.os.Parcel.createException(Parcel.java:2071)",
                    "at com.acme.shopping.util.SettingsManager.savePreference(SettingsManager.kt:78)",
                    "at com.acme.shopping.ui.SettingsActivity.onToggleChanged(SettingsActivity.kt:123)"
                ),
                eventCount = 23,
                userCount = 12,
                level = "error"
            ),
            IssueTemplate(
                "FileNotFoundException: /storage/emulated/0/cache/image.jpg",
                "java.io.FileNotFoundException",
                "/storage/emulated/0/cache/image.jpg: open failed: ENOENT (No such file or directory)",
                "android",
                listOf(
                    "at java.io.FileInputStream.open0(Native Method)",
                    "at com.acme.shopping.util.ImageCache.loadFromDisk(ImageCache.kt:89)",
                    "at com.acme.shopping.ui.ProductDetailFragment.displayCachedImage(ProductDetailFragment.kt:156)"
                ),
                eventCount = 167,
                userCount = 58,
                level = "warning"
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
            
            // Generate realistic breadcrumbs
            val breadcrumbs = generateBreadcrumbs(template.platform, template.title)
            
            // Generate realistic contexts
            val contexts = generateContexts(template.platform, device, osVersion)
            
            val eventQuery = """
                INSERT INTO $db.events (
                    event_id, project_id, issue_id, timestamp, received_at, event_type,
                    platform, level, message, exception_type, exception_value,
                    stack_trace, environment, release, user_id, user_email,
                    device_model, os_name, os_version, breadcrumbs, contexts
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
                    '$osVersion',
                    '${breadcrumbs.replace("'", "\\'")}',
                    '${contexts.replace("'", "\\'")}'
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
    
    private suspend fun seedTransactionData(projects: Map<String, Pair<Long, String>>) {
        val db = ClickHouseClient.getDatabase()
        
        // Transaction templates with realistic operations and timings
        data class TransactionTemplate(
            val name: String,
            val op: String,
            val avgDuration: Double,
            val variance: Double,
            val failureRate: Double,
            val platform: String
        )
        
        val transactionTemplates = listOf(
            // API endpoints
            TransactionTemplate("GET /api/products", "http.server", 145.0, 50.0, 0.5, "android"),
            TransactionTemplate("POST /api/checkout", "http.server", 320.0, 120.0, 2.3, "android"),
            TransactionTemplate("GET /api/user/profile", "http.server", 89.0, 30.0, 0.2, "android"),
            TransactionTemplate("PUT /api/cart/items", "http.server", 210.0, 80.0, 1.1, "android"),
            TransactionTemplate("GET /api/search", "http.server", 780.0, 300.0, 3.5, "android"),
            
            // Database queries
            TransactionTemplate("SELECT products WHERE category", "db.sql.query", 45.0, 15.0, 0.1, "android"),
            TransactionTemplate("INSERT INTO orders", "db.sql.query", 120.0, 40.0, 0.8, "android"),
            TransactionTemplate("UPDATE cart_items", "db.sql.query", 67.0, 20.0, 0.3, "android"),
            
            // UI screens/navigation
            TransactionTemplate("ProductListActivity", "navigation", 234.0, 90.0, 1.2, "android"),
            TransactionTemplate("ProductDetailActivity", "navigation", 189.0, 70.0, 0.7, "android"),
            TransactionTemplate("CheckoutActivity", "navigation", 456.0, 150.0, 2.1, "android"),
            TransactionTemplate("CartActivity", "navigation", 123.0, 50.0, 0.4, "android"),
            
            // Background tasks
            TransactionTemplate("sync_user_data", "task", 567.0, 200.0, 1.8, "android"),
            TransactionTemplate("upload_analytics", "task", 890.0, 350.0, 4.2, "android"),
            TransactionTemplate("refresh_product_cache", "task", 1240.0, 500.0, 2.9, "android"),
        )
        
        val (androidProjectId, _) = projects["android"] ?: return
        var totalTransactions = 0
        
        // Seed transactions for the past 7 days
        transactionTemplates.forEach { template ->
            // Number of transactions varies by template (more popular endpoints have more data)
            val transactionCount = when {
                template.op == "http.server" && "GET" in template.name -> random.nextInt(200, 400)
                template.op == "http.server" -> random.nextInt(100, 250)
                template.op == "navigation" -> random.nextInt(150, 300)
                template.op == "db.sql.query" -> random.nextInt(300, 600)
                else -> random.nextInt(50, 150)
            }
            
            repeat(transactionCount) { i ->
                val eventId = UUID.randomUUID().toString()
                // Distribute over last 7 days, with more recent data
                val daysAgo = when {
                    i < transactionCount * 0.3 -> random.nextInt(0, 1) // 30% in last day
                    i < transactionCount * 0.6 -> random.nextInt(1, 3) // 30% in last 2 days
                    else -> random.nextInt(3, 7) // rest distributed over week
                }
                val timestamp = randomTime(daysAgo)
                
                // Calculate duration with variance
                val baseDuration = template.avgDuration + (random.nextDouble(-template.variance, template.variance))
                val duration = maxOf(10.0, baseDuration) // minimum 10ms
                
                // Determine if this is a failure
                val isFailed = random.nextDouble() * 100 < template.failureRate
                val level = if (isFailed) "error" else "info"
                
                val userEmail = userEmails.random(random)
                val device = androidDevices.random(random)
                val osVersion = "Android ${androidVersions.random(random)}"
                val environment = if (random.nextDouble() < 0.85) "production" else "staging"
                
                val eventQuery = """
                    INSERT INTO $db.events (
                        event_id, project_id, timestamp, received_at, event_type,
                        level, platform, environment, release, 
                        transaction_name, transaction_op, duration_ms,
                        user_id, user_email, device_model, os_name, os_version,
                        browser_name, browser_version, message
                    ) VALUES (
                        '$eventId',
                        $androidProjectId,
                        toDateTime64(${timestamp.epochSecond}, 3, 'UTC'),
                        toDateTime64(${timestamp.epochSecond}, 3, 'UTC'),
                        'transaction',
                        '$level',
                        '${template.platform}',
                        '$environment',
                        '1.${random.nextInt(0, 4)}.${random.nextInt(0, 2)}',
                        '${template.name.replace("'", "''")}',
                        '${template.op}',
                        $duration,
                        '${UUID.randomUUID()}',
                        '$userEmail',
                        '$device',
                        'Android',
                        '$osVersion',
                        '',
                        '',
                        ''
                    )
                """.trimIndent()
                
                try {
                    ClickHouseClient.execute(eventQuery)
                    totalTransactions++
                } catch (e: Exception) {
                    if (i < 2) println("❌ Error inserting transaction: ${e.message}")
                }
            }
        }
        
        println("✅ Seeded $totalTransactions transaction events across ${transactionTemplates.size} transaction types")
    }
    
    private suspend fun seedReplayData(projects: Map<String, Pair<Long, String>>) {
        val db = ClickHouseClient.getDatabase()
        val (androidProjectId, _) = projects["android"] ?: return
        
        val browsers = listOf("Chrome", "Firefox", "Safari", "Edge")
        val browserVersions = mapOf(
            "Chrome" to listOf("120.0", "119.0", "118.0"),
            "Firefox" to listOf("121.0", "120.0"),
            "Safari" to listOf("17.2", "17.1", "16.6"),
            "Edge" to listOf("120.0", "119.0")
        )
        
        val osOptions = listOf(
            "Windows" to listOf("10", "11"),
            "macOS" to listOf("14.2", "13.6", "12.7"),
            "Linux" to listOf("Ubuntu 22.04", "Fedora 39")
        )
        
        val urls = listOf(
            "https://acme-shopping.com/",
            "https://acme-shopping.com/products",
            "https://acme-shopping.com/products/electronics",
            "https://acme-shopping.com/products/123/details",
            "https://acme-shopping.com/cart",
            "https://acme-shopping.com/checkout",
            "https://acme-shopping.com/account",
            "https://acme-shopping.com/orders"
        )
        
        val replayCount = 60
        var totalSegments = 0
        
        repeat(replayCount) { i ->
            val replayId = UUID.randomUUID().toString()
            
            // Session timing
            val daysAgo = when {
                i < replayCount * 0.4 -> random.nextInt(0, 1) // 40% in last day
                i < replayCount * 0.7 -> random.nextInt(1, 3) // 30% in days 1-3
                else -> random.nextInt(3, 7) // rest over week
            }
            val startTime = randomTime(daysAgo)
            
            // Session duration (10 seconds to 20 minutes)
            val durationSeconds = when {
                random.nextDouble() < 0.15 -> random.nextInt(10, 30) // 15% very short (bounced)
                random.nextDouble() < 0.50 -> random.nextInt(30, 180) // 35% short (30s-3min)
                random.nextDouble() < 0.80 -> random.nextInt(180, 600) // 30% medium (3-10min)
                else -> random.nextInt(600, 1200) // 20% long sessions (10-20min)
            }
            
            // User info
            val userEmail = userEmails.random(random)
            val userId = UUID.randomUUID().toString()
            val username = userEmail.substringBefore("@")
            
            // Device/browser info
            val browser = browsers.random(random)
            val browserVersion = browserVersions[browser]!!.random(random)
            val (osName, osVersionList) = osOptions.random(random)
            val osVersion = osVersionList.random(random)
            
            // Activity level (0-100, higher = more interactions)
            val activity = when {
                durationSeconds < 30 -> random.nextInt(0, 20) // Low activity for short sessions
                durationSeconds < 180 -> random.nextInt(20, 60) // Medium for medium sessions
                else -> random.nextInt(60, 100) // High for long sessions
            }
            
            // Determine if session had errors
            val hasErrors = random.nextDouble() < 0.25 // 25% of replays have errors
            val errorCount = if (hasErrors) random.nextInt(1, 4) else 0
            val errorIds = if (hasErrors) {
                (1..errorCount).map { UUID.randomUUID().toString() }
            } else emptyList()
            
            // URLs visited during session (1-6 pages)
            val pageCount = when {
                durationSeconds < 60 -> random.nextInt(1, 3)
                durationSeconds < 300 -> random.nextInt(2, 5)
                else -> random.nextInt(3, 7)
            }.coerceAtMost(urls.size)
            
            val visitedUrls = urls.shuffled(random).take(pageCount)
            
            val environment = if (random.nextDouble() < 0.90) "production" else "staging"
            
            // Generate 1-3 segments for this replay (simulating chunks of replay data)
            val segmentCount = when {
                durationSeconds < 120 -> 1
                durationSeconds < 600 -> random.nextInt(1, 3)
                else -> random.nextInt(2, 4)
            }
            
            repeat(segmentCount) { segmentIndex ->
                val segmentId = segmentIndex.toUInt()
                val segmentDuration = durationSeconds / segmentCount
                val segmentTime = startTime.plus((segmentDuration * segmentIndex).toLong(), ChronoUnit.SECONDS)
                
                // Format arrays for ClickHouse
                val urlsArray = visitedUrls.joinToString(",") { "'${it.replace("'", "''")}'" }
                val errorIdsArray = errorIds.joinToString(",") { "'$it'" }
                
                val replayQuery = """
                    INSERT INTO $db.replay_events (
                        replay_id, project_id, segment_id, timestamp, replay_start_timestamp,
                        urls, error_ids, trace_ids, environment, release, platform,
                        user_id, user_email, user_username, user_ip_address,
                        sdk_name, sdk_version, browser_name, browser_version,
                        os_name, os_version, device_name, device_family, activity, tags
                    ) VALUES (
                        '$replayId',
                        $androidProjectId,
                        $segmentId,
                        toDateTime64(${segmentTime.epochSecond}, 3, 'UTC'),
                        toDateTime64(${startTime.epochSecond}, 3, 'UTC'),
                        [$urlsArray],
                        [$errorIdsArray],
                        [],
                        '$environment',
                        '1.${random.nextInt(0, 4)}.${random.nextInt(0, 2)}',
                        'javascript',
                        '$userId',
                        '$userEmail',
                        '$username',
                        '${random.nextInt(1, 255)}.${random.nextInt(1, 255)}.${random.nextInt(1, 255)}.${random.nextInt(1, 255)}',
                        'sentry.javascript.react',
                        '7.99.0',
                        '$browser',
                        '$browserVersion',
                        '$osName',
                        '$osVersion',
                        '',
                        '',
                        $activity,
                        ''
                    )
                """.trimIndent()
                
                try {
                    ClickHouseClient.execute(replayQuery)
                    totalSegments++
                } catch (e: Exception) {
                    if (segmentIndex == 0) println("❌ Error inserting replay: ${e.message}")
                }
            }
        }
        
        println("✅ Seeded $replayCount session replays ($totalSegments segments total)")
    }
    
    private suspend fun seedFeedbackData(projects: Map<String, Pair<Long, String>>) {
        val db = ClickHouseClient.getDatabase()
        val (androidProjectId, _) = projects["android"] ?: return
        
        // Feedback message templates with realistic user feedback
        val feedbackTemplates = listOf(
            Triple("App crashes when trying to checkout with saved card", "sarah.johnson@example.com", "Sarah J."),
            Triple("Great app but the product images take too long to load", "mike.chen@example.com", "Mike Chen"),
            Triple("Cart doesn't update after adding items, have to refresh", "alex.rivera@example.com", "Alex R."),
            Triple("Love the new UI! Much cleaner than before", "priya.patel@example.com", "Priya Patel"),
            Triple("Can't apply discount code at checkout - keeps saying invalid", "john.smith@example.com", "John Smith"),
            Triple("App froze on payment screen - lost my order", "emma.williams@example.com", "Emma W."),
            Triple("Search results are not relevant to what I'm looking for", "mike.chen@example.com", "Mike Chen"),
            Triple("Would be great to have a wishlist feature!", "sarah.johnson@example.com", "Sarah Johnson"),
            Triple("The app is very slow when scrolling through products", "alex.rivera@example.com", "Alex Rivera"),
            Triple("Got an error message when viewing product details", "priya.patel@example.com", "Priya P."),
            Triple("Unable to login with Google - keeps timing out", "john.smith@example.com", "John S."),
            Triple("Product recommendations are really helpful!", "emma.williams@example.com", "Emma Williams"),
            Triple("App crashed while browsing electronics category", "sarah.johnson@example.com", "Sarah J."),
            Triple("Missing product images on several items", "mike.chen@example.com", "Mike C."),
            Triple("Filter options don't work properly", "alex.rivera@example.com", "Alex"),
        )
        
        val urls = listOf(
            "https://acme-shopping.com/products",
            "https://acme-shopping.com/cart",
            "https://acme-shopping.com/checkout",
            "https://acme-shopping.com/products/123/details",
            "https://acme-shopping.com/account",
            "https://acme-shopping.com/search",
            "https://acme-shopping.com/products/electronics"
        )
        
        // Get some event IDs to associate feedback with
        val eventIdsQuery = """
            SELECT toString(event_id) as event_id
            FROM $db.events
            WHERE project_id = $androidProjectId
                AND event_type = 'error'
            LIMIT 8
            FORMAT TabSeparated
        """.trimIndent()
        
        val eventIds = try {
            val response = ClickHouseClient.executeWithFormat(eventIdsQuery, "TabSeparated")
            response.lines().filter { it.isNotBlank() }
        } catch (e: Exception) {
            emptyList()
        }
        
        // Get some replay IDs to associate feedback with
        val replayIdsQuery = """
            SELECT DISTINCT toString(replay_id) as replay_id
            FROM $db.replay_events
            WHERE project_id = $androidProjectId
            LIMIT 5
            FORMAT TabSeparated
        """.trimIndent()
        
        val replayIds = try {
            val response = ClickHouseClient.executeWithFormat(replayIdsQuery, "TabSeparated")
            response.lines().filter { it.isNotBlank() }
        } catch (e: Exception) {
            emptyList()
        }
        
        var feedbackCount = 0
        
        feedbackTemplates.forEachIndexed { index, (message, email, name) ->
            val feedbackId = UUID.randomUUID().toString()
            
            // Distribute over last 14 days
            val daysAgo = when {
                index < 5 -> random.nextInt(0, 2)  // 5 recent
                index < 10 -> random.nextInt(2, 7) // 5 medium
                else -> random.nextInt(7, 14)      // rest older
            }
            val timestamp = randomTime(daysAgo)
            
            // Some feedback has associated events or replays
            val associatedEventId = if (eventIds.isNotEmpty() && random.nextDouble() < 0.4) {
                eventIds.random(random)
            } else ""
            
            val associatedReplayId = if (replayIds.isNotEmpty() && random.nextDouble() < 0.3) {
                replayIds.random(random)
            } else ""
            
            val url = urls.random(random)
            val environment = if (random.nextDouble() < 0.9) "production" else "staging"
            
            // Status distribution: 60% unresolved, 30% resolved, 10% archived
            val status = when {
                random.nextDouble() < 0.6 -> "unresolved"
                random.nextDouble() < 0.9 -> "resolved"
                else -> "archived"
            }
            
            val userId = UUID.randomUUID().toString()
            
            val feedbackQuery = """
                INSERT INTO $db.user_feedback (
                    feedback_id, project_id, timestamp, received_at,
                    message, contact_email, name, url,
                    associated_event_id, replay_id, environment, release,
                    platform, user_id, user_email, user_username, user_ip_address,
                    sdk_name, sdk_version, tags, status, updated_at
                ) VALUES (
                    '$feedbackId',
                    $androidProjectId,
                    toDateTime64(${timestamp.epochSecond}, 3, 'UTC'),
                    toDateTime64(${timestamp.epochSecond}, 3, 'UTC'),
                    '${message.replace("'", "\\'")}',
                    '$email',
                    '${name.replace("'", "\\'")}',
                    '$url',
                    '$associatedEventId',
                    '$associatedReplayId',
                    '$environment',
                    '1.${random.nextInt(0, 4)}.${random.nextInt(0, 2)}',
                    'android',
                    '$userId',
                    '$email',
                    '${name.replace("'", "\\'")}',
                    '${random.nextInt(1, 255)}.${random.nextInt(1, 255)}.${random.nextInt(1, 255)}.${random.nextInt(1, 255)}',
                    'sentry.java.android',
                    '6.34.0',
                    map(),
                    '$status',
                    toDateTime64(${timestamp.epochSecond}, 3, 'UTC')
                )
            """.trimIndent()
            
            try {
                ClickHouseClient.execute(feedbackQuery)
                feedbackCount++
            } catch (e: Exception) {
                if (index == 0) println("❌ Error inserting feedback: ${e.message}")
            }
        }
        
        println("✅ Seeded $feedbackCount user feedback items")
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
    
    private suspend fun seedStatusPages(organizationId: Int) {
        // Get existing monitors to associate with status page
        val monitorIds = transaction {
            UptimeMonitors.selectAll()
                .where { UptimeMonitors.organizationId eq organizationId }
                .map { it[UptimeMonitors.id] to it[UptimeMonitors.name] }
        }
        
        if (monitorIds.isEmpty()) {
            println("⚠️  No monitors found. Skipping status page seeding.")
            return
        }
        
        // Create a status page
        val statusPageId = transaction {
            val pageId = UUID.randomUUID()
            val createdAt = Instant.now().minus(random.nextInt(14, 30).toLong(), ChronoUnit.DAYS)
            
            StatusPages.insert {
                it[StatusPages.id] = pageId
                it[StatusPages.organizationId] = organizationId
                it[StatusPages.name] = "Acme Services Status"
                it[StatusPages.slug] = "acme-status"
                it[StatusPages.description] = "Real-time status and incident history for all Acme services"
                it[StatusPages.logoUrl] = null
                it[StatusPages.faviconUrl] = null
                it[StatusPages.primaryColor] = "#3B82F6"
                it[StatusPages.darkMode] = false
                it[StatusPages.showUptimeHistory] = true
                it[StatusPages.historyDays] = 90
                it[StatusPages.isPublic] = true
                it[StatusPages.createdAt] = kotlinx.datetime.Instant.fromEpochMilliseconds(createdAt.toEpochMilli())
                it[StatusPages.updatedAt] = kotlinx.datetime.Instant.fromEpochMilliseconds(Instant.now().toEpochMilli())
            }
            pageId
        }
        
        // Associate all monitors with the status page
        transaction {
            monitorIds.forEachIndexed { index, (monitorId, _) ->
                StatusPageMonitors.insert {
                    it[StatusPageMonitors.statusPageId] = statusPageId
                    it[StatusPageMonitors.monitorId] = monitorId
                    it[StatusPageMonitors.displayName] = null // Use actual monitor name
                    it[StatusPageMonitors.sortOrder] = index
                    it[StatusPageMonitors.createdAt] = kotlinx.datetime.Instant.fromEpochMilliseconds(Instant.now().toEpochMilli())
                }
            }
        }
        
        // Create some realistic incidents
        val incidents = listOf(
            // Resolved incident from 2 days ago
            listOf(
                "Database Connection Pool Exhausted" to "resolved",
                "Our primary database experienced connection pool exhaustion causing degraded performance." to "investigating",
                "Database team has identified the issue as a connection leak in the payment service." to "identified",
                "Fix deployed to production. Monitoring for stability." to "monitoring",
                "All systems operating normally. Connection pool has stabilized." to "resolved"
            ) to Pair("major", 2),
            
            // Recently resolved incident
            listOf(
                "API Gateway Timeout Issues" to "resolved",
                "Investigating reports of increased timeout errors on API gateway." to "investigating",
                "Root cause identified as upstream service degradation. Implementing retry logic." to "identified",
                "Issue has been resolved. All API endpoints responding normally." to "resolved"
            ) to Pair("minor", 0),
            
            // Ongoing minor incident
            listOf(
                "Elevated Error Rates on Mobile API" to "monitoring",
                "We're seeing elevated error rates on the mobile API endpoint. Investigating the root cause." to "investigating",
                "Issue identified as a cache invalidation problem. Applying fix now." to "identified",
                "Fix applied. Monitoring error rates for the next hour to ensure stability." to "monitoring"
            ) to Pair("minor", 0),
            
            // Scheduled maintenance (future)
            listOf(
                "Database Maintenance Window" to "scheduled",
                "We will be performing routine database maintenance. Services may experience brief interruptions." to "scheduled"
            ) to Pair("none", -1)
        )
        
        transaction {
            incidents.forEach { (updates, metadata) ->
                val (impact, daysAgo) = metadata
                val incidentId = UUID.randomUUID()
                
                val firstUpdate = updates.first()
                val lastUpdate = updates.last()
                val finalStatus = lastUpdate.second
                val title = firstUpdate.first
                
                val createdAt = if (daysAgo >= 0) {
                    Instant.now().minus(daysAgo.toLong(), ChronoUnit.DAYS)
                        .minus(random.nextInt(1, 12).toLong(), ChronoUnit.HOURS)
                } else {
                    Instant.now().plus(7, ChronoUnit.DAYS) // Future maintenance
                }
                
                val resolvedAt = if (finalStatus == "resolved" || finalStatus == "completed") {
                    createdAt.plus(random.nextInt(30, 180).toLong(), ChronoUnit.MINUTES)
                } else null
                
                val isScheduledMaintenance = finalStatus == "scheduled"
                
                StatusPageIncidents.insert {
                    it[StatusPageIncidents.id] = incidentId
                    it[StatusPageIncidents.statusPageId] = statusPageId
                    it[StatusPageIncidents.title] = title
                    it[StatusPageIncidents.status] = finalStatus
                    it[StatusPageIncidents.type] = if (isScheduledMaintenance) "maintenance" else "incident"
                    it[StatusPageIncidents.impact] = impact
                    
                    if (isScheduledMaintenance) {
                        it[StatusPageIncidents.scheduledStartAt] = kotlinx.datetime.Instant.fromEpochMilliseconds(
                            Instant.now().plus(7, ChronoUnit.DAYS).toEpochMilli()
                        )
                        it[StatusPageIncidents.scheduledEndAt] = kotlinx.datetime.Instant.fromEpochMilliseconds(
                            Instant.now().plus(7, ChronoUnit.DAYS).plus(2, ChronoUnit.HOURS).toEpochMilli()
                        )
                    } else {
                        it[StatusPageIncidents.scheduledStartAt] = null
                        it[StatusPageIncidents.scheduledEndAt] = null
                    }
                    
                    it[StatusPageIncidents.resolvedAt] = resolvedAt?.let { resolved ->
                        kotlinx.datetime.Instant.fromEpochMilliseconds(resolved.toEpochMilli())
                    }
                    it[StatusPageIncidents.createdAt] = kotlinx.datetime.Instant.fromEpochMilliseconds(createdAt.toEpochMilli())
                    it[StatusPageIncidents.updatedAt] = kotlinx.datetime.Instant.fromEpochMilliseconds(
                        resolvedAt?.toEpochMilli() ?: createdAt.toEpochMilli()
                    )
                }
                
                // Add incident updates
                updates.forEachIndexed { index, (message, status) ->
                    val updateTime = createdAt.plus((index * 30L), ChronoUnit.MINUTES)
                    
                    StatusPageIncidentUpdates.insert {
                        it[StatusPageIncidentUpdates.id] = UUID.randomUUID()
                        it[StatusPageIncidentUpdates.incidentId] = incidentId
                        it[StatusPageIncidentUpdates.status] = status
                        it[StatusPageIncidentUpdates.message] = message
                        it[StatusPageIncidentUpdates.createdAt] = kotlinx.datetime.Instant.fromEpochMilliseconds(updateTime.toEpochMilli())
                    }
                }
            }
        }
        
        println("✅ Seeded 1 status page with ${monitorIds.size} monitors and ${incidents.size} incidents")
    }
    
    private fun generateBreadcrumbs(platform: String, @Suppress("UNUSED_PARAMETER") errorTitle: String): String {
        val breadcrumbs = mutableListOf<String>()
        val now = System.currentTimeMillis()
        
        when (platform) {
            "android" -> {
                // Android-specific breadcrumbs
                breadcrumbs.add("""{"type":"navigation","category":"navigation","message":"MainActivity -> ProductListFragment","level":"info","timestamp":${now - 30000}}""")
                breadcrumbs.add("""{"type":"user","category":"ui.click","message":"User tapped product item #123","level":"info","timestamp":${now - 25000}}""")
                breadcrumbs.add("""{"type":"navigation","category":"navigation","message":"ProductListFragment -> ProductDetailFragment","level":"info","timestamp":${now - 20000}}""")
                breadcrumbs.add("""{"type":"http","category":"http","message":"GET /api/products/123","level":"info","data":{"status_code":200,"method":"GET"},"timestamp":${now - 18000}}""")
                breadcrumbs.add("""{"type":"user","category":"ui.click","message":"User tapped add to cart button","level":"info","timestamp":${now - 12000}}""")
                breadcrumbs.add("""{"type":"http","category":"http","message":"POST /api/cart/items","level":"info","data":{"status_code":200,"method":"POST"},"timestamp":${now - 10000}}""")
                breadcrumbs.add("""{"type":"navigation","category":"navigation","message":"ProductDetailFragment -> CartFragment","level":"info","timestamp":${now - 5000}}""")
                breadcrumbs.add("""{"type":"debug","category":"lifecycle","message":"CartFragment.onViewCreated called","level":"debug","timestamp":${now - 3000}}""")
            }
            "cocoa" -> {
                // iOS-specific breadcrumbs
                breadcrumbs.add("""{"type":"navigation","category":"navigation","message":"HomeViewController -> ProductListViewController","level":"info","timestamp":${now - 28000}}""")
                breadcrumbs.add("""{"type":"user","category":"touch","message":"User tapped product cell","level":"info","timestamp":${now - 22000}}""")
                breadcrumbs.add("""{"type":"http","category":"network","message":"GET /api/products/456","level":"info","data":{"status_code":200},"timestamp":${now - 20000}}""")
                breadcrumbs.add("""{"type":"navigation","category":"navigation","message":"ProductListViewController -> ProductDetailViewController","level":"info","timestamp":${now - 15000}}""")
                breadcrumbs.add("""{"type":"user","category":"touch","message":"User tapped Add to Cart","level":"info","timestamp":${now - 8000}}""")
                breadcrumbs.add("""{"type":"debug","category":"app.lifecycle","message":"viewWillAppear called","level":"debug","timestamp":${now - 2000}}""")
            }
            else -> {
                // React Native / JS breadcrumbs
                breadcrumbs.add("""{"type":"navigation","category":"navigation","message":"Navigate to /products","level":"info","timestamp":${now - 25000}}""")
                breadcrumbs.add("""{"type":"http","category":"fetch","message":"GET /api/products","level":"info","data":{"status":200},"timestamp":${now - 23000}}""")
                breadcrumbs.add("""{"type":"user","category":"ui.click","message":"Click on product item","level":"info","timestamp":${now - 18000}}""")
                breadcrumbs.add("""{"type":"navigation","category":"navigation","message":"Navigate to /products/789","level":"info","timestamp":${now - 15000}}""")
                breadcrumbs.add("""{"type":"console","category":"console","message":"Product data loaded successfully","level":"log","timestamp":${now - 12000}}""")
                breadcrumbs.add("""{"type":"user","category":"ui.click","message":"Click add to cart","level":"info","timestamp":${now - 6000}}""")
            }
        }
        
        return "[${breadcrumbs.joinToString(",")}]"
    }
    
    private fun generateContexts(platform: String, device: String, osVersion: String): String {
        val contexts = when (platform) {
            "android" -> """
{
  "device": {
    "family": "Android",
    "model": "$device",
    "model_id": "${device.replace(" ", "_").lowercase()}",
    "arch": "arm64-v8a",
    "battery_level": ${70 + random.nextInt(30)}.0,
    "orientation": "portrait",
    "manufacturer": "${device.split(" ").firstOrNull() ?: "Unknown"}",
    "brand": "${device.split(" ").firstOrNull() ?: "Unknown"}",
    "screen_resolution": "1080x2400",
    "screen_density": 3.0,
    "online": true,
    "charging": ${random.nextBoolean()},
    "low_memory": false,
    "simulator": false,
    "memory_size": ${4 + random.nextInt(12)}000000000,
    "free_memory": ${2 + random.nextInt(4)}000000000,
    "storage_size": ${64 + random.nextInt(192)}000000000,
    "free_storage": ${32 + random.nextInt(96)}000000000,
    "boot_time": "2024-01-${10 + random.nextInt(20)}T08:${random.nextInt(60)}:${random.nextInt(60)}.000Z"
  },
  "os": {
    "name": "Android",
    "version": "$osVersion",
    "build": "API ${osVersion.substringAfter("Android ").trim()}",
    "kernel_version": "5.${random.nextInt(10)}.${random.nextInt(100)}"
  },
  "app": {
    "app_start_time": "2024-01-${15 + random.nextInt(15)}T${random.nextInt(24)}:${random.nextInt(60)}:${random.nextInt(60)}.000Z",
    "app_name": "Acme Shopping",
    "app_version": "1.${random.nextInt(4)}.${random.nextInt(3)}",
    "app_build": "${1000 + random.nextInt(200)}",
    "app_identifier": "com.acme.shopping",
    "app_memory": ${150 + random.nextInt(100)}000000
  },
  "culture": {
    "locale": "en_US",
    "timezone": "America/New_York"
  }
}
            """.trimIndent()
            
            "cocoa" -> """
{
  "device": {
    "family": "iOS",
    "model": "$device",
    "model_id": "${device.replace(" ", "").replace("\"", "")}",
    "arch": "arm64",
    "battery_level": ${65 + random.nextInt(35)}.0,
    "orientation": "portrait",
    "manufacturer": "Apple",
    "brand": "Apple",
    "screen_resolution": "${if (device.contains("iPad")) "1668x2388" else "1170x2532"}",
    "online": true,
    "charging": ${random.nextBoolean()},
    "low_memory": false,
    "simulator": false,
    "memory_size": ${6 + random.nextInt(10)}000000000,
    "free_memory": ${3 + random.nextInt(3)}000000000,
    "storage_size": ${128 + random.nextInt(384)}000000000,
    "free_storage": ${64 + random.nextInt(128)}000000000
  },
  "os": {
    "name": "iOS",
    "version": "$osVersion",
    "build": "${random.nextInt(20)}A${random.nextInt(1000)}",
    "kernel_version": "Darwin ${random.nextInt(23)}.${random.nextInt(6)}.0"
  },
  "app": {
    "app_start_time": "2024-01-${15 + random.nextInt(15)}T${random.nextInt(24)}:${random.nextInt(60)}:${random.nextInt(60)}.000Z",
    "app_name": "Acme Shopping",
    "app_version": "1.${random.nextInt(4)}.${random.nextInt(3)}",
    "app_build": "${1000 + random.nextInt(200)}",
    "app_identifier": "com.acme.shopping.ios",
    "app_memory": ${120 + random.nextInt(80)}000000
  },
  "culture": {
    "locale": "en_US",
    "timezone": "America/Los_Angeles"
  }
}
            """.trimIndent()
            
            else -> """
{
  "browser": {
    "name": "Chrome",
    "version": "120.0.${random.nextInt(6000)}.${random.nextInt(200)}"
  },
  "os": {
    "name": "Windows",
    "version": "10.0.${random.nextInt(20000)}"
  },
  "runtime": {
    "name": "javascript",
    "version": "ES2021"
  },
  "app": {
    "app_name": "Acme Shopping Web",
    "app_version": "1.${random.nextInt(4)}.${random.nextInt(3)}"
  },
  "culture": {
    "locale": "en-US",
    "timezone": "America/Chicago"
  }
}
            """.trimIndent()
        }
        
        return contexts
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
