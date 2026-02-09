package com.jetbrains.kmpapp

import android.app.Application
import io.sentry.android.core.SentryAndroid

class MuseumApp : Application() {
    override fun onCreate() {
        super.onCreate()
        
        // Initialize Sentry
        SentryAndroid.init(this) { options ->
            // DSN will be loaded from local.properties or can be set here
            // options.dsn = "https://PUBLIC_KEY@localhost:8080/PROJECT_ID"
            
            options.environment = "e2e-testing"
            options.release = "kmp-e2e@1.0.0"
            options.tracesSampleRate = 1.0
            options.isDebug = true
            options.isEnableAutoSessionTracking = true
            
            // Add custom tags
            options.setTag("platform", "kmp-android")
            options.setTag("test_type", "e2e")
        }
    }
}
