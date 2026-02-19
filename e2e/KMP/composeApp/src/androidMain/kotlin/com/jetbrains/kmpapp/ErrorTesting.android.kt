package com.jetbrains.kmpapp

import io.sentry.Breadcrumb
import io.sentry.Sentry
import io.sentry.SentryLevel
import java.net.SocketTimeoutException
import kotlin.concurrent.thread

actual fun triggerCrash() {
    Sentry.setTag("error_type", "crash")
    Sentry.setTag("platform", "android")
    throw RuntimeException("KMP Android crash for E2E testing")
}

actual fun triggerException(onResult: (String) -> Unit) {
    try {
        Sentry.setTag("error_type", "exception")
        Sentry.setTag("platform", "android")
        throw IllegalStateException("KMP Test exception with context")
    } catch (e: Exception) {
        Sentry.captureException(e)
        onResult("Exception captured and sent to Sentry")
    }
}

actual fun triggerNetworkError(onResult: (String) -> Unit) {
    try {
        Sentry.setTag("error_type", "network")
        Sentry.setTag("platform", "android")
        throw SocketTimeoutException("KMP API request timed out")
    } catch (e: Exception) {
        Sentry.captureException(e) { scope ->
            scope.setContexts(
                "network",
                mapOf(
                    "url" to "https://api.example.com/data",
                    "method" to "GET",
                    "timeout" to 30000,
                ),
            )
        }
        onResult("Network error sent to Sentry")
    }
}

actual fun triggerBackgroundCrash() {
    thread {
        Sentry.setTag("error_type", "background_crash")
        Sentry.setTag("platform", "android")
        val breadcrumb =
            Breadcrumb().apply {
                message = "Background thread started in KMP"
                level = SentryLevel.INFO
            }
        Sentry.addBreadcrumb(breadcrumb)
        Thread.sleep(500)
        throw RuntimeException("KMP background thread crash")
    }
}

actual fun triggerNullPointer(onResult: (String) -> Unit) {
    try {
        Sentry.setTag("error_type", "npe")
        Sentry.setTag("platform", "android")
        val nullString: String? = null

        @Suppress("UNNECESSARY_NOT_NULL_ASSERTION")
        val length = nullString!!.length
    } catch (e: Exception) {
        Sentry.captureException(e) { scope ->
            scope.setContexts(
                "error_context",
                mapOf(
                    "component" to "kmp_data_processor",
                    "operation" to "string_length",
                ),
            )
        }
        onResult("NullPointerException sent to Sentry")
    }
}
