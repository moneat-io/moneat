package com.jetbrains.kmpapp

// iOS implementation - Sentry SDK for iOS would be needed for full functionality
actual fun triggerCrash() {
    throw RuntimeException("KMP iOS crash for E2E testing")
}

actual fun triggerException(onResult: (String) -> Unit) {
    try {
        throw IllegalStateException("KMP iOS test exception")
    } catch (e: Exception) {
        onResult("Exception thrown (iOS Sentry not configured)")
    }
}

actual fun triggerNetworkError(onResult: (String) -> Unit) {
    onResult("Network error triggered (iOS Sentry not configured)")
}

actual fun triggerBackgroundCrash() {
    throw RuntimeException("KMP iOS background crash")
}

actual fun triggerNullPointer(onResult: (String) -> Unit) {
    try {
        val nullString: String? = null
        @Suppress("UNNECESSARY_NOT_NULL_ASSERTION")
        val length = nullString!!.length
    } catch (e: Exception) {
        onResult("NullPointerException (iOS Sentry not configured)")
    }
}
