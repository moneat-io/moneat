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

import io.ktor.client.statement.bodyAsText
import mu.KotlinLogging

private val logger = KotlinLogging.logger {}

internal suspend fun checkFreshDataCount(): Long {
    val query =
        """
        SELECT count() as cnt
        FROM events
        WHERE project_id IN ($P1, $P2, $P3)
            AND timestamp >= now() - INTERVAL 7 DAY
        """.trimIndent()
    val response = ClickHouseClient.execute(query)
    val body = response.bodyAsText()
    if (response.status.value !in 200..299) return 0
    return body.trim().toLongOrNull() ?: 0
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
        runCatching { ClickHouseClient.execute(query) }
            .onFailure { logger.warn { "Purge $table failed (non-fatal): ${it.message}" } }
    }
    // Also purge issues materialized from demo events
    runCatching {
        ClickHouseClient.execute("ALTER TABLE issues DELETE WHERE project_id IN ($P1, $P2, $P3)")
    }
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
    fun issueInsert(
        project: String,
        issueId: String,
        platform: String,
        level: String,
        message: String,
        exType: String,
        exValue: String,
        stack: String,
        release: String,
        userBase: Int,
        userMod: Int,
        events: Int,
        hours: Int,
        devices: String,
        osName: String,
        osVersions: String
    ): String = """
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

    val androidRelease = "arrayElement(['1.3.0', '1.2.1', '1.2.0'], number % 3 + 1)"
    val iosRelease = "arrayElement(['2.1.0', '2.0.1', '2.0.0'], number % 3 + 1)"
    val rnRelease = "arrayElement(['3.0.1', '3.0.0', '2.9.0'], number % 3 + 1)"

    val statements = listOf(
        // Android issues (project -1)
        issueInsert(
            P1, "demo-android-1", "android", "fatal",
            "Attempt to invoke virtual method on a null object reference",
            "java.lang.NullPointerException",
            "Attempt to invoke virtual method 'void " +
                "android.widget.ImageView.setImageBitmap(android.graphics.Bitmap)' on a null object reference",
            "at com.acme.shopping.ui.ProductDetailFragment.updateUI(ProductDetailFragment.kt:87)\\n" +
                "at com.acme.shopping.ui.ProductDetailFragment.onViewCreated(ProductDetailFragment.kt:52)",
            androidRelease, 1000, 89, 150, 168, androidDevices, "Android", androidVersions
        ),

        issueInsert(
            P1, "demo-android-2", "android", "error",
            "android.os.NetworkOnMainThreadException",
            "android.os.NetworkOnMainThreadException",
            "Main thread network call in CheckoutRepository",
            "at android.os.StrictMode\$AndroidBlockGuardPolicy.onNetwork(StrictMode.java:1605)\\n" +
                "at com.acme.shopping.checkout.CheckoutRepository.validateCart(CheckoutRepository.kt:134)",
            androidRelease, 1100, 70, 80, 120, androidDevices, "Android", androidVersions
        ),

        issueInsert(
            P1, "demo-android-3", "android", "fatal",
            "java.lang.OutOfMemoryError: Failed to allocate a 48 MB allocation",
            "java.lang.OutOfMemoryError",
            "Failed to allocate a 48 MB allocation with 12 MB free bytes and 32 MB until OOM",
            "at com.acme.shopping.image.ImageLoader.loadFullResImage(ImageLoader.kt:212)\\n" +
                "at com.acme.shopping.ui.ProductGalleryFragment.onResume(ProductGalleryFragment.kt:78)",
            androidRelease, 1200, 35, 40, 96, androidDevices, "Android", androidVersions
        ),

        issueInsert(
            P1, "demo-android-4", "android", "error",
            "java.lang.IllegalStateException: Fragment ProductDetailFragment not attached to a context",
            "java.lang.IllegalStateException",
            "Fragment ProductDetailFragment{1a2b3c4} (2b34c5d6) not attached to a context.",
            "at androidx.fragment.app.Fragment.requireContext(Fragment.java:951)\\n" +
                "at com.acme.shopping.ui.ProductDetailFragment.showAddedToCartToast(ProductDetailFragment.kt:203)",
            androidRelease, 1300, 45, 50, 144, androidDevices, "Android", androidVersions
        ),

        issueInsert(
            P1, "demo-android-5", "android", "error",
            "java.util.ConcurrentModificationException in CartManager",
            "java.util.ConcurrentModificationException",
            "Concurrent modification of cart items list during checkout",
            "at java.util.ArrayList\$Itr.next(ArrayList.java:860)\\n" +
                "at com.acme.shopping.cart.CartManager.calculateTotal(CartManager.kt:156)",
            androidRelease, 1400, 28, 30, 72, androidDevices, "Android", androidVersions
        ),

        issueInsert(
            P1, "demo-android-6", "android", "error",
            "java.lang.ArrayIndexOutOfBoundsException: length=12; index=12",
            "java.lang.ArrayIndexOutOfBoundsException",
            "Array index 12 out of bounds for length 12 in product list adapter",
            "at com.acme.shopping.ui.adapters.ProductListAdapter.onBindViewHolder(ProductListAdapter.kt:89)\\n" +
                "at androidx.recyclerview.widget.RecyclerView\$Recycler" +
                ".tryGetViewHolderForPositionByDeadline(RecyclerView.java:6235)",
            androidRelease, 1500, 20, 20, 48, androidDevices, "Android", androidVersions
        ),

        issueInsert(
            P1, "demo-android-7", "android", "fatal",
            "java.lang.StackOverflowError in CategoryTreeRenderer",
            "java.lang.StackOverflowError",
            "Infinite recursive call while rendering nested category tree",
            "at com.acme.shopping.ui.CategoryTreeRenderer.renderNode(CategoryTreeRenderer.kt:67)\\n" +
                "at com.acme.shopping.ui.CategoryTreeRenderer.renderNode(CategoryTreeRenderer.kt:81)",
            androidRelease, 1600, 12, 13, 96, androidDevices, "Android", androidVersions
        ),

        issueInsert(
            P1, "demo-android-8", "android", "warning",
            "java.lang.IllegalArgumentException: Unknown URL scheme: acme://checkout",
            "java.lang.IllegalArgumentException",
            "Unknown URL scheme passed to NetworkClient deep link handler",
            "at com.acme.shopping.network.NetworkClient.buildUrl(NetworkClient.kt:45)\\n" +
                "at com.acme.shopping.deeplink.DeepLinkHandler.handle(DeepLinkHandler.kt:112)",
            androidRelease, 1700, 17, 17, 72, androidDevices, "Android", androidVersions
        ),

        issueInsert(
            P1, "demo-android-9", "android", "error",
            "java.lang.SecurityException: Permission Denial: requires android.permission.ACCESS_FINE_LOCATION",
            "java.lang.SecurityException",
            "ACCESS_FINE_LOCATION permission not granted before requesting location",
            "at android.os.Parcel.createExceptionOrNull(Parcel.java:2374)\\n" +
                "at com.acme.shopping.store.StoreLocatorService.getCurrentLocation(StoreLocatorService.kt:88)",
            androidRelease, 1800, 12, 12, 48, androidDevices, "Android", androidVersions
        ),

        issueInsert(
            P1, "demo-android-10", "android", "error",
            "android.database.sqlite.SQLiteException: UNIQUE constraint failed: orders.order_ref",
            "android.database.sqlite.SQLiteException",
            "UNIQUE constraint failed: orders.order_ref (code 2067 SQLITE_CONSTRAINT_UNIQUE)",
            "at android.database.sqlite.SQLiteConnection.nativeExecuteForLastInsertedRowId(Native Method)\\n" +
                "at com.acme.shopping.db.OrderDao.insertOrder(OrderDao.kt:34)",
            androidRelease, 1900, 13, 13, 96, androidDevices, "Android", androidVersions
        ),

        issueInsert(
            P1, "demo-android-11", "android", "error",
            "org.json.JSONException: Value null at response of type org.json.JSONObject\$1 cannot be converted " +
                "to JSONObject",
            "org.json.JSONException",
            "Null response body from product API could not be parsed",
            "at org.json.JSON.typeMismatch(JSON.java:111)\\n" +
                "at com.acme.shopping.network.ProductApiParser.parse(ProductApiParser.kt:67)",
            androidRelease, 2000, 10, 10, 72, androidDevices, "Android", androidVersions
        ),

        issueInsert(
            P1, "demo-android-12", "android", "error",
            "java.net.ConnectException: Failed to connect to api.acmemobile.com/93.184.216.34:443",
            "java.net.ConnectException",
            "Connection to checkout API timed out after 30 seconds",
            "at com.android.okhttp.internal.io.RealConnection.connectSocket(RealConnection.java:187)\\n" +
                "at com.acme.shopping.network.ApiService\$CheckoutService.placeOrder(ApiService.kt:289)",
            androidRelease, 2100, 12, 12, 48, androidDevices, "Android", androidVersions
        ),

        issueInsert(
            P1, "demo-android-13", "android", "error",
            "javax.net.ssl.SSLHandshakeException: Certificate expired for api.acmemobile.com",
            "javax.net.ssl.SSLHandshakeException",
            "SSL certificate for api.acmemobile.com expired on 2024-01-15",
            "at com.android.org.conscrypt.OpenSSLSocketImpl.startHandshake(OpenSSLSocketImpl.java:361)\\n" +
                "at com.acme.shopping.network.SecureApiClient.connect(SecureApiClient.kt:78)",
            androidRelease, 2200, 10, 10, 24, androidDevices, "Android", androidVersions
        ),

        issueInsert(
            P1, "demo-android-14", "android", "error",
            "android.content.ActivityNotFoundException: No Activity found to handle Intent { " +
                "act=com.acme.payment.CHECKOUT }",
            "android.content.ActivityNotFoundException",
            "Payment activity not found - payment module may not be installed",
            "at android.app.Instrumentation.checkStartActivityResult(Instrumentation.java:2085)\\n" +
                "at com.acme.shopping.checkout.PaymentLauncher.launch(PaymentLauncher.kt:56)",
            androidRelease, 2300, 7, 7, 96, androidDevices, "Android", androidVersions
        ),

        issueInsert(
            P1, "demo-android-15", "android", "error",
            "java.lang.ClassCastException: com.acme.shopping.model.SaleItem cannot be cast to " +
                "com.acme.shopping.model.ProductItem",
            "java.lang.ClassCastException",
            "Type mismatch in search results adapter — sale items mixed with regular products",
            "at com.acme.shopping.ui.adapters.SearchResultAdapter.onBindViewHolder(SearchResultAdapter.kt:112)\\n" +
                "at androidx.recyclerview.widget.RecyclerView.onScrolled(RecyclerView.java:1841)",
            androidRelease, 2400, 7, 7, 72, androidDevices, "Android", androidVersions
        ),

        issueInsert(
            P1, "demo-android-16", "android", "warning",
            "java.lang.NumberFormatException: For input string: '12.99 USD'",
            "java.lang.NumberFormatException",
            "Price string contains currency symbol, cannot parse as Double",
            "at java.lang.FloatingDecimal.readJavaFormatString(FloatingDecimal.java:2043)\\n" +
                "at com.acme.shopping.ui.PriceFormatter.parse(PriceFormatter.kt:29)",
            androidRelease, 2500, 6, 6, 48, androidDevices, "Android", androidVersions
        ),

        issueInsert(
            P1, "demo-android-17", "android", "error",
            "java.io.FileNotFoundException: /data/user/0/com.acme.shopping/cache/profile_img_1234.jpg (No such " +
                "file or directory)",
            "java.io.FileNotFoundException",
            "Cached profile image file deleted by system while still referenced",
            "at java.io.FileInputStream.open0(Native Method)\\n" +
                "at com.acme.shopping.profile.ProfileImageManager.loadCachedImage(ProfileImageManager.kt:88)",
            androidRelease, 2600, 5, 5, 120, androidDevices, "Android", androidVersions
        ),

        issueInsert(
            P1, "demo-android-18", "android", "error",
            "java.lang.UnsupportedOperationException: Payment method BNPL not supported in region",
            "java.lang.UnsupportedOperationException",
            "Buy-now-pay-later payment method unavailable for selected shipping region",
            "at com.acme.shopping.payment.PaymentProcessor.process(PaymentProcessor.kt:234)\\n" +
                "at com.acme.shopping.checkout.CheckoutViewModel.confirmOrder(CheckoutViewModel.kt:178)",
            androidRelease, 2700, 4, 4, 96, androidDevices, "Android", androidVersions
        ),

        issueInsert(
            P1, "demo-android-19", "android", "error",
            "android.os.DeadObjectException in PushNotificationService",
            "android.os.DeadObjectException",
            "Binder connection to push notification service died unexpectedly",
            "at android.os.BinderProxy.transactNative(Native Method)\\n" +
                "at com.acme.shopping.notifications.PushNotificationService.sendToken(" +
                "PushNotificationService.kt:67)",
            androidRelease, 2800, 4, 4, 72, androidDevices, "Android", androidVersions
        ),

        issueInsert(
            P1, "demo-android-20", "android", "error",
            "android.os.RemoteException in PaymentService",
            "android.os.RemoteException",
            "Remote payment service disconnected during transaction",
            "at com.acme.shopping.payment.PaymentServiceConnection" +
                ".onServiceDisconnected(PaymentServiceConnection.kt:45)\\n" +
                "at com.acme.shopping.checkout.CheckoutActivity.finalizePayment(CheckoutActivity.kt:312)",
            androidRelease, 2900, 4, 4, 48, androidDevices, "Android", androidVersions
        ),

        issueInsert(
            P1, "demo-android-21", "android", "error",
            "com.google.firebase.firestore.FirebaseFirestoreException: PERMISSION_DENIED: Missing or " +
                "insufficient permissions",
            "com.google.firebase.firestore.FirebaseFirestoreException",
            "Firestore security rules blocking wishlist read for unauthenticated user",
            "at com.google.firebase.firestore.FirebaseFirestore.collection(FirebaseFirestore.java:234)\\n" +
                "at com.acme.shopping.wishlist.WishlistRepository.fetchWishlist(WishlistRepository.kt:56)",
            androidRelease, 3000, 3, 3, 96, androidDevices, "Android", androidVersions
        ),

        issueInsert(
            P1, "demo-android-22", "android", "warning",
            "java.text.ParseException: Unparseable date: 2024-13-45T00:00:00Z",
            "java.text.ParseException",
            "Invalid ISO 8601 date from order history API response",
            "at java.text.SimpleDateFormat.parse(SimpleDateFormat.java:1457)\\n" +
                "at com.acme.shopping.orders.OrderHistoryParser.parseDate(OrderHistoryParser.kt:78)",
            androidRelease, 3100, 2, 2, 120, androidDevices, "Android", androidVersions
        ),

        issueInsert(
            P1, "demo-android-23", "android", "error",
            "java.lang.NegativeArraySizeException: -3 in FilterManager",
            "java.lang.NegativeArraySizeException",
            "Negative size passed to array constructor when no filters selected",
            "at com.acme.shopping.search.FilterManager.buildFilterArray(FilterManager.kt:112)\\n" +
                "at com.acme.shopping.ui.SearchFragment.applyFilters(SearchFragment.kt:234)",
            androidRelease, 3200, 2, 2, 72, androidDevices, "Android", androidVersions
        ),

        issueInsert(
            P1, "demo-android-24", "android", "error",
            "androidx.work.WorkerInitializationException: Could not instantiate SyncWorker",
            "androidx.work.WorkerInitializationException",
            "WorkManager SyncWorker failed to initialize — missing dependency injection",
            "at androidx.work.WorkerFactory.createWorkerWithDefaultFallback(WorkerFactory.java:98)\\n" +
                "at com.acme.shopping.sync.SyncWorker.<init>(SyncWorker.kt:24)",
            androidRelease, 3300, 2, 2, 96, androidDevices, "Android", androidVersions
        ),

        issueInsert(
            P1, "demo-android-25", "android", "fatal",
            "android.database.sqlite.SQLiteDatabaseCorruptException: database disk image is malformed",
            "android.database.sqlite.SQLiteDatabaseCorruptException",
            "Room database on-disk file corrupted — possible incomplete write during crash",
            "at android.database.sqlite.SQLiteConnection.nativeExecuteForCursorWindow(Native Method)\\n" +
                "at com.acme.shopping.db.AppDatabase\$\$_Impl.clearAllTables(AppDatabase.kt:45)",
            androidRelease, 3400, 1, 1, 48, androidDevices, "Android", androidVersions
        ),

        // iOS issues (project -2)
        issueInsert(
            P2, "demo-ios-1", "ios", "error",
            "NSInvalidArgumentException: -[UIViewController presentViewController:animated:completion:] called " +
                "on nil",
            "NSInvalidArgumentException",
            "Attempt to present view controller from a deallocated UIViewController",
            "at -[UIViewController presentViewController:animated:completion:] + 48\\n" +
                "at -[AcmeProductDetailVC showCheckout] (ProductDetailViewController.m:312)",
            iosRelease, 2000, 75, 40, 168, iosDevices, "iOS", iosVersions
        ),

        issueInsert(
            P2, "demo-ios-2", "ios", "fatal",
            "EXC_BAD_ACCESS (SIGSEGV) in ProductImageCache",
            "EXC_BAD_ACCESS",
            "SIGSEGV KERN_INVALID_ADDRESS at 0x0000000000000018 — dangling pointer to deallocated image cache " +
                "entry",
            "at AcmeProductImageCache.imageForURL(_:) + 156 (ProductImageCache.swift:89)\\n" +
                "at AcmeProductCell.configure(with:) + 304 (ProductCell.swift:67)",
            iosRelease, 2100, 60, 70, 120, iosDevices, "iOS", iosVersions
        ),

        issueInsert(
            P2, "demo-ios-3", "ios", "fatal",
            "Fatal error: Index out of range in CartViewController",
            "Swift.IndexOutOfRangeError",
            "Array index 5 is out of bounds for array with length 5 while removing cart item",
            "at Swift._ArrayBuffer._checkValidSubscript(_:withSubscriptCheck:) + 220 " +
                "(CartViewController.swift:178)\\n" +
                "at AcmeCartViewController.removeItem(at:) + 88 (CartViewController.swift:178)",
            iosRelease, 2200, 45, 50, 96, iosDevices, "iOS", iosVersions
        ),

        issueInsert(
            P2, "demo-ios-4", "ios", "error",
            "NSRangeException: -[__NSArrayM objectAtIndex:]: index 15 beyond bounds for empty array",
            "NSRangeException",
            "Order history table view accessed index 15 on empty data source",
            "at -[__NSArrayM objectAtIndex:] + 36\\n" +
                "at -[AcmeOrderHistoryVC tableView:cellForRowAtIndexPath:] (OrderHistoryViewController.m:156)",
            iosRelease, 2300, 30, 35, 144, iosDevices, "iOS", iosVersions
        ),

        issueInsert(
            P2, "demo-ios-5", "ios", "fatal",
            "Thread 1: EXC_BAD_INSTRUCTION (EXC_I386_INVOP, subcode=0x0) in CheckoutViewController",
            "EXC_BAD_INSTRUCTION",
            "Force-unwrap of nil Optional in CheckoutViewController payment result handler",
            "at AcmeCheckoutViewController.handlePaymentResult(_:) + 312 (CheckoutViewController.swift:234)\\n" +
                "at AcmePaymentService.onComplete(_:) + 88 (PaymentService.swift:156)",
            iosRelease, 2400, 22, 25, 72, iosDevices, "iOS", iosVersions
        ),

        issueInsert(
            P2, "demo-ios-6", "ios", "error",
            "NSURLErrorDomain -1009: The Internet connection appears to be offline",
            "NSURLError",
            "Network request failed — device has no internet connectivity during checkout",
            "at AcmeNetworkService.performRequest(_:completion:) + 278 (NetworkService.swift:112)\\n" +
                "at AcmeCheckoutService.submitOrder(_:) + 156 (CheckoutService.swift:89)",
            iosRelease, 2500, 18, 20, 48, iosDevices, "iOS", iosVersions
        ),

        issueInsert(
            P2, "demo-ios-7", "ios", "fatal",
            "Thread 1: signal SIGABRT — assertion failure in UITableView",
            "SIGABRT",
            "Invalid update: invalid number of sections 0 (before), 1 (after) in UITableView",
            "at AcmeProductListVC.reloadData() + 534 (ProductListViewController.swift:445)\\n" +
                "at -[UIApplication _handleApplicationActivationWithScene:...]",
            iosRelease, 2600, 13, 15, 96, iosDevices, "iOS", iosVersions
        ),

        issueInsert(
            P2, "demo-ios-8", "ios", "error",
            "NSInternalInconsistencyException: Invalid update to UITableView section 0",
            "NSInternalInconsistencyException",
            "Attempted to delete rows while another animation was in progress — race condition in search results",
            "at -[UITableView _endCellAnimationsWithContext:] + 8234\\n" +
                "at AcmeSearchResultsVC.updateResults(_:) + 312 (SearchResultsViewController.swift:289)",
            iosRelease, 2700, 18, 20, 72, iosDevices, "iOS", iosVersions
        ),

        issueInsert(
            P2, "demo-ios-9", "ios", "error",
            "Swift.DecodingError.keyNotFound(CodingKeys.productId): No value associated with key productId",
            "Swift.DecodingError",
            "product_id key missing in product API response — backend returned camelCase vs snake_case mismatch",
            "at AcmeProductParser.decode(_:) + 234 (ProductParser.swift:67)\\n" +
                "at AcmeProductService.fetchProducts(completion:) + 388 (ProductService.swift:134)",
            iosRelease, 2800, 16, 18, 120, iosDevices, "iOS", iosVersions
        ),

        issueInsert(
            P2, "demo-ios-10", "ios", "error",
            "URLError -1001: The request timed out after 30 seconds",
            "URLError",
            "Checkout API request timed out — server overloaded during flash sale",
            "at AcmeAPIClient.dataTask(with:completionHandler:) + 156 (APIClient.swift:89)\\n" +
                "at AcmeOrderService.placeOrder(_:completion:) + 488 (OrderService.swift:278)",
            iosRelease, 2900, 13, 15, 48, iosDevices, "iOS", iosVersions
        ),

        issueInsert(
            P2, "demo-ios-11", "ios", "error",
            "NSCocoaErrorDomain 257: The file photo_library could not be opened because you don't have " +
                "permission to view it",
            "NSFileReadNoPermissionError",
            "Photo library access requested without NSPhotoLibraryUsageDescription in Info.plist",
            "at AcmeProfileImagePicker.requestPhotoAccess() + 78 (ProfileImagePicker.swift:45)\\n" +
                "at AcmeProfileVC.didTapProfilePhoto(_:) + 234 (ProfileViewController.swift:156)",
            iosRelease, 3000, 11, 12, 96, iosDevices, "iOS", iosVersions
        ),

        issueInsert(
            P2, "demo-ios-12", "ios", "error",
            "NSCocoaErrorDomain 4864: The model used to open the store is incompatible with the one used to " +
                "create the store",
            "NSCocoaErrorDomain",
            "CoreData model version mismatch after app update — migration required from V3 to V4",
            "at NSPersistentStoreCoordinator.addPersistentStore(ofType:configurationName:at:options:) + 312\\n" +
                "at AcmeDataStack.loadStores() + 156 (DataStack.swift:78)",
            iosRelease, 3100, 10, 10, 72, iosDevices, "iOS", iosVersions
        ),

        issueInsert(
            P2, "demo-ios-13", "ios", "error",
            "NSJSONSerialization error 3840: JSON text did not start with array or object and option to allow " +
                "fragments not set",
            "NSJSONSerialization",
            "Empty HTTP 200 response body from product search API cannot be parsed as JSON",
            "at AcmeSearchResponseParser.parse(_:) + 112 (SearchResponseParser.swift:34)\\n" +
                "at AcmeSearchService.search(query:completion:) + 234 (SearchService.swift:89)",
            iosRelease, 3200, 10, 10, 120, iosDevices, "iOS", iosVersions
        ),

        issueInsert(
            P2, "demo-ios-14", "ios", "error",
            "SKErrorDomain 0: Cannot connect to iTunes Store",
            "SKError",
            "StoreKit purchase failed — user cannot make in-app purchases (parental controls)",
            "at AcmePremiumSubscriptionService.purchase(_:) + 189 (PremiumSubscriptionService.swift:112)\\n" +
                "at AcmeSubscriptionVC.didTapSubscribe(_:) + 234 (SubscriptionViewController.swift:78)",
            iosRelease, 3300, 8, 8, 96, iosDevices, "iOS", iosVersions
        ),

        issueInsert(
            P2, "demo-ios-15", "ios", "error",
            "WKNavigationDelegate webView(_:didFailProvisionalNavigation:withError:): A server with the " +
                "specified hostname could not be found",
            "WKNavigationError",
            "WebView failed to load order tracking page — DNS lookup failure for tracking subdomain",
            "at AcmeOrderTrackingWebVC.webView(_:didFailProvisionalNavigation:withError:) + 156 " +
                "(OrderTrackingWebViewController.swift:67)\\n" +
                "at WebKit.WKWebView.performLoad(_:)",
            iosRelease, 3400, 8, 8, 72, iosDevices, "iOS", iosVersions
        ),

        issueInsert(
            P2, "demo-ios-16", "ios", "error",
            "NSUnknownKeyException: setValue:forUndefinedKey: 'discountedPrice' this class is not key value " +
                "coding-compliant",
            "NSUnknownKeyException",
            "KVC access to discountedPrice property not found in ProductModel after model refactor",
            "at NSObject.setValue(_:forKey:) + 56\\n" +
                "at AcmeProductListVC.configureCell(_:with:) + 178 (ProductListViewController.swift:345)",
            iosRelease, 3500, 7, 7, 96, iosDevices, "iOS", iosVersions
        ),

        issueInsert(
            P2, "demo-ios-17", "ios", "error",
            "AVFoundationErrorDomain -11819: AVCaptureSession cannot initialize camera capture for this device",
            "AVFoundationError",
            "Camera capture device unavailable — ARKit session failed to start on unsupported device",
            "at AcmeARTryOnVC.startARSession() + 89 (ARTryOnViewController.swift:45)\\n" +
                "at AcmeProductDetailVC.didTapTryOn(_:) + 234 (ProductDetailViewController.swift:289)",
            iosRelease, 3600, 6, 6, 72, iosDevices, "iOS", iosVersions
        ),

        issueInsert(
            P2, "demo-ios-18", "ios", "warning",
            "CKErrorDomain 1: CloudKit account not available — iCloud not signed in",
            "CKError",
            "CloudKit sync failed — user not signed into iCloud, wishlist sync disabled",
            "at AcmeCloudKitSyncService.syncWishlist() + 112 (CloudKitSyncService.swift:67)\\n" +
                "at AcmeWishlistVC.viewDidAppear(_:) + 78 (WishlistViewController.swift:34)",
            iosRelease, 3700, 6, 6, 120, iosDevices, "iOS", iosVersions
        ),

        issueInsert(
            P2, "demo-ios-19", "ios", "error",
            "NSInvalidUnarchiveOperationException: Cannot decode object of class AcmeUserPreferences because no " +
                "such class exists",
            "NSKeyedUnarchiver",
            "Class AcmeUserPreferences renamed to UserPreferences — archived data cannot be decoded",
            "at NSKeyedUnarchiver.decodeObject(forKey:) + 234\\n" +
                "at AcmePreferencesManager.loadPreferences() + 89 (PreferencesManager.swift:45)",
            iosRelease, 3800, 5, 5, 96, iosDevices, "iOS", iosVersions
        ),

        issueInsert(
            P2, "demo-ios-20", "ios", "error",
            "FirebaseAuthErrorDomain 17020: Network error occurred, please try again",
            "FirebaseAuthError",
            "Firebase Auth network request failed during social login — APNS token not registered",
            "at AcmeSocialAuthService.signIn(with:) + 178 (SocialAuthService.swift:89)\\n" +
                "at AcmeLoginVC.didTapGoogleSignIn(_:) + 234 (LoginViewController.swift:156)",
            iosRelease, 3900, 5, 5, 72, iosDevices, "iOS", iosVersions
        ),

        issueInsert(
            P2, "demo-ios-21", "ios", "error",
            "UIApplicationInvalidInterfaceOrientation: Supported orientations has no common orientation with " +
                "the application",
            "UIApplicationInvalidInterfaceOrientation",
            "Video player forced landscape-only but parent app requires portrait — orientation conflict",
            "at -[UIViewController _validateRotationViewBounds] + 812\\n" +
                "at AcmeVideoPlayerVC.viewDidAppear(_:) + 89 (VideoPlayerViewController.swift:56)",
            iosRelease, 4000, 4, 4, 96, iosDevices, "iOS", iosVersions
        ),

        issueInsert(
            P2, "demo-ios-22", "ios", "fatal",
            "Fatal error: Unexpectedly found nil while implicitly unwrapping an Optional value in ProductService",
            "Swift.UnexpectedNilError",
            "Force-unwrapped currentUser! is nil — user session expired during background fetch",
            "at AcmeProductService.fetchRecommendations() + 89 (ProductService.swift:223)\\n" +
                "at AcmeHomeVC.viewWillAppear(_:) + 178 (HomeViewController.swift:67)",
            iosRelease, 4100, 4, 4, 48, iosDevices, "iOS", iosVersions
        ),

        issueInsert(
            P2, "demo-ios-23", "ios", "warning",
            "BGTaskScheduler error: Background task com.acme.sync expired before completion",
            "BGTaskError",
            "Inventory sync background task exceeded 30-second time limit — partial sync committed",
            "at AcmeInventorySyncTask.expirationHandler() + 45 (InventorySyncTask.swift:89)\\n" +
                "at BGTaskScheduler.submit(_:) + 234",
            iosRelease, 4200, 3, 3, 120, iosDevices, "iOS", iosVersions
        ),

        issueInsert(
            P2, "demo-ios-24", "ios", "error",
            "RLMException: Migration is required for object type 'Product' due to the following errors: " +
                "Property 'sku' has been added to latest object model",
            "RLMException",
            "Realm schema migration required from version 3 to 4 after adding Product.sku property",
            "at RLMRealm.init(configuration:) + 456\\n" +
                "at AcmeProductRepository.initializeDatabase() + 89 (ProductRepository.swift:34)",
            iosRelease, 4300, 3, 3, 96, iosDevices, "iOS", iosVersions
        ),

        issueInsert(
            P2, "demo-ios-25", "ios", "error",
            "APNs device token registration failed: InvalidDeviceToken",
            "APNsError",
            "Push notification device token rejected by APNs — sandbox token used in production environment",
            "at AcmePushNotificationService" +
                ".application(_:didFailToRegisterForRemoteNotificationsWithError:) + 78 " +
                "(PushNotificationService.swift:56)\\n" +
                "at UIApplication.registerForRemoteNotifications() + 234",
            iosRelease, 4400, 2, 2, 72, iosDevices, "iOS", iosVersions
        ),

        // React Native issues (project -3)
        issueInsert(
            P3, "demo-rn-1", "react-native", "error",
            "TypeError: Cannot read property 'id' of undefined",
            "TypeError",
            "Accessing .id on undefined cart item — product removed from store while user viewed cart",
            "at HomeScreen.render (HomeScreen.js:42)\\n" +
                "at processChild (react-native/Libraries/Renderer/implementations/" +
                "ReactNativeRenderer-prod.js:4072)",
            rnRelease, 3000, 55, 30, 168, rnDevices, "Android", rnVersions
        ),

        issueInsert(
            P3, "demo-rn-2", "react-native", "error",
            "UnhandledPromiseRejection: Network request failed in CheckoutService",
            "UnhandledPromiseRejection",
            "Fetch to checkout API failed — CORS policy blocked request from React Native WebView",
            "at CheckoutService.submitOrder (src/services/CheckoutService.js:89)\\n" +
                "at CheckoutScreen.handleSubmit (src/screens/CheckoutScreen.js:156)",
            rnRelease, 3100, 50, 60, 120, rnDevices, "Android", rnVersions
        ),

        issueInsert(
            P3, "demo-rn-3", "react-native", "error",
            "RangeError: Maximum call stack size exceeded in CategoryTreeComponent",
            "RangeError",
            "Infinite re-render loop in CategoryTree — useEffect missing dependency array",
            "at CategoryTreeComponent.renderNode (src/components/CategoryTree.js:67)\\n" +
                "at CategoryTreeComponent.renderNode (src/components/CategoryTree.js:78)",
            rnRelease, 3200, 30, 35, 96, rnDevices, "Android", rnVersions
        ),

        issueInsert(
            P3, "demo-rn-4", "react-native", "error",
            "TypeError: undefined is not a function (evaluating 'navigation.navigate')",
            "TypeError",
            "navigation prop not passed to deeply nested ProductCard component",
            "at ProductCard.onPress (src/components/ProductCard.js:34)\\n" +
                "at TouchableHighlight.onPress (Libraries/Components/Touchable/TouchableHighlight.js:195)",
            rnRelease, 3300, 35, 40, 144, rnDevices, "Android", rnVersions
        ),

        issueInsert(
            P3, "demo-rn-5", "react-native", "error",
            "Error: Network request failed — Request to https://api.acmemobile.com/v2/checkout timed out",
            "NetworkError",
            "Checkout API request timed out after 30 seconds — possible server congestion",
            "at CheckoutService.submitOrder (src/services/CheckoutService.js:134)\\n" +
                "at CheckoutScreen.onConfirmOrder (src/screens/CheckoutScreen.js:289)",
            rnRelease, 3400, 22, 25, 72, rnDevices, "Android", rnVersions
        ),

        issueInsert(
            P3, "demo-rn-6", "react-native", "error",
            "TypeError: Cannot convert undefined or null to object in CartReducer",
            "TypeError",
            "Object.keys() called on undefined cart state — reducer received undefined instead of initial state",
            "at CartReducer (src/store/reducers/cartReducer.js:45)\\n" +
                "at combineReducers (node_modules/redux/dist/redux.js:589)",
            rnRelease, 3500, 18, 20, 96, rnDevices, "Android", rnVersions
        ),

        issueInsert(
            P3, "demo-rn-7", "react-native", "warning",
            "Warning: Maximum update depth exceeded in CartSummaryComponent",
            "MaximumUpdateDepthError",
            "setState called inside render in CartSummary — causes infinite update loop",
            "at CartSummaryComponent (src/components/CartSummary.js:89)\\n" +
                "at checkForNestedUpdates (node_modules/react-dom/cjs/react-dom.development.js:25129)",
            rnRelease, 3600, 13, 15, 72, rnDevices, "Android", rnVersions
        ),

        issueInsert(
            P3, "demo-rn-8", "react-native", "warning",
            "Warning: ViewPropTypes has been removed from React Native. Use ViewPropTypes from " +
                "@react-native-community/art",
            "DeprecationWarning",
            "Third-party component react-native-camera still using deprecated ViewPropTypes",
            "at checkPropTypes (node_modules/react/cjs/react.development.js:216)\\nat camera/CameraView.js:34",
            rnRelease, 3700, 11, 12, 120, rnDevices, "Android", rnVersions
        ),

        issueInsert(
            P3, "demo-rn-9", "react-native", "error",
            "AsyncStorage failed to get item 'authToken': Invalid JSON",
            "AsyncStorageError",
            "Corrupted JSON in AsyncStorage authToken — stored value truncated during previous crash",
            "at AuthService.getToken (src/services/AuthService.js:23)\\n" +
                "at ApiClient.setAuthHeader (src/api/ApiClient.js:67)",
            rnRelease, 3800, 10, 10, 96, rnDevices, "Android", rnVersions
        ),

        issueInsert(
            P3, "demo-rn-10", "react-native", "error",
            "ReferenceError: Can't find variable: Stripe in PaymentScreen",
            "ReferenceError",
            "Stripe native module not linked — missing react-native link for @stripe/stripe-react-native",
            "at PaymentScreen.initializeStripe (src/screens/PaymentScreen.js:45)\\n" +
                "at PaymentScreen.componentDidMount (src/screens/PaymentScreen.js:23)",
            rnRelease, 3900, 8, 8, 72, rnDevices, "Android", rnVersions
        ),

        issueInsert(
            P3, "demo-rn-11", "react-native", "error",
            "SyntaxError: JSON Parse error: Unexpected identifier 'undefined' in ProductService",
            "SyntaxError",
            "JSON.parse of undefined string — empty response body from product search endpoint",
            "at ProductService.parseResponse (src/services/ProductService.js:89)\\n" +
                "at ProductListScreen.fetchProducts (src/screens/ProductListScreen.js:45)",
            rnRelease, 4000, 8, 8, 120, rnDevices, "Android", rnVersions
        ),

        issueInsert(
            P3, "demo-rn-12", "react-native", "error",
            "PaymentError: Your card was declined — insufficient funds",
            "PaymentError",
            "Stripe card payment declined at checkout — card_declined error code",
            "at PaymentService.processPayment (src/services/PaymentService.js:156)\\n" +
                "at CheckoutScreen.onPaymentConfirm (src/screens/CheckoutScreen.js:234)",
            rnRelease, 4100, 7, 7, 96, rnDevices, "Android", rnVersions
        ),

        issueInsert(
            P3, "demo-rn-13", "react-native", "error",
            "Error: Couldn't find a navigation object. Is your component inside NavigationContainer?",
            "NavigationError",
            "useNavigation hook called outside of NavigationContainer in ProductCard deep link handler",
            "at useNavigation (node_modules/@react-navigation/native/src/useNavigation.tsx:23)\\n" +
                "at ProductCard.handleDeepLink (src/components/ProductCard.js:78)",
            rnRelease, 4200, 6, 6, 72, rnDevices, "Android", rnVersions
        ),

        issueInsert(
            P3, "demo-rn-14", "react-native", "error",
            "TypeError: Cannot read property 'navigate' of undefined in NotificationHandler",
            "TypeError",
            "Navigation ref not yet initialized when push notification arrives during app startup",
            "at NotificationHandler.onNotification (src/services/NotificationHandler.js:45)\\n" +
                "at PushNotification.configure (node_modules/react-native-push-notification/index.js:78)",
            rnRelease, 4300, 6, 6, 96, rnDevices, "Android", rnVersions
        ),

        issueInsert(
            P3, "demo-rn-15", "react-native", "warning",
            "Error: Failed to load image https://cdn.acmemobile.com/products/img_4521.jpg — 404 Not Found",
            "ImageLoadError",
            "Product thumbnail deleted from CDN but not removed from product catalog — stale reference",
            "at FastImage.onError (node_modules/react-native-fast-image/src/index.tsx:156)\\n" +
                "at ProductThumbnail.render (src/components/ProductThumbnail.js:34)",
            rnRelease, 4400, 5, 5, 120, rnDevices, "Android", rnVersions
        ),

        issueInsert(
            P3, "demo-rn-16", "react-native", "error",
            "Error: Network error: Failed to fetch (GraphQL) — query: getProductRecommendations",
            "GraphQLNetworkError",
            "GraphQL query to recommendations service failed — service unavailable during deployment",
            "at ApolloClient.query (node_modules/apollo-client/ApolloClient.js:142)\\n" +
                "at RecommendationService.fetchRecommendations (src/services/RecommendationService.js:67)",
            rnRelease, 4500, 5, 5, 72, rnDevices, "Android", rnVersions
        ),

        issueInsert(
            P3, "demo-rn-17", "react-native", "error",
            "TypeError: this.setState is not a function in LegacyCartComponent",
            "TypeError",
            "setState called after component unmount — missing cleanup in legacy class component",
            "at LegacyCartComponent.updateTotal (src/components/legacy/LegacyCart.js:156)\\n" +
                "at LegacyCartComponent.componentDidUpdate (src/components/legacy/LegacyCart.js:89)",
            rnRelease, 4600, 4, 4, 96, rnDevices, "Android", rnVersions
        ),

        issueInsert(
            P3, "demo-rn-18", "react-native", "error",
            "Error: Animated: `value` argument for `interpolate` must be of type number or Animated.Value",
            "AnimationError",
            "Product rating animation receives undefined value when rating data not yet loaded",
            "at Animated.interpolate (Libraries/Animated/nodes/AnimatedInterpolation.js:302)\\n" +
                "at ProductRatingBar.render (src/components/ProductRatingBar.js:45)",
            rnRelease, 4700, 4, 4, 72, rnDevices, "Android", rnVersions
        ),

        issueInsert(
            P3, "demo-rn-19", "react-native", "error",
            "Error: Firebase: Firebase App named '[DEFAULT]' already exists (app/duplicate-app)",
            "FirebaseError",
            "Firebase initialized twice — initializeApp called in both App.js and a lazy-loaded module",
            "at FirebaseAppImpl.checkDestroyed_ (node_modules/@firebase/app/dist/index.node.cjs.js:412)\\n" +
                "at App.initializeFirebase (src/App.js:34)",
            rnRelease, 4800, 3, 3, 120, rnDevices, "Android", rnVersions
        ),

        issueInsert(
            P3, "demo-rn-20", "react-native", "error",
            "TypeError: Cannot destructure property 'id' of 'route.params' as it is undefined",
            "TypeError",
            "Product detail screen opened without required route params — deep link malformed",
            "at ProductDetailScreen (src/screens/ProductDetailScreen.js:23)\\n" +
                "at SceneView (node_modules/@react-navigation/native-stack/src/views/NativeStackView.tsx:156)",
            rnRelease, 4900, 3, 3, 96, rnDevices, "Android", rnVersions
        ),

        issueInsert(
            P3, "demo-rn-21", "react-native", "warning",
            "redux-persist/createMigrate: state versions do not match — unsupported migration from version 2 to 4",
            "MigrationError",
            "Redux persist state version jumped from 2 to 4 after skipping an intermediate release",
            "at createMigrate (node_modules/redux-persist/es/createMigrate.js:34)\\n" +
                "at persistReducer (node_modules/redux-persist/es/persistReducer.js:89)",
            rnRelease, 5000, 2, 2, 168, rnDevices, "Android", rnVersions
        ),

        issueInsert(
            P3, "demo-rn-22", "react-native", "warning",
            "Error: Push notification permission denied — cannot display promotional alerts",
            "PermissionError",
            "User declined push notification permission — promotional feature disabled",
            "at NotificationService.requestPermission (src/services/NotificationService.js:34)\\n" +
                "at App.componentDidMount (src/App.js:89)",
            rnRelease, 5100, 2, 2, 120, rnDevices, "Android", rnVersions
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
            arrayElement(['android', 'ios', 'react-native'], number % 3 + 1),
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
        runCatching { ClickHouseClient.execute(sql.trimIndent()) }
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
    runCatching { ClickHouseClient.execute(sql) }
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
            arrayElement(['android', 'ios', 'react-native'], number % 3 + 1),
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
    runCatching { ClickHouseClient.execute(replayEventsSql) }
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
    runCatching { ClickHouseClient.execute(segmentsSql) }
        .onFailure { logger.warn { "Reseed replay_segments failed (non-fatal): ${it.message}" } }
}
