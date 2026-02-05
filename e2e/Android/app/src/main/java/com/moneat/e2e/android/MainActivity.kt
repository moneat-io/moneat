package com.moneat.e2e.android

import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.moneat.e2e.android.databinding.ActivityMainBinding
import io.sentry.Breadcrumb
import io.sentry.Sentry
import io.sentry.SentryLevel
import java.io.IOException
import java.net.SocketTimeoutException
import kotlin.concurrent.thread

class MainActivity : AppCompatActivity() {
    private lateinit var binding: ActivityMainBinding
    private val logBuilder = StringBuilder()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setupButtons()
        log("App initialized. Sentry ready.")
    }

    private fun setupButtons() {
        binding.btnCrash.setOnClickListener {
            log("Triggering uncaught exception...")
            addBreadcrumb("User clicked Crash button")
            triggerCrash()
        }

        binding.btnException.setOnClickListener {
            log("Throwing caught exception...")
            addBreadcrumb("User clicked Exception button")
            triggerException()
        }

        binding.btnNetwork.setOnClickListener {
            log("Simulating network error...")
            addBreadcrumb("User clicked Network button")
            triggerNetworkError()
        }

        binding.btnAnr.setOnClickListener {
            log("Simulating ANR (will freeze for 10s)...")
            addBreadcrumb("User clicked ANR button")
            triggerAnr()
        }

        binding.btnBackground.setOnClickListener {
            log("Triggering background thread crash...")
            addBreadcrumb("User clicked Background button")
            triggerBackgroundCrash()
        }

        binding.btnNull.setOnClickListener {
            log("Triggering null pointer exception...")
            addBreadcrumb("User clicked Null button")
            triggerNullPointer()
        }

        binding.btnClear.setOnClickListener {
            clearLog()
        }
    }

    private fun triggerCrash() {
        Sentry.setTag("error_type", "crash")
        Sentry.setExtra("trigger_source", "manual_button")
        throw RuntimeException("Intentional crash for E2E testing")
    }

    private fun triggerException() {
        try {
            Sentry.setTag("error_type", "exception")
            throw IllegalStateException("Test exception with context")
        } catch (e: Exception) {
            Sentry.captureException(e)
            log("Exception captured and sent to Sentry")
            showToast("Exception sent to Sentry")
        }
    }

    private fun triggerNetworkError() {
        try {
            Sentry.setTag("error_type", "network")
            Sentry.setTag("network_error", "timeout")
            throw SocketTimeoutException("API request timed out after 30s")
        } catch (e: Exception) {
            Sentry.captureException(e) { scope ->
                scope.setContexts("network", mapOf(
                    "url" to "https://api.example.com/users",
                    "method" to "GET",
                    "timeout" to 30000
                ))
            }
            log("Network error sent to Sentry")
            showToast("Network error sent to Sentry")
        }
    }

    private fun triggerAnr() {
        Sentry.setTag("error_type", "anr")
        addBreadcrumb("Starting ANR simulation")
        // Sleep on main thread (don't do this in real apps!)
        Thread.sleep(10000)
        log("ANR simulation complete")
    }

    private fun triggerBackgroundCrash() {
        thread {
            Sentry.setTag("error_type", "background_crash")
            Sentry.setTag("thread", "background")
            addBreadcrumb("Background thread started")
            Thread.sleep(500)
            throw RuntimeException("Background thread crash for E2E testing")
        }
    }

    private fun triggerNullPointer() {
        try {
            Sentry.setTag("error_type", "npe")
            val nullString: String? = null
            @Suppress("UNNECESSARY_NOT_NULL_ASSERTION")
            val length = nullString!!.length // Force NPE
        } catch (e: Exception) {
            Sentry.captureException(e) { scope ->
                scope.setContexts("error_context", mapOf(
                    "component" to "data_processor",
                    "operation" to "string_length"
                ))
            }
            log("NullPointerException sent to Sentry")
            showToast("NPE sent to Sentry")
        }
    }

    private fun addBreadcrumb(message: String) {
        val breadcrumb = Breadcrumb().apply {
            this.message = message
            level = SentryLevel.INFO
            category = "user_action"
        }
        Sentry.addBreadcrumb(breadcrumb)
    }

    private fun log(message: String) {
        logBuilder.append("${System.currentTimeMillis()}: $message\n")
        binding.tvLog.text = logBuilder.toString()
    }

    private fun clearLog() {
        logBuilder.clear()
        binding.tvLog.text = ""
    }

    private fun showToast(message: String) {
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
    }
}
