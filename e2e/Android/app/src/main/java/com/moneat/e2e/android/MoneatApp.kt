package com.moneat.e2e.android

import android.app.Application
import io.sentry.Sentry
import io.sentry.android.core.SentryAndroid
import io.sentry.protocol.User

class MoneatApp : Application() {
    override fun onCreate() {
        super.onCreate()
        
        // Initialize Sentry
        SentryAndroid.init(this) { options ->
            // DSN will be loaded from local.properties or can be set here
            // options.dsn = "https://PUBLIC_KEY@localhost:8080/PROJECT_ID"
            
            options.environment = "e2e-testing"
            options.release = "android-e2e@1.0.0"
            options.tracesSampleRate = 1.0
            options.isDebug = true
            options.isEnableAutoSessionTracking = true
            
            // Enable session replay at 100%
            options.sessionReplay.sessionSampleRate = 1.0
            options.sessionReplay.onErrorSampleRate = 1.0
            
            // Enable stack trace attachment
            options.isAttachStacktrace = true
            options.isAttachThreads = true
            
            // Add custom tags
            options.setTag("platform", "android")
            options.setTag("test_type", "e2e")
        }

        // Use a fixed user for deterministic E2E replay attribution.
        Sentry.setUser(User().apply {
            id = "android-e2e-user-001"
            email = "android-e2e@example.com"
            username = "android_e2e_tester"
        })
    }
}
