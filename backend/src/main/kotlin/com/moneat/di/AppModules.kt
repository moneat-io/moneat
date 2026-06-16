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

package com.moneat.di

import com.moneat.alerts.services.AlertEpisodeService
import com.moneat.events.repositories.EventRepository
import com.moneat.events.repositories.EventRepositoryImpl
import com.moneat.events.repositories.IssueRepository
import com.moneat.events.repositories.IssueRepositoryImpl
import com.moneat.events.repositories.ProjectRepository
import com.moneat.events.repositories.ProjectRepositoryImpl
import com.moneat.events.services.DashboardQueryHelper
import com.moneat.events.services.DashboardService
import com.moneat.events.services.EventService
import com.moneat.events.services.ReleaseService
import com.moneat.notifications.services.AlertNotificationPreferencesService
import com.moneat.notifications.services.DiscordService
import com.moneat.notifications.services.EmailService
import com.moneat.notifications.services.NotificationService
import com.moneat.notifications.services.SlackService
import com.moneat.shared.repositories.MembershipRepository
import com.moneat.shared.repositories.MembershipRepositoryImpl
import com.moneat.shared.repositories.OrganizationRepository
import com.moneat.shared.repositories.OrganizationRepositoryImpl
import com.moneat.shared.services.ArtifactCleanupService
import com.moneat.shared.services.AttributionAnalyticsService
import com.moneat.shared.services.DemoLivenessBackgroundService
import com.moneat.shared.services.GeoIpService
import com.moneat.shared.services.ProjectIdResolver
import com.moneat.shared.services.RetentionBackgroundService
import com.moneat.shared.services.RetentionPolicyService
import com.moneat.shared.services.TraceFinalizerBackgroundService
import org.koin.dsl.module

/** Shared cross-domain singletons: notification channels, retention, shared repositories. */
val sharedModule = module {
    single<MembershipRepository> { MembershipRepositoryImpl() }
    single<OrganizationRepository> { OrganizationRepositoryImpl() }

    single { EmailService() }
    single { SlackService() }
    single { DiscordService() }
    single { AlertNotificationPreferencesService() }
    single { AlertEpisodeService() }

    single { RetentionPolicyService(get()) }
    single { RetentionBackgroundService(get()) }
    single { TraceFinalizerBackgroundService.fromConfig() }
    single { ProjectIdResolver() }

    single { AttributionAnalyticsService() }
    single { DemoLivenessBackgroundService() }
    single { GeoIpService() }
    single { ArtifactCleanupService(get(), get()) }
}

/** Core error-tracking and telemetry pipeline. */
val eventsModule = module {
    single<EventRepository> { EventRepositoryImpl() }
    single { DashboardQueryHelper(get(), get()) }
    single<IssueRepository> { IssueRepositoryImpl(get()) }
    single<ProjectRepository> {
        val qh = get<DashboardQueryHelper>()
        ProjectRepositoryImpl { col, days, orgId -> qh.timestampRetentionClause(col, days, orgId) }
    }

    single { ReleaseService() }
    single { NotificationService(get(), get()) }
    single { EventService(get(), get(), get()) }
    single { DashboardService(get(), get(), get(), get()) }
}

/** AI chat assistant. */
val aiModule = module {}

/** All application modules combined in load order. */
fun buildAppModules() =
    listOf(
        sharedModule,
        eventsModule,
        aiModule,
    )

val appModules = buildAppModules()
