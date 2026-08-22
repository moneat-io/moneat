// Moneat Enterprise - proprietary module
// Copyright (c) 2026 Moneat. All rights reserved.
// See ee/LICENSE for license terms.

package com.moneat.enterprise.alertroutes.context

import com.moneat.alerts.models.AlertLifecycleEvent
import com.moneat.alerts.services.AlertEpisodeContext
import com.moneat.enterprise.alertroutes.models.AlertRouteContextField
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import kotlin.uuid.Uuid

/**
 * Episode identity carried alongside an alert.
 *
 * Episodes are owned by the open-source alert lifecycle; alert routes read the identity and never
 * open, close, or renumber episodes themselves.
 */
data class AlertRouteEpisodeIdentity(
    val resourceId: String? = null,
    val key: String? = null,
    val sequence: Int? = null,
    val status: String? = null,
) {
    companion object {
        val UNKNOWN = AlertRouteEpisodeIdentity()

        fun from(episode: AlertEpisodeContext): AlertRouteEpisodeIdentity =
            AlertRouteEpisodeIdentity(
                resourceId = episode.resourceId.toString(),
                key = episode.episodeKey,
                sequence = episode.episodeSeq,
                status = episode.status,
            )
    }
}

/** Service, team, and catalog ownership attached to an alert. */
data class AlertRouteOwnership(
    val serviceName: String? = null,
    val teamId: Int? = null,
    val teamResourceId: Uuid? = null,
    val teamName: String? = null,
    val catalogResourceId: String? = null,
    val escalationPolicyId: Int? = null,
    val escalationPolicyResourceId: Uuid? = null,
) {
    companion object {
        val NONE = AlertRouteOwnership()
    }
}

/**
 * Normalized, source-neutral view of an alert used by every alert-route condition and template.
 *
 * Only approved metadata reaches this type; unapproved keys are dropped by
 * [AlertRouteMetadataPolicy] and reported in [droppedMetadataKeys] so previews can explain them.
 */
data class AlertRouteContext(
    val organizationId: Int,
    val source: String,
    val status: String,
    val priority: String,
    val title: String,
    val description: String,
    val deduplicationKey: String,
    val url: String,
    val episode: AlertRouteEpisodeIdentity = AlertRouteEpisodeIdentity.UNKNOWN,
    val metadata: Map<String, String> = emptyMap(),
    val ownership: AlertRouteOwnership = AlertRouteOwnership.NONE,
    val droppedMetadataKeys: List<String> = emptyList(),
) {
    fun value(field: AlertRouteContextField, metadataKey: String? = null): String? =
        when (field) {
            AlertRouteContextField.SOURCE -> source
            AlertRouteContextField.STATUS -> status
            AlertRouteContextField.PRIORITY -> priority
            AlertRouteContextField.TITLE -> title
            AlertRouteContextField.DESCRIPTION -> description
            AlertRouteContextField.DEDUPLICATION_KEY -> deduplicationKey
            AlertRouteContextField.URL -> url
            AlertRouteContextField.METADATA -> metadataKey?.let { metadata[it] }
            else -> episodeOrOwnershipValue(field)
        }?.takeIf { it.isNotBlank() }

    private fun episodeOrOwnershipValue(field: AlertRouteContextField): String? =
        when (field) {
            AlertRouteContextField.EPISODE_KEY -> episode.key
            AlertRouteContextField.EPISODE_SEQUENCE -> episode.sequence?.toString()
            AlertRouteContextField.EPISODE_STATUS -> episode.status
            AlertRouteContextField.SERVICE -> ownership.serviceName
            AlertRouteContextField.TEAM -> ownership.teamName
            AlertRouteContextField.CATALOG_RESOURCE -> ownership.catalogResourceId
            else -> null
        }

    /** Resolve a dotted reference such as `alert.title`, `episode.key`, or `metadata.host_id`. */
    fun reference(path: String): String? {
        val trimmed = path.trim()
        val (scope, leaf) = trimmed.split('.', limit = 2).let { it.first() to it.getOrNull(1) }
        return when (scope.lowercase()) {
            METADATA_SCOPE -> leaf?.let { metadata[it] }?.takeIf { it.isNotBlank() }
            OWNERSHIP_SCOPE -> leaf?.let { ownershipReference(it) }
            EPISODE_SCOPE -> leaf?.let { episodeReference(it) }
            ALERT_SCOPE -> leaf?.let { alertReference(it) }
            else -> alertReference(trimmed)
        }
    }

    private fun alertReference(leaf: String): String? =
        AlertRouteContextField.fromWire(leaf)
            ?.takeIf { it != AlertRouteContextField.METADATA }
            ?.let { value(it) }

    private fun ownershipReference(leaf: String): String? =
        when (leaf.lowercase()) {
            "service" -> ownership.serviceName
            "team" -> ownership.teamName
            "catalog_resource", "catalog" -> ownership.catalogResourceId
            else -> null
        }?.takeIf { it.isNotBlank() }

    private fun episodeReference(leaf: String): String? =
        when (leaf.lowercase()) {
            "id", "resource_id" -> episode.resourceId
            "key" -> episode.key
            "sequence" -> episode.sequence?.toString()
            "status" -> episode.status
            else -> null
        }?.takeIf { it.isNotBlank() }

    private companion object {
        const val METADATA_SCOPE = "metadata"
        const val OWNERSHIP_SCOPE = "ownership"
        const val EPISODE_SCOPE = "episode"
        const val ALERT_SCOPE = "alert"
    }
}

/** Approved metadata keys emitted by Moneat alert sources and safe to route or template on. */
object AlertRouteMetadataPolicy {
    val APPROVED_KEYS: Set<String> =
        setOf(
            "alert.condition",
            "alert.current_value",
            "alert.dashboard.title",
            "alert.display_title",
            "alert.threshold",
            "alert.widget.title",
            "catalog_resource_id",
            "cluster",
            "environment",
            "host_id",
            "monitor_id",
            "namespace",
            "region",
            "service",
            "service_name",
            "team",
            "triggered_by",
        )

    data class ApprovedMetadata(
        val values: Map<String, String>,
        val dropped: List<String>,
    )

    fun approve(metadata: Map<String, JsonElement>): ApprovedMetadata {
        val approved = LinkedHashMap<String, String>()
        val dropped = mutableListOf<String>()
        metadata.forEach { (key, element) ->
            val normalized = key.trim()
            if (normalized !in APPROVED_KEYS) {
                dropped.add(normalized)
                return@forEach
            }
            val text = element.asPlainText()
            if (text == null) {
                dropped.add(normalized)
                return@forEach
            }
            if (text.isNotBlank()) approved[normalized] = text
        }
        return ApprovedMetadata(approved, dropped.sorted())
    }

    private fun JsonElement.asPlainText(): String? = (this as? JsonPrimitive)?.content
}

/** A hand-supplied alert used to preview routes without waiting for a real alert. */
data class AlertRouteSampleInput(
    val source: String,
    val status: String = "FIRING",
    val priority: String = "P3",
    val title: String = "",
    val description: String = "",
    val deduplicationKey: String = "",
    val url: String = "",
    val metadata: Map<String, String> = emptyMap(),
    val episode: AlertRouteEpisodeIdentity = AlertRouteEpisodeIdentity.UNKNOWN,
)

/** Builds the normalized route context from the shared alert lifecycle event. */
object AlertRouteContextFactory {
    fun fromLifecycleEvent(
        event: AlertLifecycleEvent,
        episode: AlertRouteEpisodeIdentity = AlertRouteEpisodeIdentity.UNKNOWN,
        ownership: AlertRouteOwnership = AlertRouteOwnership.NONE,
    ): AlertRouteContext {
        val approved = AlertRouteMetadataPolicy.approve(event.metadata)
        return AlertRouteContext(
            organizationId = event.organizationId,
            source = event.source.name,
            status = event.status.name,
            priority = event.priority.wire,
            title = event.title,
            description = event.description,
            deduplicationKey = event.deduplicationKey,
            url = event.moneatUrl,
            episode = episode,
            metadata = approved.values,
            ownership = ownership.mergedWith(approved.values),
            droppedMetadataKeys = approved.dropped,
        )
    }

    fun fromSample(
        organizationId: Int,
        sample: AlertRouteSampleInput,
        ownership: AlertRouteOwnership = AlertRouteOwnership.NONE,
    ): AlertRouteContext {
        val approved = AlertRouteMetadataPolicy.approve(sample.metadata.mapValues { JsonPrimitive(it.value) })
        return AlertRouteContext(
            organizationId = organizationId,
            source = sample.source,
            status = sample.status,
            priority = sample.priority,
            title = sample.title,
            description = sample.description,
            deduplicationKey = sample.deduplicationKey,
            url = sample.url,
            episode = sample.episode,
            metadata = approved.values,
            ownership = ownership.mergedWith(approved.values),
            droppedMetadataKeys = approved.dropped,
        )
    }

    private fun AlertRouteOwnership.mergedWith(metadata: Map<String, String>): AlertRouteOwnership =
        copy(
            serviceName = serviceName ?: metadata["service"] ?: metadata["service_name"],
            teamName = teamName ?: metadata["team"],
            catalogResourceId = catalogResourceId ?: metadata["catalog_resource_id"],
        )
}
