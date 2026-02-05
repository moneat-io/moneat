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
    val name: String?
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
