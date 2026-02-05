package com.moneat.e2e.android

import android.app.Application
import io.sentry.android.core.SentryAndroid

class MoneatApp : Application() {
    override fun onCreate() {
        super.onCreate()
        
        // Initialize Sentry
        SentryAndroid.init(this) { options ->
            // DSN will be loaded from local.properties or can be set here
            // options.dsn = "http://PUBLIC_KEY@localhost:8080/PROJECT_ID"
            
            options.environment = "e2e-testing"
            options.release = "android-e2e@1.0.0"
            options.tracesSampleRate = 1.0
            options.isDebug = true
            options.isEnableAutoSessionTracking = true
            
            // Enable stack trace attachment
            options.isAttachStacktrace = true
            options.isAttachThreads = true
            
            // Add custom tags
            options.setTag("platform", "android")
            options.setTag("test_type", "e2e")
        }
    }
}
