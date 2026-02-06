package com.moneat.e2e.android

import android.os.Bundle
import android.widget.EditText
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import com.moneat.e2e.android.databinding.ActivityMainBinding
import io.sentry.Breadcrumb
import io.sentry.Sentry
import io.sentry.SentryLevel
import io.sentry.SpanStatus
import io.sentry.protocol.Feedback
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

        binding.btnTransactionOk.setOnClickListener {
            log("Sending successful transaction with nested spans...")
            addBreadcrumb("User clicked Transaction OK button")
            triggerSuccessfulTransaction()
        }

        binding.btnTransactionSlow.setOnClickListener {
            log("Sending slow transaction profile...")
            addBreadcrumb("User clicked Slow Transaction button")
            triggerSlowTransaction()
        }

        binding.btnTransactionFail.setOnClickListener {
            log("Sending failed transaction + related error...")
            addBreadcrumb("User clicked Failed Transaction button")
            triggerFailedTransactionWithRelatedError()
        }

        binding.btnUserFeedback.setOnClickListener {
            log("Opening user feedback form...")
            addBreadcrumb("User clicked Send User Feedback button")
            showUserFeedbackDialog()
        }

        binding.btnClear.setOnClickListener {
            clearLog()
        }
    }

    private fun showUserFeedbackDialog() {
        val messageInput = EditText(this).apply {
            hint = "What happened? What did you expect?"
            minLines = 3
            setPadding(48, 32, 48, 32)
        }
        val nameInput = EditText(this).apply {
            hint = "Your name"
            setPadding(48, 16, 48, 16)
        }
        val emailInput = EditText(this).apply {
            hint = "your.email@example.com"
            setPadding(48, 16, 48, 16)
        }
        val container = android.widget.LinearLayout(this).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            setPadding(24, 24, 24, 24)
            addView(messageInput, android.widget.LinearLayout.LayoutParams(android.view.ViewGroup.LayoutParams.MATCH_PARENT, android.view.ViewGroup.LayoutParams.WRAP_CONTENT).apply { bottomMargin = 16 })
            addView(nameInput, android.widget.LinearLayout.LayoutParams(android.view.ViewGroup.LayoutParams.MATCH_PARENT, android.view.ViewGroup.LayoutParams.WRAP_CONTENT).apply { bottomMargin = 16 })
            addView(emailInput, android.widget.LinearLayout.LayoutParams(android.view.ViewGroup.LayoutParams.MATCH_PARENT, android.view.ViewGroup.LayoutParams.WRAP_CONTENT))
        }
        AlertDialog.Builder(this)
            .setTitle("Send User Feedback")
            .setView(container)
            .setPositiveButton("Send") { _, _ ->
                val message = messageInput.text.toString().trim().ifEmpty { "E2E feedback from Android" }
                val name = nameInput.text.toString().trim()
                val email = emailInput.text.toString().trim()
                val feedback = Feedback(message).apply {
                    if (name.isNotBlank()) this.name = name
                    if (email.isNotBlank()) this.contactEmail = email
                }
                Sentry.captureFeedback(feedback)
                log("User feedback sent to Sentry")
                showToast("Feedback sent to Sentry")
            }
            .setNegativeButton("Cancel", null)
            .show()
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

    private fun triggerSuccessfulTransaction() {
        thread {
            Sentry.setTag("performance_scenario", "success")
            val transaction = Sentry.startTransaction("E2E/checkout", "ui.load")
            try {
                val dbSpan = transaction.startChild("db.query", "SELECT cart_items")
                Thread.sleep(80)
                dbSpan.finish(SpanStatus.OK)

                val httpSpan = transaction.startChild("http.client", "GET /api/pricing")
                Thread.sleep(120)
                httpSpan.finish(SpanStatus.OK)

                val renderSpan = transaction.startChild("ui.render", "Render checkout screen")
                Thread.sleep(45)
                renderSpan.finish(SpanStatus.OK)

                transaction.finish(SpanStatus.OK)
                log("Successful transaction sent")
                showToast("Successful transaction sent")
            } catch (e: Exception) {
                transaction.finish(SpanStatus.INTERNAL_ERROR)
                log("Failed to send successful transaction: ${e.message}")
            }
        }
    }

    private fun triggerSlowTransaction() {
        thread {
            Sentry.setTag("performance_scenario", "slow")
            val transaction = Sentry.startTransaction("E2E/report.generate", "task.background")
            try {
                val fetchSpan = transaction.startChild("db.query", "Fetch report rows")
                Thread.sleep(650)
                fetchSpan.finish(SpanStatus.OK)

                val aggregateSpan = transaction.startChild("compute.aggregate", "Aggregate metrics")
                Thread.sleep(900)
                aggregateSpan.finish(SpanStatus.OK)

                val uploadSpan = transaction.startChild("http.client", "POST /api/reports")
                Thread.sleep(350)
                uploadSpan.finish(SpanStatus.OK)

                transaction.finish(SpanStatus.OK)
                log("Slow transaction sent (~1.9s)")
                showToast("Slow transaction sent")
            } catch (e: Exception) {
                transaction.finish(SpanStatus.INTERNAL_ERROR)
                log("Failed to send slow transaction: ${e.message}")
            }
        }
    }

    private fun triggerFailedTransactionWithRelatedError() {
        thread {
            Sentry.setTag("performance_scenario", "failed")
            val transaction = Sentry.startTransaction("E2E/payment.submit", "http.client")
            val transactionScope = transaction.makeCurrent()
            try {
                val authSpan = transaction.startChild("auth.jwt", "Validate auth token")
                Thread.sleep(120)
                authSpan.finish(SpanStatus.OK)

                val paymentSpan = transaction.startChild("http.client", "POST /api/payments")
                val paymentScope = paymentSpan.makeCurrent()
                val failure = IllegalStateException("E2E payment submission failed: upstream 502")
                try {
                    Thread.sleep(180)
                    Sentry.captureException(failure) { scope ->
                        scope.setTransaction(transaction)
                        scope.setActiveSpan(paymentSpan)
                        scope.setTag("error_type", "transaction_failure")
                        scope.setTag("trace_linked", "true")
                    }
                    paymentSpan.setThrowable(failure)
                } finally {
                    paymentScope.close()
                    paymentSpan.finish(SpanStatus.INTERNAL_ERROR)
                }

                transaction.finish(SpanStatus.INTERNAL_ERROR)
                log("Failed transaction and related error sent")
                showToast("Failed transaction + related error sent")
            } catch (e: Exception) {
                transaction.finish(SpanStatus.INTERNAL_ERROR)
                log("Failed to send failed transaction scenario: ${e.message}")
            } finally {
                transactionScope.close()
            }
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
        runOnUiThread {
            logBuilder.append("${System.currentTimeMillis()}: $message\n")
            binding.tvLog.text = logBuilder.toString()
        }
    }

    private fun clearLog() {
        runOnUiThread {
            logBuilder.clear()
            binding.tvLog.text = ""
        }
    }

    private fun showToast(message: String) {
        runOnUiThread {
            Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
        }
    }
}
