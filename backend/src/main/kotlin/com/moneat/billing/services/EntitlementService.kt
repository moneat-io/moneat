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

package com.moneat.billing.services

import com.moneat.billing.models.PricingTierConfigResponse
import com.moneat.config.EnvConfig

class EntitlementService(
    private val pricingTierService: PricingTierService
) {
    fun requireFeatureEnabled(
        organizationId: Int,
        featureCheck: (PricingTierConfigResponse) -> Boolean,
        featureName: String
    ) {
        unavailableFeatureMessage(organizationId, featureCheck, featureName)?.let {
            throw FeatureNotAvailableException(it)
        }
    }

    fun isFeatureEnabled(
        organizationId: Int,
        featureCheck: (PricingTierConfigResponse) -> Boolean
    ): Boolean {
        if (EnvConfig.SelfHost.enabled) return true
        val tier = pricingTierService.getEffectiveTierForOrganization(organizationId).tier
        return featureCheck(tier)
    }

    fun unavailableFeatureMessage(
        organizationId: Int,
        featureCheck: (PricingTierConfigResponse) -> Boolean,
        featureName: String
    ): String? {
        if (EnvConfig.SelfHost.enabled) return null
        val tier = pricingTierService.getEffectiveTierForOrganization(organizationId).tier
        return if (featureCheck(tier)) null else "$featureName is not available on your current plan"
    }
}

class FeatureNotAvailableException(message: String) : IllegalArgumentException(message)
