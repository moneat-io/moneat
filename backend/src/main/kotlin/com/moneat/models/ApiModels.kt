package com.moneat.models

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement

@Serializable
data class SignupRequest(
    val email: String,
    val password: String,
    val name: String? = null,
    val acceptTerms: Boolean,
    val acceptPrivacy: Boolean,
    val termsVersion: String,
    val privacyVersion: String
)

@Serializable
data class LoginRequest(
    val email: String,
    val password: String
)

@Serializable
data class AuthResponse(
    val token: String,
    val user: UserResponse
)

@Serializable
data class UserResponse(
    val id: Int,
    val email: String,
    val name: String?,
    val emailVerified: Boolean = false,
    val onboardingCompleted: Boolean = false,
    val isAdmin: Boolean = false,
    val organizationSlug: String? = null
)

@Serializable
data class ProjectKeyResponse(
    val platformTarget: String?,
    val dsn: String
)

@Serializable
data class ProjectResponse(
    val id: Long,
    val name: String,
    val slug: String,
    val framework: String?,
    val keys: List<ProjectKeyResponse>,
    val dsn: String,  // First key's DSN for backward compatibility
    val issueCount: Long = 0
)

@Serializable
data class IssueResponse(
    val id: String,
    val projectId: Long,
    val title: String,
    val culprit: String,
    val level: String,
    val platform: String,
    val firstSeen: String,
    val lastSeen: String,
    val eventCount: Long,
    val userCount: Long,
    val status: String
)

@Serializable
data class IssueDetailResponse(
    val id: String,
    val projectId: Long,
    val title: String,
    val culprit: String,
    val level: String,
    val platform: String,
    val firstSeen: String,
    val lastSeen: String,
    val eventCount: Long,
    val userCount: Long,
    val status: String,
    val fingerprint: List<String>,
    val latestEvent: EventResponse?
)

@Serializable
data class EventResponse(
    val eventId: String,
    val timestamp: String,
    val message: String,
    val platform: String,
    val level: String,
    val environment: String?,
    val release: String?,
    val user: UserInfo?,
    val tags: HashMap<String, String> = hashMapOf(),
    val contexts: String,
    val exception: String?,
    val breadcrumbs: String?
)

@Serializable
data class ProjectStatsResponse(
    val totalEvents: Long,
    val totalIssues: Long,
    val unresolvedIssues: Long,
    val affectedUsers: Long,
    val eventsTimeline: List<TimelinePoint>,
    val eventsByLevel: Map<String, Long>,
    val eventsByPlatform: Map<String, Long>,
    val eventsByBrowser: Map<String, Long>,
    val eventsByEnvironment: Map<String, Long>,
    val issuesByStatus: Map<String, Long>,
    val topIssues: List<TopIssue>,
    val usersTimeline: List<TimelinePoint>,
    val releaseMarkers: List<ReleaseMarker> = emptyList()
)

@Serializable
data class TimelinePoint(
    val timestamp: String,
    val count: Long
)

@Serializable
data class TopIssue(
    val issueId: String,
    val title: String,
    val count: Long
)

@Serializable
data class TransactionSummaryResponse(
    val name: String,
    val op: String,
    val latestEventId: String? = null,
    val count: Long,
    val p50: Double,
    val p75: Double,
    val p95: Double,
    val failureRate: Double,
    val tpm: Double
)

@Serializable
data class IssueTransactionResponse(
    val eventId: String,
    val name: String,
    val op: String,
    val duration: Double,
    val timestamp: String,
    val status: String? = null,
    val traceId: String? = null
)

@Serializable
data class TransactionDetailResponse(
    val eventId: String,
    val name: String,
    val op: String,
    val startTimestamp: Double,
    val duration: Double,
    val traceId: String,
    val timestamp: String,
    val environment: String?,
    val release: String?,
    val status: String? = null,
    val tags: Map<String, String>,
    val contexts: String,
    val breadcrumbs: String? = null,
    val request: String? = null
)

@Serializable
data class SpanResponse(
    val spanId: String,
    val parentSpanId: String? = null,
    val op: String,
    val description: String,
    val startTimestamp: Double,
    val endTimestamp: Double,
    val duration: Double,
    val status: String? = null,
    val tags: Map<String, String> = emptyMap()
)

@Serializable
data class TransactionWithSpansResponse(
    val transaction: TransactionDetailResponse,
    val spans: List<SpanResponse>
)

@Serializable
data class SlowTransactionResponse(
    val eventId: String,
    val name: String,
    val op: String,
    val duration: Double,
    val timestamp: String
)

@Serializable
data class PerformanceStatsResponse(
    val apdex: Double,
    val throughput: List<TimelinePoint>,
    val slowestTransactions: List<SlowTransactionResponse>,
    val totalTransactions: Long,
    val avgDuration: Double
)

@Serializable
data class IssueUpdateRequest(
    val status: String? = null
)

@Serializable
data class CreateProjectRequest(
    val name: String,
    val framework: String? = null,
    val targets: List<String>? = null
)

@Serializable
data class UpdateProjectRequest(
    val name: String? = null,
    val framework: String? = null
)

@Serializable
data class AddTargetRequest(
    val target: String
)

@Serializable
data class VerifyEmailRequest(
    val token: String
)

@Serializable
data class ResendVerificationRequest(
    val email: String
)

@Serializable
data class CompleteOnboardingRequest(
    val organizationName: String,
    val companySize: String,
    val slug: String? = null
)

@Serializable
data class ForgotPasswordRequest(
    val email: String
)

@Serializable
data class ResetPasswordRequest(
    val token: String,
    val newPassword: String
)

@Serializable
data class CreateAuthTokenRequest(
    val name: String,
    val scopes: List<String>,
    val expiresInDays: Int? = null
)

@Serializable
data class AuthTokenResponse(
    val id: Int,
    val name: String,
    val token: String? = null, // Only returned on creation
    val scopes: List<String>,
    val lastUsedAt: String? = null,
    val expiresAt: String? = null,
    val createdAt: String
)

@Serializable
data class UpdateAuthTokenRequest(
    val name: String? = null,
    val scopes: List<String>? = null
)

@Serializable
data class CreateReleaseRequest(
    val version: String,
    val ref: String? = null,
    val projects: List<String>? = null
)

@Serializable
data class ReleaseResponse(
    val version: String,
    val ref: String? = null,
    val projectSlug: String,
    val dateCreated: String
)

@Serializable
data class UploadSourceMapRequest(
    val name: String,
    val file: String // Base64 encoded file content for simplicity
)

@Serializable
data class SourceMapFileResponse(
    val id: Int,
    val name: String,
    val dateCreated: String
)

@Serializable
data class ReleaseListResponse(
    val version: String,
    val firstSeen: String,
    val lastSeen: String,
    val eventCount: Long,
    val newIssueCount: Long,
    val crashFreeRate: Double? = null,
    val userCount: Long
)

@Serializable
data class ReleaseDetailStats(
    val version: String,
    val firstSeen: String,
    val lastSeen: String,
    val totalEvents: Long,
    val newIssues: Long,
    val resolvedIssues: Long,
    val crashFreeSessionRate: Double? = null,
    val crashFreeUserRate: Double? = null,
    val userCount: Long,
    val eventsTimeline: List<TimelinePoint>,
    val eventsByLevel: Map<String, Long>,
    val topIssues: List<TopIssue>
)

@Serializable
data class ReleaseMarker(
    val version: String,
    val timestamp: String
)

@Serializable
data class ReplayListItem(
    val replayId: String,
    val projectId: Long,
    val startedAt: String,
    val finishedAt: String,
    val durationMs: Double,
    val urls: List<String>,
    val errorCount: Int,
    val user: UserInfo?,
    val browserName: String?,
    val browserVersion: String?,
    val osName: String?,
    val osVersion: String?,
    val activity: Int
)

@Serializable
data class ReplayDetailResponse(
    val replayId: String,
    val projectId: Long,
    val startedAt: String,
    val finishedAt: String,
    val durationMs: Double,
    val urls: List<String>,
    val errorCount: Int,
    val errorIds: List<String>,
    val traceIds: List<String>,
    val segmentCount: Int,
    val environment: String?,
    val release: String?,
    val platform: String?,
    val user: UserInfo?,
    val browserName: String?,
    val browserVersion: String?,
    val osName: String?,
    val osVersion: String?,
    val activity: Int,
    val tags: Map<String, String>
)

@Serializable
data class ReplayRecordingResponse(
    val events: List<JsonElement>
)

@Serializable
data class ReplayTimelineItem(
    val id: String,
    val type: String,
    val timestamp: String,
    val offsetMs: Double,
    val title: String,
    val description: String? = null,
    val durationMs: Double? = null,
    val category: String? = null,
    val eventId: String? = null,
    val issueId: String? = null,
    val traceId: String? = null
)

@Serializable
data class ReplayTimelineResponse(
    val items: List<ReplayTimelineItem>,
    val replayStartMs: Long
)

@Serializable
data class FeedbackListItem(
    val feedbackId: String,
    val message: String,
    val contactEmail: String,
    val name: String,
    val url: String,
    val status: String,
    val timestamp: String,
    val environment: String,
    val release: String,
    val platform: String,
    val user: UserInfo?,
    val associatedEventId: String?,
    val replayId: String?
)

@Serializable
data class FeedbackDetailResponse(
    val feedbackId: String,
    val message: String,
    val contactEmail: String,
    val name: String,
    val url: String,
    val status: String,
    val timestamp: String,
    val environment: String,
    val release: String,
    val platform: String,
    val user: UserInfo?,
    val associatedEventId: String?,
    val replayId: String?,
    val tags: Map<String, String>,
    val sdkName: String,
    val sdkVersion: String
)

@Serializable
data class FeedbackUpdateRequest(
    val status: String? = null
)

@Serializable
data class TestNotificationRequest(
    val type: String,  // error_alert, weekly_summary, system_up, system_down, uptime_alert, verification, password_reset
    val channel: String,  // email, slack, both
    val testEmail: String? = null  // Optional test email address
)

@Serializable
data class TestNotificationResponse(
    val success: Boolean,
    val emailSent: Boolean,
    val slackSent: Boolean,
    val discordSent: Boolean = false,
    val errors: List<String> = emptyList()
)

@Serializable
data class TriggerIncidentRequest(
    val source: String,
    val severity: String,
    val title: String,
    val description: String
)

@Serializable
data class ErrorResponse(
    val error: String
)

@Serializable
data class SdkVersionsResponse(
    val fetchedAt: String,
    val cacheTtlSeconds: Int,
    val versions: Map<String, String>,
)

@Serializable
data class NotificationPreferencesData(
    val issueAlerts: Boolean,
    val errorAlerts: Boolean,
    val weeklySummary: Boolean,
    val alertFrequencyMinutes: Int
)

@Serializable
data class ProjectNotificationPreferences(
    val projectId: Long,
    val projectName: String,
    val issueAlerts: Boolean,
    val errorAlerts: Boolean,
    val weeklySummary: Boolean,
    val alertFrequencyMinutes: Int
)

@Serializable
data class NotificationPreferencesResponse(
    val global: NotificationPreferencesData,
    val projects: List<ProjectNotificationPreferences>
)

// Alert Notification Preferences Models

@Serializable
data class AlertNotificationPreference(
    val alertSource: String,
    val emailEnabled: Boolean,
    val slackEnabled: Boolean,
    val discordEnabled: Boolean
)

@Serializable
data class AlertNotificationPreferencesResponse(
    val preferences: List<AlertNotificationPreference>
)

@Serializable
data class UpdateAlertNotificationPreferenceRequest(
    val emailEnabled: Boolean,
    val slackEnabled: Boolean,
    val discordEnabled: Boolean
)

// Org Team Management Models

@Serializable
data class InviteMemberRequest(
    val email: String,
    val role: String = "member"
)

@Serializable
data class BulkInviteRequest(
    val emails: List<String>,
    val role: String = "member"
)

@Serializable
data class BulkInviteResult(
    val success: List<String>,
    val failed: List<BulkInviteFailure>
)

@Serializable
data class BulkInviteFailure(
    val email: String,
    val reason: String
)

@Serializable
data class InvitationResponse(
    val id: Int,
    val email: String,
    val role: String,
    val status: String,
    val invitedBy: String,
    val invitedByEmail: String,
    val createdAt: String,
    val expiresAt: String
)

@Serializable
data class OrgMemberResponse(
    val userId: Int,
    val email: String,
    val name: String?,
    val role: String,
    val joinedAt: String?
)

@Serializable
data class UpdateMemberRoleRequest(
    val role: String
)

@Serializable
data class AcceptInviteRequest(
    val token: String
)

@Serializable
data class InvitationDetailsResponse(
    val orgName: String,
    val role: String,
    val invitedBy: String,
    val expiresAt: String,
    val valid: Boolean
)

@Serializable
data class OrgMembersResponse(
    val members: List<OrgMemberResponse>,
    val pendingInvitations: List<InvitationResponse>
)
