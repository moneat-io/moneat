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

package com.moneat.otlp.models

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class CreateOtlpApiKeyRequest(
    val name: String
)

@Serializable
data class CreateOtlpApiKeyResponse(
    val id: Int,
    val name: String,
    @SerialName("key_prefix") val keyPrefix: String,
    @SerialName("key") val key: String,
    @SerialName("created_at") val createdAt: String
)

@Serializable
data class OtlpApiKeyResponse(
    val id: Int,
    val name: String,
    @SerialName("key_prefix") val keyPrefix: String,
    @SerialName("created_at") val createdAt: String,
    @SerialName("last_used_at") val lastUsedAt: String? = null
)
