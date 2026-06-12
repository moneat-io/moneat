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

package com.moneat.synthetics.routes

import com.microsoft.playwright.Browser
import com.microsoft.playwright.BrowserContext
import com.microsoft.playwright.BrowserType
import com.microsoft.playwright.Page
import com.microsoft.playwright.Playwright
import com.microsoft.playwright.PlaywrightException
import com.moneat.config.EnvConfig
import com.moneat.config.StorageConfig
import com.moneat.utils.UrlValidator
import com.moneat.utils.suspendRunCatching
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import mu.KotlinLogging
import java.util.UUID
import java.util.concurrent.Executors

private val logger = KotlinLogging.logger {}

private const val MAX_CONSOLE_ENTRIES = 100
private const val MAX_NETWORK_ENTRIES = 100
private const val DEFAULT_STEP_TIMEOUT_MS = 15_000.0
private const val DEFAULT_WAIT_MS = 1000.0
private const val VIEWPORT_W = 1280
private const val VIEWPORT_H = 800
private const val MS_PER_SECOND = 1000.0

/**
 * Runs synthetic browser/E2E tests on a real headless Chromium via Playwright.
 *
 * A single dedicated thread owns the Playwright runtime (Playwright objects are
 * thread-affine), which also serializes runs — a natural concurrency cap suited to
 * a small host. Disabled by default; enable with `SYNTHETICS_BROWSER_ENABLED=true`
 * on probes where the Chromium binary is installed. Never throws: any failure to
 * launch or drive the browser becomes a failed [SyntheticCheckResult].
 */
object BrowserCheckExecutor {
    private val browserJson = Json { ignoreUnknownKeys = true }

    private val browserDispatcher = Executors.newSingleThreadExecutor { r ->
        Thread(r, "synthetics-browser").apply { isDaemon = true }
    }.asCoroutineDispatcher()

    @Volatile private var playwright: Playwright? = null

    @Volatile private var browser: Browser? = null

    private fun isEnabled(): Boolean =
        EnvConfig.get("SYNTHETICS_BROWSER_ENABLED", "false").toBoolean()

    /** Lazily launches (and reuses) the Chromium browser on the dedicated thread. */
    private fun ensureBrowser(): Browser {
        browser?.let { return it }
        val pw = Playwright.create()
        playwright = pw
        val launched = pw.chromium().launch(
            BrowserType.LaunchOptions()
                .setHeadless(true)
                .setArgs(listOf("--no-sandbox", "--disable-dev-shm-usage"))
        )
        browser = launched
        return launched
    }

    private fun resetRuntime() {
        suspendRunCatching { browser?.close() }
        suspendRunCatching { playwright?.close() }
        browser = null
        playwright = null
    }

    suspend fun execute(test: SyntheticTestData): SyntheticCheckResult {
        if (!isEnabled()) {
            return SyntheticCheckResult(
                status = "failed",
                durationMs = 0,
                errorMessage = "Browser execution is not enabled on this probe"
            )
        }

        val steps = parseSteps(test.browserSteps)
        if (steps.isEmpty()) {
            return SyntheticCheckResult(
                status = "failed",
                durationMs = 0,
                errorMessage = "No browser steps configured"
            )
        }

        val startUrl = resolveStartUrl(test, steps)
        try {
            UrlValidator.validateExternalUrl(startUrl)
        } catch (e: UrlValidator.SsrfException) {
            return SyntheticCheckResult(
                status = "failed",
                durationMs = 0,
                errorMessage = "Blocked: ${e.message}"
            )
        }

        return withContext(browserDispatcher) {
            runJourney(test, steps, startUrl)
        }
    }

    private fun runJourney(
        test: SyntheticTestData,
        steps: List<BrowserStep>,
        startUrl: String
    ): SyntheticCheckResult {
        val runId = UUID.randomUUID()
        val console = mutableListOf<BrowserConsoleEntry>()
        val network = mutableListOf<BrowserNetworkEntry>()
        val stepResults = mutableListOf<BrowserStepResult>()
        val start = System.currentTimeMillis()

        val activeBrowser = try {
            ensureBrowser()
        } catch (e: PlaywrightException) {
            logger.warn { "Browser runtime unavailable for ${test.id}: ${e.message}" }
            resetRuntime()
            return SyntheticCheckResult(
                status = "failed",
                durationMs = System.currentTimeMillis() - start,
                errorMessage = "Browser runtime unavailable: ${e.message}"
            )
        }

        var context: BrowserContext? = null
        try {
            context = activeBrowser.newContext(
                Browser.NewContextOptions().setViewportSize(VIEWPORT_W, VIEWPORT_H)
            )
            val page = context.newPage()
            page.setDefaultTimeout(stepTimeout(test))
            attachListeners(page, console, network)
            if (steps.firstOrNull()?.action != "navigate") {
                page.navigate(startUrl)
            }

            val failedStep = executeSteps(page, test, steps, runId, stepResults)
            val failed = failedStep != null

            val totalMs = System.currentTimeMillis() - start
            val assertionResults = browserAssertionResults(steps, stepResults)
            val detail = BrowserRunDetail(
                steps = stepResults,
                console = console,
                network = network,
                viewport = "$VIEWPORT_W × $VIEWPORT_H",
                browser = "Chromium",
                failedStep = failedStep
            )
            return SyntheticCheckResult(
                status = if (failed) "failed" else "passed",
                durationMs = totalMs,
                errorMessage = if (failed) "Step $failedStep failed" else "",
                timings = mapOf("total" to totalMs.toDouble()),
                assertionResults = assertionResults,
                browser = detail
            )
        } catch (e: PlaywrightException) {
            logger.warn { "Browser run failed for ${test.id}: ${e.message}" }
            return SyntheticCheckResult(
                status = "failed",
                durationMs = System.currentTimeMillis() - start,
                errorMessage = "Browser run failed: ${e.message}",
                browser = BrowserRunDetail(steps = stepResults, console = console, network = network)
            )
        } finally {
            suspendRunCatching { context?.close() }
        }
    }

    private fun executeSteps(
        page: Page,
        test: SyntheticTestData,
        steps: List<BrowserStep>,
        runId: UUID,
        stepResults: MutableList<BrowserStepResult>
    ): Int? {
        var failedStep: Int? = null
        for ((index, step) in steps.withIndex()) {
            if (failedStep != null) {
                stepResults.add(skippedStep(step))
                continue
            }
            val outcome = runStep(page, step, test)
            val passed = outcome.first
            val screenshotKey = captureScreenshot(page, test.organizationId, runId, index + 1)
            stepResults.add(
                BrowserStepResult(
                    action = step.action,
                    label = stepLabel(step),
                    status = if (passed) "passed" else "failed",
                    durationMs = outcome.third,
                    screenshotKey = screenshotKey,
                    errorMessage = if (passed) "" else outcome.second
                )
            )
            if (!passed) {
                failedStep = index + 1
            }
        }
        return failedStep
    }

    /** Returns (passed, errorMessage, durationMs) for one step. */
    private fun runStep(
        page: Page,
        step: BrowserStep,
        test: SyntheticTestData
    ): Triple<Boolean, String, Long> {
        val t0 = System.currentTimeMillis()
        return try {
            when (step.action) {
                "navigate" -> {
                    val target = step.value.ifBlank { test.url ?: "" }
                    UrlValidator.validateExternalUrl(target)
                    page.navigate(target)
                    Triple(true, "", System.currentTimeMillis() - t0)
                }
                "click" -> {
                    page.click(step.selector)
                    Triple(true, "", System.currentTimeMillis() - t0)
                }
                "type" -> {
                    page.fill(step.selector, step.value)
                    Triple(true, "", System.currentTimeMillis() - t0)
                }
                "wait" -> {
                    page.waitForTimeout(step.value.toDoubleOrNull() ?: DEFAULT_WAIT_MS)
                    Triple(true, "", System.currentTimeMillis() - t0)
                }
                "assert" -> {
                    val ok = evaluateAssert(page, step)
                    Triple(ok, if (ok) "" else "Assertion failed: ${stepLabel(step)}", System.currentTimeMillis() - t0)
                }
                else -> Triple(true, "", System.currentTimeMillis() - t0)
            }
        } catch (e: UrlValidator.SsrfException) {
            Triple(false, "Blocked: ${e.message}", System.currentTimeMillis() - t0)
        } catch (e: PlaywrightException) {
            Triple(false, e.message ?: "step failed", System.currentTimeMillis() - t0)
        }
    }

    private fun evaluateAssert(page: Page, step: BrowserStep): Boolean =
        when (step.assertType) {
            "url_contains" -> page.url().contains(step.value)
            "text_visible" -> page.isVisible("text=${step.value}")
            "selector_visible" -> page.isVisible(step.selector)
            else -> step.selector.isBlank() || page.isVisible(step.selector)
        }

    private fun browserAssertionResults(
        steps: List<BrowserStep>,
        stepResults: List<BrowserStepResult>
    ): List<AssertionResult> =
        steps.zip(stepResults)
            .filter { (step, _) -> step.action == "assert" }
            .map { (step, result) -> browserAssertionResult(step, result) }

    private fun browserAssertionResult(step: BrowserStep, result: BrowserStepResult): AssertionResult {
        val expected = when (step.assertType) {
            "url_contains" -> "URL contains ${step.value}"
            "text_visible" -> "Text visible: ${step.value}"
            "selector_visible" -> "Selector visible: ${step.selector}"
            else -> "Visible: ${step.selector.ifBlank { step.value }}"
        }
        return AssertionResult(
            label = result.label,
            expected = expected,
            actual = if (result.status == "passed") expected else result.errorMessage.ifBlank { "Assertion failed" },
            passed = result.status == "passed"
        )
    }

    private fun captureScreenshot(
        page: Page,
        organizationId: Int,
        runId: UUID,
        stepNumber: Int
    ): String = suspendRunCatching {
        val bytes = page.screenshot()
        val key = "synthetics/$organizationId/$runId/step-$stepNumber.png"
        StorageConfig.provider.write(key, bytes)
        key
    }.getOrElse { "" }

    private fun attachListeners(
        page: Page,
        console: MutableList<BrowserConsoleEntry>,
        network: MutableList<BrowserNetworkEntry>
    ) {
        page.onConsoleMessage { msg ->
            if (console.size < MAX_CONSOLE_ENTRIES) {
                console.add(BrowserConsoleEntry(level = msg.type(), text = msg.text()))
            }
        }
        page.onResponse { resp ->
            if (network.size < MAX_NETWORK_ENTRIES) {
                network.add(
                    BrowserNetworkEntry(
                        status = resp.status(),
                        method = resp.request().method(),
                        url = resp.url()
                    )
                )
            }
        }
    }

    private fun skippedStep(step: BrowserStep): BrowserStepResult =
        BrowserStepResult(action = step.action, label = stepLabel(step), status = "skipped")

    private fun stepLabel(step: BrowserStep): String =
        step.label.ifBlank {
            when (step.action) {
                "navigate" -> "Navigate to ${step.value}"
                "click" -> "Click ${step.selector}"
                "type" -> "Type into ${step.selector}"
                "assert" -> "Assert ${step.value.ifBlank { step.selector }}"
                "wait" -> "Wait ${step.value}ms"
                else -> step.action
            }
        }

    private fun stepTimeout(test: SyntheticTestData): Double =
        (test.timeoutSeconds * MS_PER_SECOND).coerceIn(MS_PER_SECOND, DEFAULT_STEP_TIMEOUT_MS)

    private fun resolveStartUrl(test: SyntheticTestData, steps: List<BrowserStep>): String =
        test.url?.takeIf { it.isNotBlank() }
            ?: steps.firstOrNull { it.action == "navigate" }?.value
            ?: ""

    private fun parseSteps(raw: String?): List<BrowserStep> =
        raw?.let {
            suspendRunCatching {
                browserJson.decodeFromString<List<BrowserStep>>(it)
            }.getOrElse { emptyList() }
        } ?: emptyList()
}
