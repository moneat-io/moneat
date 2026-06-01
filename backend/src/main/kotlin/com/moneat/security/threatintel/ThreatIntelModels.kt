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

package com.moneat.security.threatintel

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class ThreatIntelSnapshot(
    val feeds: List<ThreatIntelFeed> = emptyList(),
)

@Serializable
data class ThreatIntelFeed(
    val name: String,
    val source: String,
    @SerialName("updated_at") val updatedAt: String,
    val indicators: List<ThreatIntelIndicator> = emptyList(),
)

@Serializable
data class ThreatIntelIndicator(
    val type: String,
    val value: String,
    @SerialName("threat_type") val threatType: String,
    val confidence: Int,
    val reference: String = "",
)

@Serializable
data class ThreatIntelEnrichmentResponse(
    @SerialName("entity_key") val entityKey: String,
    @SerialName("entity_value") val entityValue: String,
    @SerialName("indicator_type") val indicatorType: String,
    @SerialName("feed_name") val feedName: String,
    val source: String,
    @SerialName("threat_type") val threatType: String,
    val confidence: Int,
    val reference: String = "",
    @SerialName("updated_at") val updatedAt: String,
)
