package com.moneat.models

import kotlinx.serialization.Serializable

@Serializable
data class SignupRequest(
    val email: String,
    val password: String,
    val name: String?
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
    val onboardingCompleted: Boolean = false
)

@Serializable
data class ProjectResponse(
    val id: Long,
    val name: String,
    val slug: String,
    val platform: String?,
    val dsn: String
)

@Serializable
data class IssueResponse(
    val id: String,
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
    val tags: Map<String, String>,
    val contexts: String,
    val exception: String?,
    val breadcrumbs: String?
)

@Serializable
data class ProjectStatsResponse(
    val totalEvents: Long,
    val totalIssues: Long,
    val eventsToday: Long,
    val timeline: List<TimelinePoint>
)

@Serializable
data class TimelinePoint(
    val timestamp: String,
    val count: Long
)

@Serializable
data class IssueUpdateRequest(
    val status: String? = null
)

@Serializable
data class CreateProjectRequest(
    val name: String,
    val platform: String? = null
)

@Serializable
data class UpdateProjectRequest(
    val name: String? = null,
    val platform: String? = null
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
    val companySize: String
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
