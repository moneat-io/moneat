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

private val logger = KotlinLogging.logger {}

/** Platform value for React Native demo issues (Sonar: avoid duplicating literal). */
private const val DEMO_PLATFORM_REACT_NATIVE = "react-native"

// Demo seed DSL: one property per INSERT column; Sonar S107 suppressed — nesting would churn 70+ call sites.
private data class DemoIssueInsertSpec
@Suppress("kotlin:S107")
constructor(
    val project: String,
    val issueId: String,
    val platform: String,
    val level: String,
    val message: String,
    val exType: String,
    val exValue: String,
    val stack: String,
    val release: String,
    val userBase: Int,
    val userMod: Int,
    val events: Int,
    val hours: Int,
    val devices: String,
    val osName: String,
    val osVersions: String,
)

private fun demoIssueInsertSql(envExpr: String, spec: DemoIssueInsertSpec): String =
    with(spec) {
        """
        INSERT INTO events (
            event_id, project_id, issue_id, timestamp, received_at, event_type,
            platform, level, message, exception_type, exception_value,
            stack_trace, environment, release, user_id, user_email,
            device_model, os_name, os_version, fingerprint
        )
        SELECT generateUUIDv4(), $project, '$issueId',
            now() - INTERVAL (number % $hours) HOUR, now() - INTERVAL (number % $hours) HOUR,
            'error', '$platform', '$level',
            '$message', '$exType', '$exValue', '$stack',
            $envExpr, '$release',
            toString($userBase + (number % $userMod)),
            concat('user', toString(number % $userMod), '@acmemobile.com'),
            $devices, '$osName', $osVersions,
            ['$exType']
        FROM numbers($events)
        """.trimIndent()
    }

internal suspend fun checkFreshDataCount(): Long {
    val query =
        """
        SELECT count() as cnt
        FROM events
        WHERE project_id IN ($P1, $P2, $P3)
            AND timestamp >= now() - INTERVAL 7 DAY
        """.trimIndent()
    return suspendRunCatching {
        val response = ClickHouseClient.execute(query)
        val body = response.bodyAsText()
        if (response.status.value !in 200..299) return@suspendRunCatching 0
        body.trim().toLongOrNull() ?: 0
    }.getOrElse {
        logger.warn { "Failed to check fresh core demo data (non-fatal): ${it.message}" }
        0
    }
}
internal suspend fun purgeOldDemoData() {
    val tables =
        listOf(
            "events" to "project_id",
            "sessions" to "project_id",
            "spans" to "project_id",
            "replay_events" to "project_id",
            "replay_segments" to "project_id"
        )
    for ((table, col) in tables) {
        val query = "ALTER TABLE $table DELETE WHERE $col IN ($P1, $P2, $P3)"
        suspendRunCatching { ClickHouseClient.execute(query) }
            .onFailure { logger.warn { "Purge $table failed (non-fatal): ${it.message}" } }
    }
    // Also purge issues materialized from demo events
    suspendRunCatching {
        ClickHouseClient.execute("ALTER TABLE issues DELETE WHERE project_id IN ($P1, $P2, $P3)")
    }.onFailure { logger.warn { "Purge issues failed (non-fatal): ${it.message}" } }
}
internal suspend fun reseedEvents() {
    val androidDevices =
        "arrayElement(['Samsung Galaxy S23', 'Google Pixel 8', 'OnePlus 11', 'Xiaomi 13 Pro'], " +
            "number % 4 + 1)"
    val androidVersions = "arrayElement(['14', '13', '12', '11'], number % 4 + 1)"
    val iosDevices = "arrayElement(['iPhone 15 Pro', 'iPhone 14', 'iPhone 13', 'iPad Air 5'], number % 4 + 1)"
    val iosVersions = "arrayElement(['17.2', '17.0', '16.6', '16.0'], number % 4 + 1)"
    val rnDevices =
        "arrayElement(['Samsung Galaxy S23', 'iPhone 15 Pro', 'Google Pixel 8', 'iPhone 14'], " +
            "number % 4 + 1)"
    val rnVersions = "arrayElement(['14', '17.2', '13', '17.0'], number % 4 + 1)"
    val envExpr = "if(number % 8 = 0, 'staging', 'production')"

    // Helper: build a single-issue error event INSERT
    fun issueInsert(spec: DemoIssueInsertSpec): String = demoIssueInsertSql(envExpr, spec)

    val androidRelease = "arrayElement(['1.3.0', '1.2.1', '1.2.0'], number % 3 + 1)"
    val iosRelease = "arrayElement(['2.1.0', '2.0.1', '2.0.0'], number % 3 + 1)"
    val rnRelease = "arrayElement(['3.0.1', '3.0.0', '2.9.0'], number % 3 + 1)"

    val statements = listOf(
        // Android issues (project -1)
        issueInsert(
            DemoIssueInsertSpec(
                project = P1,
                issueId = "demo-android-1",
                platform = "android",
                level = "fatal",
                message = "Attempt to invoke virtual method on a null object reference",
                exType = "java.lang.NullPointerException",
                exValue = "Attempt to invoke virtual method 'void " +
                    "android.widget.ImageView.setImageBitmap(android.graphics.Bitmap)' on a null object reference",
                stack = "at com.acme.shopping.ui.ProductDetailFragment.updateUI(ProductDetailFragment.kt:87)\\n" +
                    "at com.acme.shopping.ui.ProductDetailFragment.onViewCreated(ProductDetailFragment.kt:52)",
                release = androidRelease,
                userBase = 1000,
                userMod = 89,
                events = 150,
                hours = 168,
                devices = androidDevices,
                osName = "Android",
                osVersions = androidVersions,
            ),
        ),

        issueInsert(
            DemoIssueInsertSpec(
                project = P1,
                issueId = "demo-android-2",
                platform = "android",
                level = "error",
                message = "android.os.NetworkOnMainThreadException",
                exType = "android.os.NetworkOnMainThreadException",
                exValue = "Main thread network call in CheckoutRepository",
                stack = "at android.os.StrictMode\$AndroidBlockGuardPolicy.onNetwork(StrictMode.java:1605)\\n" +
                    "at com.acme.shopping.checkout.CheckoutRepository.validateCart(CheckoutRepository.kt:134)",
                release = androidRelease,
                userBase = 1100,
                userMod = 70,
                events = 80,
                hours = 120,
                devices = androidDevices,
                osName = "Android",
                osVersions = androidVersions,
            ),
        ),

        issueInsert(
            DemoIssueInsertSpec(
                project = P1,
                issueId = "demo-android-3",
                platform = "android",
                level = "fatal",
                message = "java.lang.OutOfMemoryError: Failed to allocate a 48 MB allocation",
                exType = "java.lang.OutOfMemoryError",
                exValue = "Failed to allocate a 48 MB allocation with 12 MB free bytes and 32 MB until OOM",
                stack = "at com.acme.shopping.image.ImageLoader.loadFullResImage(ImageLoader.kt:212)\\n" +
                    "at com.acme.shopping.ui.ProductGalleryFragment.onResume(ProductGalleryFragment.kt:78)",
                release = androidRelease,
                userBase = 1200,
                userMod = 35,
                events = 40,
                hours = 96,
                devices = androidDevices,
                osName = "Android",
                osVersions = androidVersions,
            ),
        ),

        issueInsert(
            DemoIssueInsertSpec(
                project = P1,
                issueId = "demo-android-4",
                platform = "android",
                level = "error",
                message = "java.lang.IllegalStateException: Fragment ProductDetailFragment not attached to a context",
                exType = "java.lang.IllegalStateException",
                exValue = "Fragment ProductDetailFragment{1a2b3c4} (2b34c5d6) not attached to a context.",
                stack = "at androidx.fragment.app.Fragment.requireContext(Fragment.java:951)\\n" +
                    "at com.acme.shopping.ui.ProductDetailFragment.showAddedToCartToast(ProductDetailFragment.kt:203)",
                release = androidRelease,
                userBase = 1300,
                userMod = 45,
                events = 50,
                hours = 144,
                devices = androidDevices,
                osName = "Android",
                osVersions = androidVersions,
            ),
        ),

        issueInsert(
            DemoIssueInsertSpec(
                project = P1,
                issueId = "demo-android-5",
                platform = "android",
                level = "error",
                message = "java.util.ConcurrentModificationException in CartManager",
                exType = "java.util.ConcurrentModificationException",
                exValue = "Concurrent modification of cart items list during checkout",
                stack = "at java.util.ArrayList\$Itr.next(ArrayList.java:860)\\n" +
                    "at com.acme.shopping.cart.CartManager.calculateTotal(CartManager.kt:156)",
                release = androidRelease,
                userBase = 1400,
                userMod = 28,
                events = 30,
                hours = 72,
                devices = androidDevices,
                osName = "Android",
                osVersions = androidVersions,
            ),
        ),

        issueInsert(
            DemoIssueInsertSpec(
                project = P1,
                issueId = "demo-android-6",
                platform = "android",
                level = "error",
                message = "java.lang.ArrayIndexOutOfBoundsException: length=12; index=12",
                exType = "java.lang.ArrayIndexOutOfBoundsException",
                exValue = "Array index 12 out of bounds for length 12 in product list adapter",
                stack = "at com.acme.shopping.ui.adapters.ProductListAdapter.onBindViewHolder(ProductListAdapter.kt:89)\\n" +
                    "at androidx.recyclerview.widget.RecyclerView\$Recycler" +
                    ".tryGetViewHolderForPositionByDeadline(RecyclerView.java:6235)",
                release = androidRelease,
                userBase = 1500,
                userMod = 20,
                events = 20,
                hours = 48,
                devices = androidDevices,
                osName = "Android",
                osVersions = androidVersions,
            ),
        ),

        issueInsert(
            DemoIssueInsertSpec(
                project = P1,
                issueId = "demo-android-7",
                platform = "android",
                level = "fatal",
                message = "java.lang.StackOverflowError in CategoryTreeRenderer",
                exType = "java.lang.StackOverflowError",
                exValue = "Infinite recursive call while rendering nested category tree",
                stack = "at com.acme.shopping.ui.CategoryTreeRenderer.renderNode(CategoryTreeRenderer.kt:67)\\n" +
                    "at com.acme.shopping.ui.CategoryTreeRenderer.renderNode(CategoryTreeRenderer.kt:81)",
                release = androidRelease,
                userBase = 1600,
                userMod = 12,
                events = 13,
                hours = 96,
                devices = androidDevices,
                osName = "Android",
                osVersions = androidVersions,
            ),
        ),

        issueInsert(
            DemoIssueInsertSpec(
                project = P1,
                issueId = "demo-android-8",
                platform = "android",
                level = "warning",
                message = "java.lang.IllegalArgumentException: Unknown URL scheme: acme://checkout",
                exType = "java.lang.IllegalArgumentException",
                exValue = "Unknown URL scheme passed to NetworkClient deep link handler",
                stack = "at com.acme.shopping.network.NetworkClient.buildUrl(NetworkClient.kt:45)\\n" +
                    "at com.acme.shopping.deeplink.DeepLinkHandler.handle(DeepLinkHandler.kt:112)",
                release = androidRelease,
                userBase = 1700,
                userMod = 17,
                events = 17,
                hours = 72,
                devices = androidDevices,
                osName = "Android",
                osVersions = androidVersions,
            ),
        ),

        issueInsert(
            DemoIssueInsertSpec(
                project = P1,
                issueId = "demo-android-9",
                platform = "android",
                level = "error",
                message = "java.lang.SecurityException: Permission Denial: requires android.permission.ACCESS_FINE_LOCATION",
                exType = "java.lang.SecurityException",
                exValue = "ACCESS_FINE_LOCATION permission not granted before requesting location",
                stack = "at android.os.Parcel.createExceptionOrNull(Parcel.java:2374)\\n" +
                    "at com.acme.shopping.store.StoreLocatorService.getCurrentLocation(StoreLocatorService.kt:88)",
                release = androidRelease,
                userBase = 1800,
                userMod = 12,
                events = 12,
                hours = 48,
                devices = androidDevices,
                osName = "Android",
                osVersions = androidVersions,
            ),
        ),

        issueInsert(
            DemoIssueInsertSpec(
                project = P1,
                issueId = "demo-android-10",
                platform = "android",
                level = "error",
                message = "android.database.sqlite.SQLiteException: UNIQUE constraint failed: orders.order_ref",
                exType = "android.database.sqlite.SQLiteException",
                exValue = "UNIQUE constraint failed: orders.order_ref (code 2067 SQLITE_CONSTRAINT_UNIQUE)",
                stack = "at android.database.sqlite.SQLiteConnection.nativeExecuteForLastInsertedRowId(Native Method)\\n" +
                    "at com.acme.shopping.db.OrderDao.insertOrder(OrderDao.kt:34)",
                release = androidRelease,
                userBase = 1900,
                userMod = 13,
                events = 13,
                hours = 96,
                devices = androidDevices,
                osName = "Android",
                osVersions = androidVersions,
            ),
        ),

        issueInsert(
            DemoIssueInsertSpec(
                project = P1,
                issueId = "demo-android-11",
                platform = "android",
                level = "error",
                message = "org.json.JSONException: Value null at response of type org.json.JSONObject\$1 cannot be converted " +
                    "to JSONObject",
                exType = "org.json.JSONException",
                exValue = "Null response body from product API could not be parsed",
                stack = "at org.json.JSON.typeMismatch(JSON.java:111)\\n" +
                    "at com.acme.shopping.network.ProductApiParser.parse(ProductApiParser.kt:67)",
                release = androidRelease,
                userBase = 2000,
                userMod = 10,
                events = 10,
                hours = 72,
                devices = androidDevices,
                osName = "Android",
                osVersions = androidVersions,
            ),
        ),

        issueInsert(
            DemoIssueInsertSpec(
                project = P1,
                issueId = "demo-android-12",
                platform = "android",
                level = "error",
                message = "java.net.ConnectException: Failed to connect to api.acmemobile.com/93.184.216.34:443",
                exType = "java.net.ConnectException",
                exValue = "Connection to checkout API timed out after 30 seconds",
                stack = "at com.android.okhttp.internal.io.RealConnection.connectSocket(RealConnection.java:187)\\n" +
                    "at com.acme.shopping.network.ApiService\$CheckoutService.placeOrder(ApiService.kt:289)",
                release = androidRelease,
                userBase = 2100,
                userMod = 12,
                events = 12,
                hours = 48,
                devices = androidDevices,
                osName = "Android",
                osVersions = androidVersions,
            ),
        ),

        issueInsert(
            DemoIssueInsertSpec(
                project = P1,
                issueId = "demo-android-13",
                platform = "android",
                level = "error",
                message = "javax.net.ssl.SSLHandshakeException: Certificate expired for api.acmemobile.com",
                exType = "javax.net.ssl.SSLHandshakeException",
                exValue = "SSL certificate for api.acmemobile.com expired on 2024-01-15",
                stack = "at com.android.org.conscrypt.OpenSSLSocketImpl.startHandshake(OpenSSLSocketImpl.java:361)\\n" +
                    "at com.acme.shopping.network.SecureApiClient.connect(SecureApiClient.kt:78)",
                release = androidRelease,
                userBase = 2200,
                userMod = 10,
                events = 10,
                hours = 24,
                devices = androidDevices,
                osName = "Android",
                osVersions = androidVersions,
            ),
        ),

        issueInsert(
            DemoIssueInsertSpec(
                project = P1,
                issueId = "demo-android-14",
                platform = "android",
                level = "error",
                message = "android.content.ActivityNotFoundException: No Activity found to handle Intent { " +
                    "act=com.acme.payment.CHECKOUT }",
                exType = "android.content.ActivityNotFoundException",
                exValue = "Payment activity not found - payment module may not be installed",
                stack = "at android.app.Instrumentation.checkStartActivityResult(Instrumentation.java:2085)\\n" +
                    "at com.acme.shopping.checkout.PaymentLauncher.launch(PaymentLauncher.kt:56)",
                release = androidRelease,
                userBase = 2300,
                userMod = 7,
                events = 7,
                hours = 96,
                devices = androidDevices,
                osName = "Android",
                osVersions = androidVersions,
            ),
        ),

        issueInsert(
            DemoIssueInsertSpec(
                project = P1,
                issueId = "demo-android-15",
                platform = "android",
                level = "error",
                message = "java.lang.ClassCastException: com.acme.shopping.model.SaleItem cannot be cast to " +
                    "com.acme.shopping.model.ProductItem",
                exType = "java.lang.ClassCastException",
                exValue = "Type mismatch in search results adapter — sale items mixed with regular products",
                stack = "at com.acme.shopping.ui.adapters.SearchResultAdapter.onBindViewHolder(SearchResultAdapter.kt:112)\\n" +
                    "at androidx.recyclerview.widget.RecyclerView.onScrolled(RecyclerView.java:1841)",
                release = androidRelease,
                userBase = 2400,
                userMod = 7,
                events = 7,
                hours = 72,
                devices = androidDevices,
                osName = "Android",
                osVersions = androidVersions,
            ),
        ),

        issueInsert(
            DemoIssueInsertSpec(
                project = P1,
                issueId = "demo-android-16",
                platform = "android",
                level = "warning",
                message = "java.lang.NumberFormatException: For input string: '12.99 USD'",
                exType = "java.lang.NumberFormatException",
                exValue = "Price string contains currency symbol, cannot parse as Double",
                stack = "at java.lang.FloatingDecimal.readJavaFormatString(FloatingDecimal.java:2043)\\n" +
                    "at com.acme.shopping.ui.PriceFormatter.parse(PriceFormatter.kt:29)",
                release = androidRelease,
                userBase = 2500,
                userMod = 6,
                events = 6,
                hours = 48,
                devices = androidDevices,
                osName = "Android",
                osVersions = androidVersions,
            ),
        ),

        issueInsert(
            DemoIssueInsertSpec(
                project = P1,
                issueId = "demo-android-17",
                platform = "android",
                level = "error",
                message = "java.io.FileNotFoundException: /data/user/0/com.acme.shopping/cache/profile_img_1234.jpg (No such " +
                    "file or directory)",
                exType = "java.io.FileNotFoundException",
                exValue = "Cached profile image file deleted by system while still referenced",
                stack = "at java.io.FileInputStream.open0(Native Method)\\n" +
                    "at com.acme.shopping.profile.ProfileImageManager.loadCachedImage(ProfileImageManager.kt:88)",
                release = androidRelease,
                userBase = 2600,
                userMod = 5,
                events = 5,
                hours = 120,
                devices = androidDevices,
                osName = "Android",
                osVersions = androidVersions,
            ),
        ),

        issueInsert(
            DemoIssueInsertSpec(
                project = P1,
                issueId = "demo-android-18",
                platform = "android",
                level = "error",
                message = "java.lang.UnsupportedOperationException: Payment method BNPL not supported in region",
                exType = "java.lang.UnsupportedOperationException",
                exValue = "Buy-now-pay-later payment method unavailable for selected shipping region",
                stack = "at com.acme.shopping.payment.PaymentProcessor.process(PaymentProcessor.kt:234)\\n" +
                    "at com.acme.shopping.checkout.CheckoutViewModel.confirmOrder(CheckoutViewModel.kt:178)",
                release = androidRelease,
                userBase = 2700,
                userMod = 4,
                events = 4,
                hours = 96,
                devices = androidDevices,
                osName = "Android",
                osVersions = androidVersions,
            ),
        ),

        issueInsert(
            DemoIssueInsertSpec(
                project = P1,
                issueId = "demo-android-19",
                platform = "android",
                level = "error",
                message = "android.os.DeadObjectException in PushNotificationService",
                exType = "android.os.DeadObjectException",
                exValue = "Binder connection to push notification service died unexpectedly",
                stack = "at android.os.BinderProxy.transactNative(Native Method)\\n" +
                    "at com.acme.shopping.notifications.PushNotificationService.sendToken(" +
                    "PushNotificationService.kt:67)",
                release = androidRelease,
                userBase = 2800,
                userMod = 4,
                events = 4,
                hours = 72,
                devices = androidDevices,
                osName = "Android",
                osVersions = androidVersions,
            ),
        ),

        issueInsert(
            DemoIssueInsertSpec(
                project = P1,
                issueId = "demo-android-20",
                platform = "android",
                level = "error",
                message = "android.os.RemoteException in PaymentService",
                exType = "android.os.RemoteException",
                exValue = "Remote payment service disconnected during transaction",
                stack = "at com.acme.shopping.payment.PaymentServiceConnection" +
                    ".onServiceDisconnected(PaymentServiceConnection.kt:45)\\n" +
                    "at com.acme.shopping.checkout.CheckoutActivity.finalizePayment(CheckoutActivity.kt:312)",
                release = androidRelease,
                userBase = 2900,
                userMod = 4,
                events = 4,
                hours = 48,
                devices = androidDevices,
                osName = "Android",
                osVersions = androidVersions,
            ),
        ),

        issueInsert(
            DemoIssueInsertSpec(
                project = P1,
                issueId = "demo-android-21",
                platform = "android",
                level = "error",
                message = "com.google.firebase.firestore.FirebaseFirestoreException: PERMISSION_DENIED: Missing or " +
                    "insufficient permissions",
                exType = "com.google.firebase.firestore.FirebaseFirestoreException",
                exValue = "Firestore security rules blocking wishlist read for unauthenticated user",
                stack = "at com.google.firebase.firestore.FirebaseFirestore.collection(FirebaseFirestore.java:234)\\n" +
                    "at com.acme.shopping.wishlist.WishlistRepository.fetchWishlist(WishlistRepository.kt:56)",
                release = androidRelease,
                userBase = 3000,
                userMod = 3,
                events = 3,
                hours = 96,
                devices = androidDevices,
                osName = "Android",
                osVersions = androidVersions,
            ),
        ),

        issueInsert(
            DemoIssueInsertSpec(
                project = P1,
                issueId = "demo-android-22",
                platform = "android",
                level = "warning",
                message = "java.text.ParseException: Unparseable date: 2024-13-45T00:00:00Z",
                exType = "java.text.ParseException",
                exValue = "Invalid ISO 8601 date from order history API response",
                stack = "at java.text.SimpleDateFormat.parse(SimpleDateFormat.java:1457)\\n" +
                    "at com.acme.shopping.orders.OrderHistoryParser.parseDate(OrderHistoryParser.kt:78)",
                release = androidRelease,
                userBase = 3100,
                userMod = 2,
                events = 2,
                hours = 120,
                devices = androidDevices,
                osName = "Android",
                osVersions = androidVersions,
            ),
        ),

        issueInsert(
            DemoIssueInsertSpec(
                project = P1,
                issueId = "demo-android-23",
                platform = "android",
                level = "error",
                message = "java.lang.NegativeArraySizeException: -3 in FilterManager",
                exType = "java.lang.NegativeArraySizeException",
                exValue = "Negative size passed to array constructor when no filters selected",
                stack = "at com.acme.shopping.search.FilterManager.buildFilterArray(FilterManager.kt:112)\\n" +
                    "at com.acme.shopping.ui.SearchFragment.applyFilters(SearchFragment.kt:234)",
                release = androidRelease,
                userBase = 3200,
                userMod = 2,
                events = 2,
                hours = 72,
                devices = androidDevices,
                osName = "Android",
                osVersions = androidVersions,
            ),
        ),

        issueInsert(
            DemoIssueInsertSpec(
                project = P1,
                issueId = "demo-android-24",
                platform = "android",
                level = "error",
                message = "androidx.work.WorkerInitializationException: Could not instantiate SyncWorker",
                exType = "androidx.work.WorkerInitializationException",
                exValue = "WorkManager SyncWorker failed to initialize — missing dependency injection",
                stack = "at androidx.work.WorkerFactory.createWorkerWithDefaultFallback(WorkerFactory.java:98)\\n" +
                    "at com.acme.shopping.sync.SyncWorker.<init>(SyncWorker.kt:24)",
                release = androidRelease,
                userBase = 3300,
                userMod = 2,
                events = 2,
                hours = 96,
                devices = androidDevices,
                osName = "Android",
                osVersions = androidVersions,
            ),
        ),

        issueInsert(
            DemoIssueInsertSpec(
                project = P1,
                issueId = "demo-android-25",
                platform = "android",
                level = "fatal",
                message = "android.database.sqlite.SQLiteDatabaseCorruptException: database disk image is malformed",
                exType = "android.database.sqlite.SQLiteDatabaseCorruptException",
                exValue = "Room database on-disk file corrupted — possible incomplete write during crash",
                stack = "at android.database.sqlite.SQLiteConnection.nativeExecuteForCursorWindow(Native Method)\\n" +
                    "at com.acme.shopping.db.AppDatabase\$\$_Impl.clearAllTables(AppDatabase.kt:45)",
                release = androidRelease,
                userBase = 3400,
                userMod = 1,
                events = 1,
                hours = 48,
                devices = androidDevices,
                osName = "Android",
                osVersions = androidVersions,
            ),
        ),

        // iOS issues (project -2)
        issueInsert(
            DemoIssueInsertSpec(
                project = P2,
                issueId = "demo-ios-1",
                platform = "ios",
                level = "error",
                message = "NSInvalidArgumentException: -[UIViewController presentViewController:animated:completion:] called " +
                    "on nil",
                exType = "NSInvalidArgumentException",
                exValue = "Attempt to present view controller from a deallocated UIViewController",
                stack = "at -[UIViewController presentViewController:animated:completion:] + 48\\n" +
                    "at -[AcmeProductDetailVC showCheckout] (ProductDetailViewController.m:312)",
                release = iosRelease,
                userBase = 2000,
                userMod = 75,
                events = 40,
                hours = 168,
                devices = iosDevices,
                osName = "iOS",
                osVersions = iosVersions,
            ),
        ),

        issueInsert(
            DemoIssueInsertSpec(
                project = P2,
                issueId = "demo-ios-2",
                platform = "ios",
                level = "fatal",
                message = "EXC_BAD_ACCESS (SIGSEGV) in ProductImageCache",
                exType = "EXC_BAD_ACCESS",
                exValue = "SIGSEGV KERN_INVALID_ADDRESS at 0x0000000000000018 — dangling pointer to deallocated image cache " +
                    "entry",
                stack = "at AcmeProductImageCache.imageForURL(_:) + 156 (ProductImageCache.swift:89)\\n" +
                    "at AcmeProductCell.configure(with:) + 304 (ProductCell.swift:67)",
                release = iosRelease,
                userBase = 2100,
                userMod = 60,
                events = 70,
                hours = 120,
                devices = iosDevices,
                osName = "iOS",
                osVersions = iosVersions,
            ),
        ),

        issueInsert(
            DemoIssueInsertSpec(
                project = P2,
                issueId = "demo-ios-3",
                platform = "ios",
                level = "fatal",
                message = "Fatal error: Index out of range in CartViewController",
                exType = "Swift.IndexOutOfRangeError",
                exValue = "Array index 5 is out of bounds for array with length 5 while removing cart item",
                stack = "at Swift._ArrayBuffer._checkValidSubscript(_:withSubscriptCheck:) + 220 " +
                    "(CartViewController.swift:178)\\n" +
                    "at AcmeCartViewController.removeItem(at:) + 88 (CartViewController.swift:178)",
                release = iosRelease,
                userBase = 2200,
                userMod = 45,
                events = 50,
                hours = 96,
                devices = iosDevices,
                osName = "iOS",
                osVersions = iosVersions,
            ),
        ),

        issueInsert(
            DemoIssueInsertSpec(
                project = P2,
                issueId = "demo-ios-4",
                platform = "ios",
                level = "error",
                message = "NSRangeException: -[__NSArrayM objectAtIndex:]: index 15 beyond bounds for empty array",
                exType = "NSRangeException",
                exValue = "Order history table view accessed index 15 on empty data source",
                stack = "at -[__NSArrayM objectAtIndex:] + 36\\n" +
                    "at -[AcmeOrderHistoryVC tableView:cellForRowAtIndexPath:] (OrderHistoryViewController.m:156)",
                release = iosRelease,
                userBase = 2300,
                userMod = 30,
                events = 35,
                hours = 144,
                devices = iosDevices,
                osName = "iOS",
                osVersions = iosVersions,
            ),
        ),

        issueInsert(
            DemoIssueInsertSpec(
                project = P2,
                issueId = "demo-ios-5",
                platform = "ios",
                level = "fatal",
                message = "Thread 1: EXC_BAD_INSTRUCTION (EXC_I386_INVOP, subcode=0x0) in CheckoutViewController",
                exType = "EXC_BAD_INSTRUCTION",
                exValue = "Force-unwrap of nil Optional in CheckoutViewController payment result handler",
                stack = "at AcmeCheckoutViewController.handlePaymentResult(_:) + 312 (CheckoutViewController.swift:234)\\n" +
                    "at AcmePaymentService.onComplete(_:) + 88 (PaymentService.swift:156)",
                release = iosRelease,
                userBase = 2400,
                userMod = 22,
                events = 25,
                hours = 72,
                devices = iosDevices,
                osName = "iOS",
                osVersions = iosVersions,
            ),
        ),

        issueInsert(
            DemoIssueInsertSpec(
                project = P2,
                issueId = "demo-ios-6",
                platform = "ios",
                level = "error",
                message = "NSURLErrorDomain -1009: The Internet connection appears to be offline",
                exType = "NSURLError",
                exValue = "Network request failed — device has no internet connectivity during checkout",
                stack = "at AcmeNetworkService.performRequest(_:completion:) + 278 (NetworkService.swift:112)\\n" +
                    "at AcmeCheckoutService.submitOrder(_:) + 156 (CheckoutService.swift:89)",
                release = iosRelease,
                userBase = 2500,
                userMod = 18,
                events = 20,
                hours = 48,
                devices = iosDevices,
                osName = "iOS",
                osVersions = iosVersions,
            ),
        ),

        issueInsert(
            DemoIssueInsertSpec(
                project = P2,
                issueId = "demo-ios-7",
                platform = "ios",
                level = "fatal",
                message = "Thread 1: signal SIGABRT — assertion failure in UITableView",
                exType = "SIGABRT",
                exValue = "Invalid update: invalid number of sections 0 (before), 1 (after) in UITableView",
                stack = "at AcmeProductListVC.reloadData() + 534 (ProductListViewController.swift:445)\\n" +
                    "at -[UIApplication _handleApplicationActivationWithScene:...]",
                release = iosRelease,
                userBase = 2600,
                userMod = 13,
                events = 15,
                hours = 96,
                devices = iosDevices,
                osName = "iOS",
                osVersions = iosVersions,
            ),
        ),

        issueInsert(
            DemoIssueInsertSpec(
                project = P2,
                issueId = "demo-ios-8",
                platform = "ios",
                level = "error",
                message = "NSInternalInconsistencyException: Invalid update to UITableView section 0",
                exType = "NSInternalInconsistencyException",
                exValue = "Attempted to delete rows while another animation was in progress — race condition in search results",
                stack = "at -[UITableView _endCellAnimationsWithContext:] + 8234\\n" +
                    "at AcmeSearchResultsVC.updateResults(_:) + 312 (SearchResultsViewController.swift:289)",
                release = iosRelease,
                userBase = 2700,
                userMod = 18,
                events = 20,
                hours = 72,
                devices = iosDevices,
                osName = "iOS",
                osVersions = iosVersions,
            ),
        ),

        issueInsert(
            DemoIssueInsertSpec(
                project = P2,
                issueId = "demo-ios-9",
                platform = "ios",
                level = "error",
                message = "Swift.DecodingError.keyNotFound(CodingKeys.productId): No value associated with key productId",
                exType = "Swift.DecodingError",
                exValue = "product_id key missing in product API response — backend returned camelCase vs snake_case mismatch",
                stack = "at AcmeProductParser.decode(_:) + 234 (ProductParser.swift:67)\\n" +
                    "at AcmeProductService.fetchProducts(completion:) + 388 (ProductService.swift:134)",
                release = iosRelease,
                userBase = 2800,
                userMod = 16,
                events = 18,
                hours = 120,
                devices = iosDevices,
                osName = "iOS",
                osVersions = iosVersions,
            ),
        ),

        issueInsert(
            DemoIssueInsertSpec(
                project = P2,
                issueId = "demo-ios-10",
                platform = "ios",
                level = "error",
                message = "URLError -1001: The request timed out after 30 seconds",
                exType = "URLError",
                exValue = "Checkout API request timed out — server overloaded during flash sale",
                stack = "at AcmeAPIClient.dataTask(with:completionHandler:) + 156 (APIClient.swift:89)\\n" +
                    "at AcmeOrderService.placeOrder(_:completion:) + 488 (OrderService.swift:278)",
                release = iosRelease,
                userBase = 2900,
                userMod = 13,
                events = 15,
                hours = 48,
                devices = iosDevices,
                osName = "iOS",
                osVersions = iosVersions,
            ),
        ),

        issueInsert(
            DemoIssueInsertSpec(
                project = P2,
                issueId = "demo-ios-11",
                platform = "ios",
                level = "error",
                message = "NSCocoaErrorDomain 257: The file photo_library could not be opened because you don't have " +
                    "permission to view it",
                exType = "NSFileReadNoPermissionError",
                exValue = "Photo library access requested without NSPhotoLibraryUsageDescription in Info.plist",
                stack = "at AcmeProfileImagePicker.requestPhotoAccess() + 78 (ProfileImagePicker.swift:45)\\n" +
                    "at AcmeProfileVC.didTapProfilePhoto(_:) + 234 (ProfileViewController.swift:156)",
                release = iosRelease,
                userBase = 3000,
                userMod = 11,
                events = 12,
                hours = 96,
                devices = iosDevices,
                osName = "iOS",
                osVersions = iosVersions,
            ),
        ),

        issueInsert(
            DemoIssueInsertSpec(
                project = P2,
                issueId = "demo-ios-12",
                platform = "ios",
                level = "error",
                message = "NSCocoaErrorDomain 4864: The model used to open the store is incompatible with the one used to " +
                    "create the store",
                exType = "NSCocoaErrorDomain",
                exValue = "CoreData model version mismatch after app update — migration required from V3 to V4",
                stack = "at NSPersistentStoreCoordinator.addPersistentStore(ofType:configurationName:at:options:) + 312\\n" +
                    "at AcmeDataStack.loadStores() + 156 (DataStack.swift:78)",
                release = iosRelease,
                userBase = 3100,
                userMod = 10,
                events = 10,
                hours = 72,
                devices = iosDevices,
                osName = "iOS",
                osVersions = iosVersions,
            ),
        ),

        issueInsert(
            DemoIssueInsertSpec(
                project = P2,
                issueId = "demo-ios-13",
                platform = "ios",
                level = "error",
                message = "NSJSONSerialization error 3840: JSON text did not start with array or object and option to allow " +
                    "fragments not set",
                exType = "NSJSONSerialization",
                exValue = "Empty HTTP 200 response body from product search API cannot be parsed as JSON",
                stack = "at AcmeSearchResponseParser.parse(_:) + 112 (SearchResponseParser.swift:34)\\n" +
                    "at AcmeSearchService.search(query:completion:) + 234 (SearchService.swift:89)",
                release = iosRelease,
                userBase = 3200,
                userMod = 10,
                events = 10,
                hours = 120,
                devices = iosDevices,
                osName = "iOS",
                osVersions = iosVersions,
            ),
        ),

        issueInsert(
            DemoIssueInsertSpec(
                project = P2,
                issueId = "demo-ios-14",
                platform = "ios",
                level = "error",
                message = "SKErrorDomain 0: Cannot connect to iTunes Store",
                exType = "SKError",
                exValue = "StoreKit purchase failed — user cannot make in-app purchases (parental controls)",
                stack = "at AcmePremiumSubscriptionService.purchase(_:) + 189 (PremiumSubscriptionService.swift:112)\\n" +
                    "at AcmeSubscriptionVC.didTapSubscribe(_:) + 234 (SubscriptionViewController.swift:78)",
                release = iosRelease,
                userBase = 3300,
                userMod = 8,
                events = 8,
                hours = 96,
                devices = iosDevices,
                osName = "iOS",
                osVersions = iosVersions,
            ),
        ),

        issueInsert(
            DemoIssueInsertSpec(
                project = P2,
                issueId = "demo-ios-15",
                platform = "ios",
                level = "error",
                message = "WKNavigationDelegate webView(_:didFailProvisionalNavigation:withError:): A server with the " +
                    "specified hostname could not be found",
                exType = "WKNavigationError",
                exValue = "WebView failed to load order tracking page — DNS lookup failure for tracking subdomain",
                stack = "at AcmeOrderTrackingWebVC.webView(_:didFailProvisionalNavigation:withError:) + 156 " +
                    "(OrderTrackingWebViewController.swift:67)\\n" +
                    "at WebKit.WKWebView.performLoad(_:)",
                release = iosRelease,
                userBase = 3400,
                userMod = 8,
                events = 8,
                hours = 72,
                devices = iosDevices,
                osName = "iOS",
                osVersions = iosVersions,
            ),
        ),

        issueInsert(
            DemoIssueInsertSpec(
                project = P2,
                issueId = "demo-ios-16",
                platform = "ios",
                level = "error",
                message = "NSUnknownKeyException: setValue:forUndefinedKey: 'discountedPrice' this class is not key value " +
                    "coding-compliant",
                exType = "NSUnknownKeyException",
                exValue = "KVC access to discountedPrice property not found in ProductModel after model refactor",
                stack = "at NSObject.setValue(_:forKey:) + 56\\n" +
                    "at AcmeProductListVC.configureCell(_:with:) + 178 (ProductListViewController.swift:345)",
                release = iosRelease,
                userBase = 3500,
                userMod = 7,
                events = 7,
                hours = 96,
                devices = iosDevices,
                osName = "iOS",
                osVersions = iosVersions,
            ),
        ),

        issueInsert(
            DemoIssueInsertSpec(
                project = P2,
                issueId = "demo-ios-17",
                platform = "ios",
                level = "error",
                message = "AVFoundationErrorDomain -11819: AVCaptureSession cannot initialize camera capture for this device",
                exType = "AVFoundationError",
                exValue = "Camera capture device unavailable — ARKit session failed to start on unsupported device",
                stack = "at AcmeARTryOnVC.startARSession() + 89 (ARTryOnViewController.swift:45)\\n" +
                    "at AcmeProductDetailVC.didTapTryOn(_:) + 234 (ProductDetailViewController.swift:289)",
                release = iosRelease,
                userBase = 3600,
                userMod = 6,
                events = 6,
                hours = 72,
                devices = iosDevices,
                osName = "iOS",
                osVersions = iosVersions,
            ),
        ),

        issueInsert(
            DemoIssueInsertSpec(
                project = P2,
                issueId = "demo-ios-18",
                platform = "ios",
                level = "warning",
                message = "CKErrorDomain 1: CloudKit account not available — iCloud not signed in",
                exType = "CKError",
                exValue = "CloudKit sync failed — user not signed into iCloud, wishlist sync disabled",
                stack = "at AcmeCloudKitSyncService.syncWishlist() + 112 (CloudKitSyncService.swift:67)\\n" +
                    "at AcmeWishlistVC.viewDidAppear(_:) + 78 (WishlistViewController.swift:34)",
                release = iosRelease,
                userBase = 3700,
                userMod = 6,
                events = 6,
                hours = 120,
                devices = iosDevices,
                osName = "iOS",
                osVersions = iosVersions,
            ),
        ),

        issueInsert(
            DemoIssueInsertSpec(
                project = P2,
                issueId = "demo-ios-19",
                platform = "ios",
                level = "error",
                message = "NSInvalidUnarchiveOperationException: Cannot decode object of class AcmeUserPreferences because no " +
                    "such class exists",
                exType = "NSKeyedUnarchiver",
                exValue = "Class AcmeUserPreferences renamed to UserPreferences — archived data cannot be decoded",
                stack = "at NSKeyedUnarchiver.decodeObject(forKey:) + 234\\n" +
                    "at AcmePreferencesManager.loadPreferences() + 89 (PreferencesManager.swift:45)",
                release = iosRelease,
                userBase = 3800,
                userMod = 5,
                events = 5,
                hours = 96,
                devices = iosDevices,
                osName = "iOS",
                osVersions = iosVersions,
            ),
        ),

        issueInsert(
            DemoIssueInsertSpec(
                project = P2,
                issueId = "demo-ios-20",
                platform = "ios",
                level = "error",
                message = "FirebaseAuthErrorDomain 17020: Network error occurred, please try again",
                exType = "FirebaseAuthError",
                exValue = "Firebase Auth network request failed during social login — APNS token not registered",
                stack = "at AcmeSocialAuthService.signIn(with:) + 178 (SocialAuthService.swift:89)\\n" +
                    "at AcmeLoginVC.didTapGoogleSignIn(_:) + 234 (LoginViewController.swift:156)",
                release = iosRelease,
                userBase = 3900,
                userMod = 5,
                events = 5,
                hours = 72,
                devices = iosDevices,
                osName = "iOS",
                osVersions = iosVersions,
            ),
        ),

        issueInsert(
            DemoIssueInsertSpec(
                project = P2,
                issueId = "demo-ios-21",
                platform = "ios",
                level = "error",
                message = "UIApplicationInvalidInterfaceOrientation: Supported orientations has no common orientation with " +
                    "the application",
                exType = "UIApplicationInvalidInterfaceOrientation",
                exValue = "Video player forced landscape-only but parent app requires portrait — orientation conflict",
                stack = "at -[UIViewController _validateRotationViewBounds] + 812\\n" +
                    "at AcmeVideoPlayerVC.viewDidAppear(_:) + 89 (VideoPlayerViewController.swift:56)",
                release = iosRelease,
                userBase = 4000,
                userMod = 4,
                events = 4,
                hours = 96,
                devices = iosDevices,
                osName = "iOS",
                osVersions = iosVersions,
            ),
        ),

        issueInsert(
            DemoIssueInsertSpec(
                project = P2,
                issueId = "demo-ios-22",
                platform = "ios",
                level = "fatal",
                message = "Fatal error: Unexpectedly found nil while implicitly unwrapping an Optional value in ProductService",
                exType = "Swift.UnexpectedNilError",
                exValue = "Force-unwrapped currentUser! is nil — user session expired during background fetch",
                stack = "at AcmeProductService.fetchRecommendations() + 89 (ProductService.swift:223)\\n" +
                    "at AcmeHomeVC.viewWillAppear(_:) + 178 (HomeViewController.swift:67)",
                release = iosRelease,
                userBase = 4100,
                userMod = 4,
                events = 4,
                hours = 48,
                devices = iosDevices,
                osName = "iOS",
                osVersions = iosVersions,
            ),
        ),

        issueInsert(
            DemoIssueInsertSpec(
                project = P2,
                issueId = "demo-ios-23",
                platform = "ios",
                level = "warning",
                message = "BGTaskScheduler error: Background task com.acme.sync expired before completion",
                exType = "BGTaskError",
                exValue = "Inventory sync background task exceeded 30-second time limit — partial sync committed",
                stack = "at AcmeInventorySyncTask.expirationHandler() + 45 (InventorySyncTask.swift:89)\\n" +
                    "at BGTaskScheduler.submit(_:) + 234",
                release = iosRelease,
                userBase = 4200,
                userMod = 3,
                events = 3,
                hours = 120,
                devices = iosDevices,
                osName = "iOS",
                osVersions = iosVersions,
            ),
        ),

        issueInsert(
            DemoIssueInsertSpec(
                project = P2,
                issueId = "demo-ios-24",
                platform = "ios",
                level = "error",
                message = "RLMException: Migration is required for object type 'Product' due to the following errors: " +
                    "Property 'sku' has been added to latest object model",
                exType = "RLMException",
                exValue = "Realm schema migration required from version 3 to 4 after adding Product.sku property",
                stack = "at RLMRealm.init(configuration:) + 456\\n" +
                    "at AcmeProductRepository.initializeDatabase() + 89 (ProductRepository.swift:34)",
                release = iosRelease,
                userBase = 4300,
                userMod = 3,
                events = 3,
                hours = 96,
                devices = iosDevices,
                osName = "iOS",
                osVersions = iosVersions,
            ),
        ),

        issueInsert(
            DemoIssueInsertSpec(
                project = P2,
                issueId = "demo-ios-25",
                platform = "ios",
                level = "error",
                message = "APNs device token registration failed: InvalidDeviceToken",
                exType = "APNsError",
                exValue = "Push notification device token rejected by APNs — sandbox token used in production environment",
                stack = "at AcmePushNotificationService" +
                    ".application(_:didFailToRegisterForRemoteNotificationsWithError:) + 78 " +
                    "(PushNotificationService.swift:56)\\n" +
                    "at UIApplication.registerForRemoteNotifications() + 234",
                release = iosRelease,
                userBase = 4400,
                userMod = 2,
                events = 2,
                hours = 72,
                devices = iosDevices,
                osName = "iOS",
                osVersions = iosVersions,
            ),
        ),

        // React Native issues (project -3)
        issueInsert(
            DemoIssueInsertSpec(
                project = P3,
                issueId = "demo-rn-1",
                platform = DEMO_PLATFORM_REACT_NATIVE,
                level = "error",
                message = "TypeError: Cannot read property 'id' of undefined",
                exType = "TypeError",
                exValue = "Accessing .id on undefined cart item — product removed from store while user viewed cart",
                stack = "at HomeScreen.render (HomeScreen.js:42)\\n" +
                    "at processChild (react-native/Libraries/Renderer/implementations/" +
                    "ReactNativeRenderer-prod.js:4072)",
                release = rnRelease,
                userBase = 3000,
                userMod = 55,
                events = 30,
                hours = 168,
                devices = rnDevices,
                osName = "Android",
                osVersions = rnVersions,
            ),
        ),

        issueInsert(
            DemoIssueInsertSpec(
                project = P3,
                issueId = "demo-rn-2",
                platform = DEMO_PLATFORM_REACT_NATIVE,
                level = "error",
                message = "UnhandledPromiseRejection: Network request failed in CheckoutService",
                exType = "UnhandledPromiseRejection",
                exValue = "Fetch to checkout API failed — CORS policy blocked request from React Native WebView",
                stack = "at CheckoutService.submitOrder (src/services/CheckoutService.js:89)\\n" +
                    "at CheckoutScreen.handleSubmit (src/screens/CheckoutScreen.js:156)",
                release = rnRelease,
                userBase = 3100,
                userMod = 50,
                events = 60,
                hours = 120,
                devices = rnDevices,
                osName = "Android",
                osVersions = rnVersions,
            ),
        ),

        issueInsert(
            DemoIssueInsertSpec(
                project = P3,
                issueId = "demo-rn-3",
                platform = DEMO_PLATFORM_REACT_NATIVE,
                level = "error",
                message = "RangeError: Maximum call stack size exceeded in CategoryTreeComponent",
                exType = "RangeError",
                exValue = "Infinite re-render loop in CategoryTree — useEffect missing dependency array",
                stack = "at CategoryTreeComponent.renderNode (src/components/CategoryTree.js:67)\\n" +
                    "at CategoryTreeComponent.renderNode (src/components/CategoryTree.js:78)",
                release = rnRelease,
                userBase = 3200,
                userMod = 30,
                events = 35,
                hours = 96,
                devices = rnDevices,
                osName = "Android",
                osVersions = rnVersions,
            ),
        ),

        issueInsert(
            DemoIssueInsertSpec(
                project = P3,
                issueId = "demo-rn-4",
                platform = DEMO_PLATFORM_REACT_NATIVE,
                level = "error",
                message = "TypeError: undefined is not a function (evaluating 'navigation.navigate')",
                exType = "TypeError",
                exValue = "navigation prop not passed to deeply nested ProductCard component",
                stack = "at ProductCard.onPress (src/components/ProductCard.js:34)\\n" +
                    "at TouchableHighlight.onPress (Libraries/Components/Touchable/TouchableHighlight.js:195)",
                release = rnRelease,
                userBase = 3300,
                userMod = 35,
                events = 40,
                hours = 144,
                devices = rnDevices,
                osName = "Android",
                osVersions = rnVersions,
            ),
        ),

        issueInsert(
            DemoIssueInsertSpec(
                project = P3,
                issueId = "demo-rn-5",
                platform = DEMO_PLATFORM_REACT_NATIVE,
                level = "error",
                message = "Error: Network request failed — Request to https://api.acmemobile.com/v2/checkout timed out",
                exType = "NetworkError",
                exValue = "Checkout API request timed out after 30 seconds — possible server congestion",
                stack = "at CheckoutService.submitOrder (src/services/CheckoutService.js:134)\\n" +
                    "at CheckoutScreen.onConfirmOrder (src/screens/CheckoutScreen.js:289)",
                release = rnRelease,
                userBase = 3400,
                userMod = 22,
                events = 25,
                hours = 72,
                devices = rnDevices,
                osName = "Android",
                osVersions = rnVersions,
            ),
        ),

        issueInsert(
            DemoIssueInsertSpec(
                project = P3,
                issueId = "demo-rn-6",
                platform = DEMO_PLATFORM_REACT_NATIVE,
                level = "error",
                message = "TypeError: Cannot convert undefined or null to object in CartReducer",
                exType = "TypeError",
                exValue = "Object.keys() called on undefined cart state — reducer received undefined instead of initial state",
                stack = "at CartReducer (src/store/reducers/cartReducer.js:45)\\n" +
                    "at combineReducers (node_modules/redux/dist/redux.js:589)",
                release = rnRelease,
                userBase = 3500,
                userMod = 18,
                events = 20,
                hours = 96,
                devices = rnDevices,
                osName = "Android",
                osVersions = rnVersions,
            ),
        ),

        issueInsert(
            DemoIssueInsertSpec(
                project = P3,
                issueId = "demo-rn-7",
                platform = DEMO_PLATFORM_REACT_NATIVE,
                level = "warning",
                message = "Warning: Maximum update depth exceeded in CartSummaryComponent",
                exType = "MaximumUpdateDepthError",
                exValue = "setState called inside render in CartSummary — causes infinite update loop",
                stack = "at CartSummaryComponent (src/components/CartSummary.js:89)\\n" +
                    "at checkForNestedUpdates (node_modules/react-dom/cjs/react-dom.development.js:25129)",
                release = rnRelease,
                userBase = 3600,
                userMod = 13,
                events = 15,
                hours = 72,
                devices = rnDevices,
                osName = "Android",
                osVersions = rnVersions,
            ),
        ),

        issueInsert(
            DemoIssueInsertSpec(
                project = P3,
                issueId = "demo-rn-8",
                platform = DEMO_PLATFORM_REACT_NATIVE,
                level = "warning",
                message = "Warning: ViewPropTypes has been removed from React Native. Use ViewPropTypes from " +
                    "@react-native-community/art",
                exType = "DeprecationWarning",
                exValue = "Third-party component react-native-camera still using deprecated ViewPropTypes",
                stack = "at checkPropTypes (node_modules/react/cjs/react.development.js:216)\\nat camera/CameraView.js:34",
                release = rnRelease,
                userBase = 3700,
                userMod = 11,
                events = 12,
                hours = 120,
                devices = rnDevices,
                osName = "Android",
                osVersions = rnVersions,
            ),
        ),

        issueInsert(
            DemoIssueInsertSpec(
                project = P3,
                issueId = "demo-rn-9",
                platform = DEMO_PLATFORM_REACT_NATIVE,
                level = "error",
                message = "AsyncStorage failed to get item 'authToken': Invalid JSON",
                exType = "AsyncStorageError",
                exValue = "Corrupted JSON in AsyncStorage authToken — stored value truncated during previous crash",
                stack = "at AuthService.getToken (src/services/AuthService.js:23)\\n" +
                    "at ApiClient.setAuthHeader (src/api/ApiClient.js:67)",
                release = rnRelease,
                userBase = 3800,
                userMod = 10,
                events = 10,
                hours = 96,
                devices = rnDevices,
                osName = "Android",
                osVersions = rnVersions,
            ),
        ),

        issueInsert(
            DemoIssueInsertSpec(
                project = P3,
                issueId = "demo-rn-10",
                platform = DEMO_PLATFORM_REACT_NATIVE,
                level = "error",
                message = "ReferenceError: Can't find variable: Stripe in PaymentScreen",
                exType = "ReferenceError",
                exValue = "Stripe native module not linked — missing react-native link for @stripe/stripe-react-native",
                stack = "at PaymentScreen.initializeStripe (src/screens/PaymentScreen.js:45)\\n" +
                    "at PaymentScreen.componentDidMount (src/screens/PaymentScreen.js:23)",
                release = rnRelease,
                userBase = 3900,
                userMod = 8,
                events = 8,
                hours = 72,
                devices = rnDevices,
                osName = "Android",
                osVersions = rnVersions,
            ),
        ),

        issueInsert(
            DemoIssueInsertSpec(
                project = P3,
                issueId = "demo-rn-11",
                platform = DEMO_PLATFORM_REACT_NATIVE,
                level = "error",
                message = "SyntaxError: JSON Parse error: Unexpected identifier 'undefined' in ProductService",
                exType = "SyntaxError",
                exValue = "JSON.parse of undefined string — empty response body from product search endpoint",
                stack = "at ProductService.parseResponse (src/services/ProductService.js:89)\\n" +
                    "at ProductListScreen.fetchProducts (src/screens/ProductListScreen.js:45)",
                release = rnRelease,
                userBase = 4000,
                userMod = 8,
                events = 8,
                hours = 120,
                devices = rnDevices,
                osName = "Android",
                osVersions = rnVersions,
            ),
        ),

        issueInsert(
            DemoIssueInsertSpec(
                project = P3,
                issueId = "demo-rn-12",
                platform = DEMO_PLATFORM_REACT_NATIVE,
                level = "error",
                message = "PaymentError: Your card was declined — insufficient funds",
                exType = "PaymentError",
                exValue = "Stripe card payment declined at checkout — card_declined error code",
                stack = "at PaymentService.processPayment (src/services/PaymentService.js:156)\\n" +
                    "at CheckoutScreen.onPaymentConfirm (src/screens/CheckoutScreen.js:234)",
                release = rnRelease,
                userBase = 4100,
                userMod = 7,
                events = 7,
                hours = 96,
                devices = rnDevices,
                osName = "Android",
                osVersions = rnVersions,
            ),
        ),

        issueInsert(
            DemoIssueInsertSpec(
                project = P3,
                issueId = "demo-rn-13",
                platform = DEMO_PLATFORM_REACT_NATIVE,
                level = "error",
                message = "Error: Couldn't find a navigation object. Is your component inside NavigationContainer?",
                exType = "NavigationError",
                exValue = "useNavigation hook called outside of NavigationContainer in ProductCard deep link handler",
                stack = "at useNavigation (node_modules/@react-navigation/native/src/useNavigation.tsx:23)\\n" +
                    "at ProductCard.handleDeepLink (src/components/ProductCard.js:78)",
                release = rnRelease,
                userBase = 4200,
                userMod = 6,
                events = 6,
                hours = 72,
                devices = rnDevices,
                osName = "Android",
                osVersions = rnVersions,
            ),
        ),

        issueInsert(
            DemoIssueInsertSpec(
                project = P3,
                issueId = "demo-rn-14",
                platform = DEMO_PLATFORM_REACT_NATIVE,
                level = "error",
                message = "TypeError: Cannot read property 'navigate' of undefined in NotificationHandler",
                exType = "TypeError",
                exValue = "Navigation ref not yet initialized when push notification arrives during app startup",
                stack = "at NotificationHandler.onNotification (src/services/NotificationHandler.js:45)\\n" +
                    "at PushNotification.configure (node_modules/react-native-push-notification/index.js:78)",
                release = rnRelease,
                userBase = 4300,
                userMod = 6,
                events = 6,
                hours = 96,
                devices = rnDevices,
                osName = "Android",
                osVersions = rnVersions,
            ),
        ),

        issueInsert(
            DemoIssueInsertSpec(
                project = P3,
                issueId = "demo-rn-15",
                platform = DEMO_PLATFORM_REACT_NATIVE,
                level = "warning",
                message = "Error: Failed to load image https://cdn.acmemobile.com/products/img_4521.jpg — 404 Not Found",
                exType = "ImageLoadError",
                exValue = "Product thumbnail deleted from CDN but not removed from product catalog — stale reference",
                stack = "at FastImage.onError (node_modules/react-native-fast-image/src/index.tsx:156)\\n" +
                    "at ProductThumbnail.render (src/components/ProductThumbnail.js:34)",
                release = rnRelease,
                userBase = 4400,
                userMod = 5,
                events = 5,
                hours = 120,
                devices = rnDevices,
                osName = "Android",
                osVersions = rnVersions,
            ),
        ),

        issueInsert(
            DemoIssueInsertSpec(
                project = P3,
                issueId = "demo-rn-16",
                platform = DEMO_PLATFORM_REACT_NATIVE,
                level = "error",
                message = "Error: Network error: Failed to fetch (GraphQL) — query: getProductRecommendations",
                exType = "GraphQLNetworkError",
                exValue = "GraphQL query to recommendations service failed — service unavailable during deployment",
                stack = "at ApolloClient.query (node_modules/apollo-client/ApolloClient.js:142)\\n" +
                    "at RecommendationService.fetchRecommendations (src/services/RecommendationService.js:67)",
                release = rnRelease,
                userBase = 4500,
                userMod = 5,
                events = 5,
                hours = 72,
                devices = rnDevices,
                osName = "Android",
                osVersions = rnVersions,
            ),
        ),

        issueInsert(
            DemoIssueInsertSpec(
                project = P3,
                issueId = "demo-rn-17",
                platform = DEMO_PLATFORM_REACT_NATIVE,
                level = "error",
                message = "TypeError: this.setState is not a function in LegacyCartComponent",
                exType = "TypeError",
                exValue = "setState called after component unmount — missing cleanup in legacy class component",
                stack = "at LegacyCartComponent.updateTotal (src/components/legacy/LegacyCart.js:156)\\n" +
                    "at LegacyCartComponent.componentDidUpdate (src/components/legacy/LegacyCart.js:89)",
                release = rnRelease,
                userBase = 4600,
                userMod = 4,
                events = 4,
                hours = 96,
                devices = rnDevices,
                osName = "Android",
                osVersions = rnVersions,
            ),
        ),

        issueInsert(
            DemoIssueInsertSpec(
                project = P3,
                issueId = "demo-rn-18",
                platform = DEMO_PLATFORM_REACT_NATIVE,
                level = "error",
                message = "Error: Animated: `value` argument for `interpolate` must be of type number or Animated.Value",
                exType = "AnimationError",
                exValue = "Product rating animation receives undefined value when rating data not yet loaded",
                stack = "at Animated.interpolate (Libraries/Animated/nodes/AnimatedInterpolation.js:302)\\n" +
                    "at ProductRatingBar.render (src/components/ProductRatingBar.js:45)",
                release = rnRelease,
                userBase = 4700,
                userMod = 4,
                events = 4,
                hours = 72,
                devices = rnDevices,
                osName = "Android",
                osVersions = rnVersions,
            ),
        ),

        issueInsert(
            DemoIssueInsertSpec(
                project = P3,
                issueId = "demo-rn-19",
                platform = DEMO_PLATFORM_REACT_NATIVE,
                level = "error",
                message = "Error: Firebase: Firebase App named '[DEFAULT]' already exists (app/duplicate-app)",
                exType = "FirebaseError",
                exValue = "Firebase initialized twice — initializeApp called in both App.js and a lazy-loaded module",
                stack = "at FirebaseAppImpl.checkDestroyed_ (node_modules/@firebase/app/dist/index.node.cjs.js:412)\\n" +
                    "at App.initializeFirebase (src/App.js:34)",
                release = rnRelease,
                userBase = 4800,
                userMod = 3,
                events = 3,
                hours = 120,
                devices = rnDevices,
                osName = "Android",
                osVersions = rnVersions,
            ),
        ),

        issueInsert(
            DemoIssueInsertSpec(
                project = P3,
                issueId = "demo-rn-20",
                platform = DEMO_PLATFORM_REACT_NATIVE,
                level = "error",
                message = "TypeError: Cannot destructure property 'id' of 'route.params' as it is undefined",
                exType = "TypeError",
                exValue = "Product detail screen opened without required route params — deep link malformed",
                stack = "at ProductDetailScreen (src/screens/ProductDetailScreen.js:23)\\n" +
                    "at SceneView (node_modules/@react-navigation/native-stack/src/views/NativeStackView.tsx:156)",
                release = rnRelease,
                userBase = 4900,
                userMod = 3,
                events = 3,
                hours = 96,
                devices = rnDevices,
                osName = "Android",
                osVersions = rnVersions,
            ),
        ),

        issueInsert(
            DemoIssueInsertSpec(
                project = P3,
                issueId = "demo-rn-21",
                platform = DEMO_PLATFORM_REACT_NATIVE,
                level = "warning",
                message = "redux-persist/createMigrate: state versions do not match — unsupported migration from version 2 to 4",
                exType = "MigrationError",
                exValue = "Redux persist state version jumped from 2 to 4 after skipping an intermediate release",
                stack = "at createMigrate (node_modules/redux-persist/es/createMigrate.js:34)\\n" +
                    "at persistReducer (node_modules/redux-persist/es/persistReducer.js:89)",
                release = rnRelease,
                userBase = 5000,
                userMod = 2,
                events = 2,
                hours = 168,
                devices = rnDevices,
                osName = "Android",
                osVersions = rnVersions,
            ),
        ),

        issueInsert(
            DemoIssueInsertSpec(
                project = P3,
                issueId = "demo-rn-22",
                platform = DEMO_PLATFORM_REACT_NATIVE,
                level = "warning",
                message = "Error: Push notification permission denied — cannot display promotional alerts",
                exType = "PermissionError",
                exValue = "User declined push notification permission — promotional feature disabled",
                stack = "at NotificationService.requestPermission (src/services/NotificationService.js:34)\\n" +
                    "at App.componentDidMount (src/App.js:89)",
                release = rnRelease,
                userBase = 5100,
                userMod = 2,
                events = 2,
                hours = 120,
                devices = rnDevices,
                osName = "Android",
                osVersions = rnVersions,
            ),
        ),

        // ── Transactions (all 3 projects) ────────────────────────────────────
        """
        INSERT INTO events (
            event_id, project_id, issue_id, timestamp, received_at, event_type,
            platform, level, transaction_name, transaction_op, duration_ms,
            environment, release, user_id, contexts
        )
        SELECT
            generateUUIDv4(),
            CASE number % 3 WHEN 0 THEN $P1 WHEN 1 THEN $P2 ELSE $P3 END,
            '',
            now() - INTERVAL (number % 168) HOUR, now() - INTERVAL (number % 168) HOUR,
            'transaction',
            arrayElement(['android', 'ios', '${DEMO_PLATFORM_REACT_NATIVE}'], number % 3 + 1),
            'info',
            arrayElement(['app.launch', 'checkout.complete', 'search.query', 'profile.load',
                          'cart.add', 'product.view', 'order.history', 'payment.process'], number % 8 + 1),
            arrayElement(['ui.load', 'http.client', 'navigation', 'db.query'], number % 4 + 1),
            CASE number % 8
                WHEN 0 THEN 1200 + (number * 97)  % 1800
                WHEN 1 THEN 400  + (number * 53)  % 800
                WHEN 2 THEN 80   + (number * 31)  % 220
                WHEN 3 THEN 150  + (number * 43)  % 350
                WHEN 4 THEN 50   + (number * 19)  % 150
                WHEN 5 THEN 100  + (number * 37)  % 300
                WHEN 6 THEN 200  + (number * 61)  % 600
                ELSE        600  + (number * 71)  % 900
            END,
            if(number % 10 = 0, 'staging', 'production'),
            arrayElement(['1.3.0', '2.1.0', '3.0.1'], number % 3 + 1),
            toString(1000 + (number % 150)),
            concat('{"trace":{"trace_id":"', toString(generateUUIDv4()), '","status":"ok"}}')
        FROM numbers(300)
        """.trimIndent()
    )

    for (sql in statements) {
        suspendRunCatching { ClickHouseClient.execute(sql.trimIndent()) }
            .onFailure { logger.warn { "Reseed events statement failed (non-fatal): ${it.message}" } }
    }
}

internal suspend fun reseedSessions() {
    val sql =
        """
        INSERT INTO sessions (session_id, project_id, started, duration_ms, status, errors, release, environment, user_id, received_at)
        SELECT
            generateUUIDv4(),
            CASE number % 3 WHEN 0 THEN $P1 WHEN 1 THEN $P2 ELSE $P3 END,
            now() - INTERVAL (number * 2) HOUR,
            1000 + (number * 123) % 300000,
            CASE WHEN number % 20 = 0 THEN 'crashed' WHEN number % 10 = 0 THEN 'exited' ELSE 'ok' END,
            if(number % 20 = 0, 1, 0),
            arrayElement(['1.3.0', '2.1.0', '3.0.1'], number % 3 + 1),
            'production',
            toString(1000 + (number % 100)),
            now() - INTERVAL (number * 2) HOUR
        FROM numbers(80)
        """.trimIndent()
    suspendRunCatching { ClickHouseClient.execute(sql) }
        .onFailure { logger.warn { "Reseed sessions failed (non-fatal): ${it.message}" } }
}

internal suspend fun reseedReplays() {
    val replayEventsSql =
        """
        INSERT INTO replay_events (
            replay_id, project_id, segment_id, timestamp, replay_start_timestamp,
            urls, error_ids, trace_ids, environment, release, platform,
            user_id, user_email, user_username, browser_name, browser_version,
            os_name, os_version, activity, tags
        )
        SELECT
            toUUID(concat(
                'aaaaaaaa-bbbb-cccc-dddd-',
                lpad(toString(number), 12, '0')
            )),
            CASE number % 3 WHEN 0 THEN $P1 WHEN 1 THEN $P2 ELSE $P3 END,
            0,
            now() - INTERVAL (number * 4) HOUR,
            now() - INTERVAL (number * 4 + 1) HOUR,
            ['com.acme.shopping://home', 'com.acme.shopping://product'],
            [],
            [],
            'production',
            arrayElement(['1.3.0', '2.1.0', '3.0.1'], number % 3 + 1),
            arrayElement(['android', 'ios', '${DEMO_PLATFORM_REACT_NATIVE}'], number % 3 + 1),
            toString(1000 + (number % 50)),
            concat('user', toString(number % 50), '@example.com'),
            concat('User ', toString(number % 50)),
            '', '', 
            arrayElement(['Android', 'iOS'], number % 2 + 1),
            arrayElement(['14', '17.2'], number % 2 + 1),
            50 + (number % 50),
            '{}'
        FROM numbers(20)
        """.trimIndent()
    suspendRunCatching { ClickHouseClient.execute(replayEventsSql) }
        .onFailure { logger.warn { "Reseed replay_events failed (non-fatal): ${it.message}" } }

    // Seed replay_segments with valid rrweb recording data so the replay viewer can render them.
    // Each replay gets one segment containing a Meta event (type 4) and a FullSnapshot (type 2).
    val segmentsSql =
        """
        INSERT INTO replay_segments (
            replay_id, project_id, segment_id, timestamp, recording_data
        )
        SELECT
            toUUID(concat(
                'aaaaaaaa-bbbb-cccc-dddd-',
                lpad(toString(number), 12, '0')
            )) as replay_id,
            CASE number % 3 WHEN 0 THEN $P1 WHEN 1 THEN $P2 ELSE $P3 END as project_id,
            0 as segment_id,
            now() - INTERVAL (number * 4) HOUR as timestamp,
            concat(
                '[',
                '{"type":4,"data":{"href":"https://demo.acme-shopping.com/","width":1280,"height":720},"timestamp":',
                toString(toInt64(toUnixTimestamp64Milli(now() - INTERVAL (number * 4 + 1) HOUR))),
                '},',
                '{"type":2,"data":{"node":{"type":0,"childNodes":[',
                  '{"type":1,"name":"html","publicId":"","systemId":"","childNodes":[],"id":2},',
                  '{"type":2,"tagName":"html","attributes":{"lang":"en"},"childNodes":[',
                    '{"type":2,"tagName":"head","attributes":{},"childNodes":[',
                      '{"type":2,"tagName":"title","attributes":{},"childNodes":[',
                        '{"type":3,"textContent":"Acme Shopping","id":5}',
                      '],"id":4}',
                    '],"id":3},',
                    '{"type":2,"tagName":"body","attributes":{"class":"app-root"},"childNodes":[',
                      '{"type":2,"tagName":"div","attributes":{"id":"app","class":"container"},"childNodes":[',
                        '{"type":2,"tagName":"header","attributes":{"class":"navbar"},"childNodes":[',
                          '{"type":2,"tagName":"h1","attributes":{},"childNodes":[',
                            '{"type":3,"textContent":"Acme Shopping","id":9}',
                          '],"id":8},',
                          '{"type":2,"tagName":"nav","attributes":{},"childNodes":[',
                            '{"type":2,"tagName":"a","attributes":{"href":"/products"},"childNodes":[',
                              '{"type":3,"textContent":"Products","id":12}',
                            '],"id":11},',
                            '{"type":2,"tagName":"a","attributes":{"href":"/cart"},"childNodes":[',
                              '{"type":3,"textContent":"Cart (', toString(number % 5), ')","id":14}',
                            '],"id":13}',
                          '],"id":10}',
                        '],"id":7},',
                        '{"type":2,"tagName":"main","attributes":{"class":"content"},"childNodes":[',
                          '{"type":2,"tagName":"div","attributes":{"class":"product-grid"},"childNodes":[',
                            '{"type":2,"tagName":"div","attributes":{"class":"product-card"},"childNodes":[',
                              '{"type":2,"tagName":"h3","attributes":{},"childNodes":[',
                                '{"type":3,"textContent":"Premium Widget","id":19}',
                              '],"id":18},',
                              '{"type":2,"tagName":"p","attributes":{"class":"price"},"childNodes":[',
                                '{"type":3,"textContent":"$', toString(19 + number * 10), '.99","id":21}',
                              '],"id":20},',
                              '{"type":2,"tagName":"button","attributes":{"class":"btn-primary"},"childNodes":[',
                                '{"type":3,"textContent":"Add to Cart","id":23}',
                              '],"id":22}',
                            '],"id":17}',
                          '],"id":16}',
                        '],"id":15}',
                      '],"id":6}',
                    '],"id":24}',
                  '],"id":1}',
                ']},"initialOffset":{"left":0,"top":0}},"timestamp":',
                toString(toInt64(toUnixTimestamp64Milli(now() - INTERVAL (number * 4 + 1) HOUR)) + 50),
                '},',
                '{"type":3,"data":{"source":1,"positions":[{"x":640,"y":360,"id":6,"timeOffset":0}]},"timestamp":',
                toString(toInt64(toUnixTimestamp64Milli(now() - INTERVAL (number * 4 + 1) HOUR)) + 2000),
                '},',
                '{"type":3,"data":{"source":1,"positions":[{"x":', toString(200 + number * 30 % 800),
                ',"y":', toString(200 + number * 20 % 400),
                ',"id":6,"timeOffset":0}]},"timestamp":',
                toString(toInt64(toUnixTimestamp64Milli(now() - INTERVAL (number * 4 + 1) HOUR)) + 5000),
                '},',
                '{"type":3,"data":{"source":2,"type":2,"id":22,"x":', toString(400 + number * 10 % 300),
                ',"y":', toString(350 + number * 5 % 200),
                '},"timestamp":',
                toString(toInt64(toUnixTimestamp64Milli(now() - INTERVAL (number * 4 + 1) HOUR)) + 8000),
                '},',
                '{"type":3,"data":{"source":5,"text":"', toString((number % 5) + 1),
                '","isChecked":false,"id":14},"timestamp":',
                toString(toInt64(toUnixTimestamp64Milli(now() - INTERVAL (number * 4 + 1) HOUR)) + 8500),
                '}',
                ']'
            ) as recording_data
        FROM numbers(20)
        """.trimIndent()
    suspendRunCatching { ClickHouseClient.execute(segmentsSql) }
        .onFailure { logger.warn { "Reseed replay_segments failed (non-fatal): ${it.message}" } }
}
