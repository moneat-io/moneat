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

import com.moneat.ai.AiChatService
import com.moneat.analytics.services.AnalyticsService
import com.moneat.analytics.services.GeoIpService
import com.moneat.analytics.services.SessionHashService
import com.moneat.auth.repositories.UserRepository
import com.moneat.auth.repositories.UserRepositoryImpl
import com.moneat.auth.services.AccountDeletionService
import com.moneat.auth.services.AuthService
import com.moneat.auth.services.AuthTokenService
import com.moneat.auth.services.OAuthService
import com.moneat.auth.services.RefreshTokenCleanupService
import com.moneat.auth.services.RefreshTokenService
import com.moneat.billing.repositories.SubscriptionRepository
import com.moneat.billing.repositories.SubscriptionRepositoryImpl
import com.moneat.billing.services.AdminBillingService
import com.moneat.billing.services.BillingBackgroundService
import com.moneat.billing.services.BillingQuotaService
import com.moneat.billing.services.EntitlementService
import com.moneat.billing.services.PricingTierService
import com.moneat.billing.services.StripeService
import com.moneat.dashboards.repositories.DashboardFolderRepository
import com.moneat.dashboards.repositories.DashboardFolderRepositoryImpl
import com.moneat.dashboards.repositories.DashboardRepository
import com.moneat.dashboards.repositories.DashboardRepositoryImpl
import com.moneat.dashboards.repositories.DashboardWidgetRepository
import com.moneat.dashboards.repositories.DashboardWidgetRepositoryImpl
import com.moneat.dashboards.services.CustomDashboardService
import com.moneat.dashboards.services.CustomDataSourceExecutor
import com.moneat.dashboards.services.CustomDataSourceService
import com.moneat.dashboards.services.DashboardAlertService
import com.moneat.dashboards.services.DashboardQueryEngine
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
import com.moneat.incident.services.IncidentService
import com.moneat.llm.services.LlmDashboardService
import com.moneat.logs.repositories.LogRepository
import com.moneat.logs.repositories.LogRepositoryImpl
import com.moneat.logs.services.LogIndexService
import com.moneat.logs.services.LogService
import com.moneat.monitor.repositories.HostAlertRepository
import com.moneat.monitor.repositories.HostAlertRepositoryImpl
import com.moneat.monitor.repositories.HostRepository
import com.moneat.monitor.repositories.HostRepositoryImpl
import com.moneat.monitor.services.AgentApiKeyService
import com.moneat.monitor.services.MonitorAlertService
import com.moneat.monitor.services.MonitorService
import com.moneat.notifications.services.AlertNotificationPreferencesService
import com.moneat.notifications.services.DiscordService
import com.moneat.notifications.services.EmailService
import com.moneat.notifications.services.NotificationService
import com.moneat.notifications.services.SlackService
import com.moneat.org.repositories.OrgInvitationRepository
import com.moneat.org.repositories.OrgInvitationRepositoryImpl
import com.moneat.org.repositories.OrgMembershipRepository
import com.moneat.org.repositories.OrgMembershipRepositoryImpl
import com.moneat.org.services.AdminService
import com.moneat.org.services.OrgInvitationService
import com.moneat.org.services.OrgMembershipService
import com.moneat.otlp.services.OtlpApiKeyService
import com.moneat.shared.repositories.MembershipRepository
import com.moneat.shared.repositories.MembershipRepositoryImpl
import com.moneat.shared.repositories.OrganizationRepository
import com.moneat.shared.repositories.OrganizationRepositoryImpl
import com.moneat.shared.services.ArtifactCleanupService
import com.moneat.shared.services.AttributionAnalyticsService
import com.moneat.shared.services.RetentionBackgroundService
import com.moneat.shared.services.RetentionPolicyService
import com.moneat.statuspage.services.StatusPageService
import com.moneat.summary.services.SummaryService
import com.moneat.synthetics.routes.SyntheticsService
import com.moneat.uptime.repositories.UptimeMonitorRepository
import com.moneat.uptime.repositories.UptimeMonitorRepositoryImpl
import com.moneat.uptime.services.UptimeCheckExecutor
import com.moneat.uptime.services.UptimeScheduler
import com.moneat.uptime.services.UptimeService
import org.koin.dsl.module

/** Shared cross-domain singletons: notification channels, pricing, retention, shared repositories. */
val sharedModule = module {
    single<MembershipRepository> { MembershipRepositoryImpl() }
    single<OrganizationRepository> { OrganizationRepositoryImpl() }

    single { EmailService() }
    single { SlackService() }
    single { DiscordService() }
    single { AlertNotificationPreferencesService() }
    single { IncidentService() }

    single { PricingTierService() }
    single { BillingQuotaService(get()) }
    single { EntitlementService(get()) }
    single { RetentionPolicyService(get()) }
    single { RetentionBackgroundService(get()) }

    single { AttributionAnalyticsService() }
}

/** Authentication, token management, and account lifecycle. */
val authModule = module {
    single<UserRepository> { UserRepositoryImpl() }

    single { OAuthService() }
    single { AuthTokenService() }
    single { RefreshTokenService() }
    single { RefreshTokenCleanupService() }
    single {
        AuthService(
            userRepository = get(),
            membershipRepository = get(),
            organizationRepository = get(),
            emailService = get(),
            refreshTokenService = get(),
        )
    }
    single { AccountDeletionService(get(), get()) }
    single { ArtifactCleanupService(get(), get()) }
}

/** Billing, subscriptions, and Stripe integration. */
val billingModule = module {
    single<SubscriptionRepository> { SubscriptionRepositoryImpl() }

    single {
        StripeService(
            subscriptionRepository = get(),
            organizationRepository = get(),
            pricingTierService = get(),
        )
    }
    single {
        BillingBackgroundService(
            stripeService = get(),
            quotaService = get(),
            emailService = get(),
            pricingTierService = get(),
        )
    }
    single { AdminBillingService(get()) }
}

/** Organization membership, invitations, and admin operations. */
val orgModule = module {
    single<OrgMembershipRepository> { OrgMembershipRepositoryImpl() }
    single<OrgInvitationRepository> { OrgInvitationRepositoryImpl() }

    single { OrgMembershipService(get()) }
    single { OrgInvitationService(get(), get(), get()) }
    single { AdminService(get()) }
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
    single { NotificationService(get(), get(), get()) }
    single { EventService(get(), get(), get()) }
    single { DashboardService(get(), get(), get()) }
}

/** Infrastructure monitoring and alerting. */
val monitorModule = module {
    single<HostRepository> { HostRepositoryImpl() }
    single<HostAlertRepository> { HostAlertRepositoryImpl() }

    single { MonitorService(get(), get(), get(), get()) }
    single { MonitorAlertService(get(), get(), get(), get()) }
    single { AgentApiKeyService() }
    single { SyntheticsService(get(), get(), get(), get(), get()) }
}

/** Log ingestion and querying. */
val logsModule = module {
    single<LogRepository> { LogRepositoryImpl() }

    single { LogService(get()) }
    single { OtlpApiKeyService() }
    single { LogIndexService() }
}

/** Uptime monitoring and status pages. */
val uptimeModule = module {
    single<UptimeMonitorRepository> { UptimeMonitorRepositoryImpl() }

    single { UptimeService(get(), get()) }
    single { UptimeCheckExecutor() }
    single { UptimeScheduler(get(), get(), get(), get(), get(), get(), get(), get()) }
    single { StatusPageService(get()) }
}

/** Custom dashboards, alert evaluation, and external data sources. */
val dashboardsModule = module {
    single<DashboardFolderRepository> { DashboardFolderRepositoryImpl() }
    single<DashboardRepository> { DashboardRepositoryImpl() }
    single<DashboardWidgetRepository> { DashboardWidgetRepositoryImpl() }

    single { DashboardQueryEngine() }
    single {
        DashboardAlertService(
            emailService = get(),
            slackService = get(),
            discordService = get(),
            incidentService = get(),
            prefsService = get(),
            queryEngine = get(),
            retentionPolicyService = get(),
            dataSourceService = get(),
            dataSourceExecutor = get(),
        )
    }
    single {
        CustomDashboardService(
            folderRepository = get(),
            dashboardRepository = get(),
            dashboardWidgetRepository = get(),
            // Simplified projection without retention — only needed for the custom dashboard builder
            projectRepository = ProjectRepositoryImpl { col, _, _ -> col },
        )
    }
    single { CustomDataSourceService() }
    single { CustomDataSourceExecutor() }
}

/** Weekly/overnight summary reports. */
val summaryModule = module {
    single { SummaryService(get(), get(), get(), get(), get()) }
}

/** LLM observability. */
val llmModule = module {
    single { LlmDashboardService() }
}

/** Product analytics (cookie-free web analytics). */
val analyticsModule = module {
    single { AnalyticsService() }
    single { SessionHashService() }
    single { GeoIpService() }
}

/** AI chat assistant. */
val aiModule = module {
    single { AiChatService() }
}

/** All application modules combined in load order. */
val appModules = listOf(
    sharedModule,
    authModule,
    billingModule,
    orgModule,
    eventsModule,
    monitorModule,
    logsModule,
    uptimeModule,
    dashboardsModule,
    summaryModule,
    llmModule,
    analyticsModule,
    aiModule,
)
