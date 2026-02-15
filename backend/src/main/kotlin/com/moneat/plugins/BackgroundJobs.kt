package com.moneat.plugins

import com.moneat.config.RedisClient
import com.moneat.services.*
import com.moneat.services.oncall.*
import io.ktor.server.application.*
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import mu.KotlinLogging

private val logger = KotlinLogging.logger {}

// Global service instances
private lateinit var escalationEngineInstance: EscalationEngine
private lateinit var incidentManagementServiceInstance: IncidentManagementService
private var slackUserGroupSyncServiceInstance: SlackUserGroupSyncService? = null
private lateinit var pushNotificationServiceInstance: PushNotificationService
private var onCallHandoffServiceInstance: OnCallHandoffService? = null

fun getEscalationEngine(): EscalationEngine = escalationEngineInstance
fun getIncidentManagementService(): IncidentManagementService = incidentManagementServiceInstance
fun getSlackUserGroupSyncService(): SlackUserGroupSyncService? = slackUserGroupSyncServiceInstance
fun getPushNotificationService(): PushNotificationService = pushNotificationServiceInstance

fun Application.configureBackgroundJobs() {
    val monitorAlertService = MonitorAlertService()
    val billingBackgroundService = BillingBackgroundService()
    val retentionBackgroundService = RetentionBackgroundService()
    val refreshTokenCleanupService = RefreshTokenCleanupService()
    val uptimeScheduler = UptimeScheduler()
    val queueKey = environment.config.property("ingest.queueKey").getString()
    val dlqKey = environment.config.property("ingest.dlqKey").getString()
    val workerCount = environment.config.property("ingest.workerCount").getString().toInt()
    val ingestionWorker = IngestionWorker(queueKey, dlqKey, workerCount)
    val logQueueKey = environment.config.propertyOrNull("logs.queueKey")?.getString() ?: "moneat:logs:queue"
    val logDlqKey = environment.config.propertyOrNull("logs.dlqKey")?.getString() ?: "moneat:logs:dlq"
    val logWorkerCount = environment.config.propertyOrNull("logs.workerCount")?.getString()?.toIntOrNull() ?: 2
    val logIngestionWorker = LogIngestionWorker(logQueueKey, logDlqKey, logWorkerCount)
    
    // Initialize on-call services
    val escalationPolicyService = EscalationPolicyService()
    val onCallScheduleService = OnCallScheduleService()
    val pushNotificationService = PushNotificationService()
    val slackService = SlackService()
    val redisClient = RedisClient()

    pushNotificationServiceInstance = pushNotificationService
    
    escalationEngineInstance = EscalationEngine(
        escalationPolicyService = escalationPolicyService,
        onCallScheduleService = onCallScheduleService,
        pushNotificationService = pushNotificationService,
        slackService = slackService,
        redisClient = redisClient
    )
    
    incidentManagementServiceInstance = IncidentManagementService(
        escalationEngine = escalationEngineInstance
    )
    
    slackUserGroupSyncServiceInstance = SlackUserGroupSyncService(
        onCallScheduleService = onCallScheduleService,
        slackService = slackService,
        redisClient = redisClient
    )
    
    onCallHandoffServiceInstance = OnCallHandoffService(
        onCallScheduleService = onCallScheduleService,
        pushNotificationService = pushNotificationService,
        redisClient = redisClient
    )

    // Create a coroutine scope for background jobs
    val jobScope = CoroutineScope(Dispatchers.Default + SupervisorJob())

    // Start the monitor alert service, billing service, retention service, refresh token cleanup, uptime scheduler, ingestion workers, escalation engine, and Slack usergroup sync
    logger.info { "Starting background jobs" }
    monitorAlertService.start(jobScope)
    billingBackgroundService.start(jobScope)
    retentionBackgroundService.start(jobScope)
    refreshTokenCleanupService.start(jobScope)
    uptimeScheduler.start()
    ingestionWorker.start()
    logIngestionWorker.start()
    escalationEngineInstance.start()
    slackUserGroupSyncServiceInstance?.start()
    onCallHandoffServiceInstance?.start()

    // Register shutdown hook
    environment.monitor.subscribe(ApplicationStopped) {
        logger.info { "Stopping background jobs" }
        monitorAlertService.stop()
        billingBackgroundService.stop()
        retentionBackgroundService.stop()
        refreshTokenCleanupService.stop()
        uptimeScheduler.stop()
        ingestionWorker.stop()
        logIngestionWorker.stop()
        escalationEngineInstance.stop()
        slackUserGroupSyncServiceInstance?.stop()
        onCallHandoffServiceInstance?.stop()
    }
}
