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

import {isDemo, setDemoEpoch} from './demo'

const API_BASE = `${import.meta.env.VITE_BACKEND_URL || ''}/v1`
const AUTH_PAGE_PATHS = new Set(['/', '/login', '/signup', '/verify-email', '/forgot-password', '/reset-password'])

// Helper to format errors for logging without massive stack traces
export function formatErrorForLogging(error: unknown): string {
  if (error instanceof Error) {
    if (error.message === 'NETWORK_ERROR') {
      return 'Network error: Unable to connect to server'
    }
    return error.message
  }
  return String(error)
}

interface AuthResponse {
  token: string
  refreshToken?: string
  expiresIn?: number
  user: { 
    id: number
    email: string
    name?: string
    emailVerified: boolean
    onboardingCompleted: boolean
  }
}

interface SignupLegalConsent {
  acceptTerms: boolean
  acceptPrivacy: boolean
  termsVersion: string
  privacyVersion: string
}

interface ProjectKey {
  platformTarget: string | null
  dsn: string
}

interface Project {
  id: number
  name: string
  slug: string
  framework?: string
  keys: ProjectKey[]
  dsn: string  // First key's DSN for backward compatibility
  issueCount?: number
}

interface Issue {
  id: string
  projectId: number
  title: string
  culprit: string
  level: string
  platform: string
  firstSeen: string
  lastSeen: string
  eventCount: number
  userCount: number
  status: string
}

interface IssueDetail extends Issue {
  projectName?: string
  fingerprint: string[]
  latestEvent?: Event
}

interface Event {
  eventId: string
  timestamp: string
  message: string
  platform: string
  level: string
  environment?: string
  release?: string
  user?: {
    id: string
    email?: string
    username?: string
  }
  tags: Record<string, string>
  contexts: string
  exception?: string
  breadcrumbs?: string
}

interface TimelinePoint {
  timestamp: string
  count: number
}

interface TransactionSummary {
  name: string
  op: string
  latestEventId?: string
  count: number
  p50: number
  p75: number
  p95: number
  failureRate: number
  tpm: number
}

interface IssueTransaction {
  eventId: string
  name: string
  op: string
  duration: number
  timestamp: string
  status?: string | null
  traceId?: string | null
}

interface TransactionDetail {
  eventId: string
  name: string
  op: string
  startTimestamp: number
  duration: number
  traceId: string
  timestamp: string
  environment?: string
  release?: string
  status?: string
  tags: Record<string, string>
  contexts: string
  breadcrumbs?: string
  request?: string
}

interface Span {
  spanId: string
  parentSpanId?: string | null
  traceId?: string | null
  transactionId?: string | null
  op: string
  description: string
  startTimestamp: number
  endTimestamp: number
  duration: number
  status?: string
  tags: Record<string, string>
  data?: string | null
}

interface TransactionWithSpans {
  transaction: TransactionDetail
  spans: Span[]
}

interface TraceDetail {
  traceId: string
  projectId: number
  spans: Span[]
  startTimestamp: number
  endTimestamp: number
  duration: number
}

interface SpanDetail {
  span: Span
  transaction: TransactionDetail | null
}

interface SlowTransaction {
  eventId: string
  name: string
  op: string
  duration: number
  timestamp: string
}

interface PerformanceStats {
  apdex: number
  throughput: TimelinePoint[]
  slowestTransactions: SlowTransaction[]
  totalTransactions: number
  avgDuration: number
}

interface TopIssue {
  issueId: string
  title: string
  count: number
}

interface ProjectStats {
  totalEvents: number
  totalIssues: number
  unresolvedIssues: number
  affectedUsers: number
  eventsTimeline: TimelinePoint[]
  eventsByLevel: Record<string, number>
  eventsByPlatform: Record<string, number>
  eventsByBrowser: Record<string, number>
  eventsByEnvironment: Record<string, number>
  issuesByStatus: Record<string, number>
  topIssues: TopIssue[]
  usersTimeline: TimelinePoint[]
  releaseMarkers?: { version: string; timestamp: string }[]
}

interface Release {
  version: string
  firstSeen: string
  lastSeen: string
  eventCount: number
  newIssueCount: number
  crashFreeRate: number | null
  userCount: number
}

interface ReleaseStats {
  version: string
  firstSeen: string
  lastSeen: string
  totalEvents: number
  newIssues: number
  resolvedIssues: number
  crashFreeSessionRate: number | null
  crashFreeUserRate: number | null
  userCount: number
  eventsTimeline: TimelinePoint[]
  eventsByLevel: Record<string, number>
  topIssues: TopIssue[]
}

interface Replay {
  replayId: string
  projectId: number
  startedAt: string
  finishedAt: string
  durationMs: number
  urls: string[]
  errorCount: number
  user?: { id?: string; email?: string; username?: string }
  browserName?: string
  browserVersion?: string
  osName?: string
  osVersion?: string
  activity: number
}

interface ReplayDetail extends Replay {
  errorIds: string[]
  traceIds: string[]
  segmentCount: number
  environment?: string
  release?: string
  platform?: string
  tags: Record<string, string>
}

interface ReplayRecordingResponse {
  events: unknown[]
}

export interface Feedback {
  feedbackId: string
  message: string
  contactEmail: string
  name: string
  url: string
  status: string
  timestamp: string
  environment: string
  release: string
  platform: string
  user?: { id?: string; email?: string; username?: string }
  associatedEventId?: string | null
  replayId?: string | null
}

export interface FeedbackDetail extends Feedback {
  tags: Record<string, string>
  sdkName: string
  sdkVersion: string
}

interface ReplayTimelineItem {
  id: string
  type: 'error' | 'transaction' | 'span'
  timestamp: string
  offsetMs: number
  title: string
  description?: string
  durationMs?: number
  category?: string
  eventId?: string
  issueId?: string
  traceId?: string
}

interface NotificationPreference {
  issueAlerts: boolean
  errorAlerts: boolean
  weeklySummary: boolean
  alertFrequencyMinutes: number
}

interface ProjectNotificationPreference extends NotificationPreference {
  projectId: number
  projectName: string
}

interface NotificationPreferences {
  global: NotificationPreference
  projects: ProjectNotificationPreference[]
}

export type AlertSource = 'SYSTEM_ALERT' | 'SYSTEM_DOWN' | 'UPTIME_MONITOR' | 'ERROR_ALERT'

export interface AlertNotificationPreference {
  alertSource: AlertSource
  emailEnabled: boolean
  slackEnabled: boolean
  discordEnabled: boolean
}

interface AlertNotificationPreferencesResponse {
  preferences: AlertNotificationPreference[]
}

interface ReplayTimelineResponse {
  items: ReplayTimelineItem[]
  replayStartMs: number
}

interface LogEntry {
  logId: string
  timestamp: string
  level: string
  message: string
  body: string
  service: string
  environment: string
  host: string
  source: string
  containerName: string
  containerId: string
  containerImage: string
  traceId: string
  spanId: string
  tags: Record<string, string>
  resourceAttributes: Record<string, string>
}

interface LogQueryResponse {
  logs: LogEntry[]
  nextCursor?: string | null
  hasMore: boolean
  totalCount?: number | null
}

interface LogFilterOptions {
  services: string[]
  environments: string[]
  levels: string[]
  tagKeys: string[]
}

interface LogFilterOptionWithCount {
  value: string
  count: number
}

interface LogFilterOptionsWithCounts {
  services: LogFilterOptionWithCount[]
  environments: LogFilterOptionWithCount[]
  levels: string[]
  tagKeys: string[]
}

interface LogAggregateBucket {
  timestamp: string
  count: number
  groups: Record<string, number>
}

interface LogAggregateResponse {
  buckets: LogAggregateBucket[]
  totalCount: number
  interval: string
}

interface LogTopValue {
  value: string
  count: number
}

interface LogTopResponse {
  field: string
  values: LogTopValue[]
  totalCount: number
}

interface RawLogResponse {
  logs?: Record<string, unknown>[]
  nextCursor?: string | null
  next_cursor?: string | null
  hasMore?: boolean
  has_more?: boolean
}

interface RawLogFilterResponse {
  services?: (string | { value: string; count: number })[]
  environments?: (string | { value: string; count: number })[]
  levels?: string[]
  tagKeys?: string[]
  tag_keys?: string[]
}

interface RawLogAggregateResponse {
  buckets?: { timestamp: string; count?: number; groups?: Record<string, number> }[]
  total_count?: number
  totalCount?: number
  interval?: string
}

interface RawLogTopResponse {
  field?: string
  values?: { value: string; count?: number }[]
  total_count?: number
  totalCount?: number
}

interface RawSystemAlertConfigResponse {
  scope?: string
  globalAlerts?: Record<string, unknown>[]
  global_alerts?: Record<string, unknown>[]
  systemAlerts?: Record<string, unknown>[]
  system_alerts?: Record<string, unknown>[]
  effectiveAlerts?: Record<string, unknown>[]
  effective_alerts?: Record<string, unknown>[]
}

interface SdkVersionsResponse {
  fetchedAt: string
  cacheTtlSeconds: number
  versions: Record<string, string>
}

interface AuthToken {
  id: number
  name: string
  token?: string | null
  scopes: string[]
  lastUsedAt?: string | null
  expiresAt?: string | null
  createdAt: string
}

interface OrganizationAccountSettings {
  id: number
  name: string
  role: string
}

interface AccountDeletionValidation {
  canDelete: boolean
  error?: string | null
  organizationsAsLastOwner: string[]
}

interface OrganizationDeletionValidation {
  canDelete: boolean
  error?: string | null
}

interface AdminAttributionMetrics {
  source: string | null
  medium: string | null
  campaign: string | null
  signups: number
  paidOrganizations: number
  conversionRate: number
  totalMrr: string
  averageMrr: string
  estimatedLtv: string
}

interface AdminAttributionSummary {
  totalSignups: number
  totalPaidOrganizations: number
  overallConversionRate: number
  totalMrr: string
}

interface AdminAttributionResponse {
  metrics: AdminAttributionMetrics[]
  summary: AdminAttributionSummary
}

interface BillingTierConfig {
  id: number
  tierName: string
  version: number
  monthlyUnitLimit: number
  monthlyErrorLimit: number
  monthlyTransactionLimit: number
  monthlyReplayLimit: number
  monthlyFeedbackLimit: number
  monthlyLlmEventLimit: number
  logRetentionDays: number
  retentionDays: number
  replayRetentionDays: number
  llmRetentionDays: number
  statusPagesEnabled: boolean
  statusPageCustomDomainEnabled: boolean
  sessionReplayEnabled: boolean
  slackEnabled: boolean
  discordEnabled: boolean
  incidentIoEnabled: boolean
  samlEnabled: boolean
  oidcEnabled: boolean
  prioritySupportEnabled: boolean
  slaEnabled: boolean
  customRetentionEnabled: boolean
  maxProjects: number | null
  maxSystems: number
  monitorIntervalSeconds: number
  monthlyPriceCents: number
  yearlyPriceCents: number
  trialDays: number
  monthlyGbLimit: number
  paygEnabled: boolean
  paygRateMicrosPerUnit: number
  overageRateCentsPerGb: number
  errorOverageRateCentsPer1k?: number
  replayOverageRateCentsPerGb?: number
  llmOverageRateCentsPer1k?: number
  stripeBasePriceId?: string | null
  stripeOveragePriceId?: string | null
  stripeYearlyBasePriceId?: string | null
  stripeYearlyOveragePriceId?: string | null
  stripeOncallPriceId?: string | null
  stripeOncallYearlyPriceId?: string | null
  oncallPerUserMonthlyCents?: number
  oncallPerUserYearlyCents?: number
  oncallEnabled?: boolean
  isCurrent: boolean
}

interface BillingPlan {
  tier: BillingTierConfig
  trialDays: number
}

interface BillingPlansResponse {
  plans: BillingPlan[]
  stripeEnabled: boolean
  publishableKey?: string | null
}

interface BillingUsage {
  organizationId: number
  periodStart: string
  periodEnd: string
  retentionDays: number
  logRetentionDays?: number
  replayRetentionDays?: number
  llmRetentionDays?: number
  usedUnits: number
  usedErrors: number
  errorLimit: number
  usedTransactions: number
  transactionLimit: number
  usedReplays: number
  replayLimit: number
  usedFeedback: number
  feedbackLimit: number
  usedLlmEvents?: number
  llmEventLimit?: number
  usedLogs?: number
  usedBytes: number
  usedErrorBytes?: number
  usedReplayBytes?: number
  usedLogBytes?: number
  usedLlmBytes?: number
  bytesLimit: number
  baseLimitUnits: number
  paygLimitUnits: number
  totalLimitUnits: number
  paygBudgetCents: number
  paygUsedUnits: number
  paygUsedCentsEstimate: number
  errorOverageCentsEstimate?: number
  replayOverageCentsEstimate?: number
  logOverageCentsEstimate?: number
  llmOverageCentsEstimate?: number
  totalOverageCentsEstimate?: number
  errorOverageRateCentsPer1k?: number
  replayOverageRateCentsPerGb?: number
  logOverageRateCentsPerGb?: number
  llmOverageRateCentsPer1k?: number
  oncallSeats?: number
  oncallUsedSeats?: number
  oncallPerUserMonthlyCents?: number
  oncallEnabled?: boolean
  plan: string
  status: string
  withinQuota: boolean
  bonusGbBytes?: number
  bonusUnits?: number
  bonusReason?: string
}

interface CheckoutSessionRequest {
  tierName: string
  billingInterval?: string  // 'monthly' or 'yearly'
  successUrl: string
  cancelUrl: string
  oncallSeats?: number
}

interface CheckoutSessionResponse {
  sessionId: string
  url: string
}

interface Invoice {
  id: string
  date: string
  amountCents: number
  status: string
  pdfUrl?: string | null
}

interface PaymentMethod {
  brand?: string | null
  last4?: string | null
  expMonth?: number | null
  expYear?: number | null
}

interface SetupIntentResponse {
  clientSecret: string
}

interface CancelSubscriptionResponse {
  status: string
  cancelAtPeriodEnd: boolean
  currentPeriodEnd?: string | null
}

interface AdminBillingSubscription {
  subscriptionId: number
  organizationId: number
  organizationName: string
  plan: string
  status: string
  pricingTierConfigId?: number | null
  paygBudgetCents: number
  paygUsedUnits: number
  paygUsedMicros: number
  pendingMeterUnits: number
  currentPeriodStart?: string | null
  currentPeriodEnd?: string | null
}

interface CreateTierVersionRequest {
  monthlyUnitLimit: number
  monthlyErrorLimit: number
  monthlyTransactionLimit: number
  monthlyReplayLimit: number
  monthlyFeedbackLimit: number
  monthlyLlmEventLimit?: number
  monthlyGbLimit?: number | null
  retentionDays: number
  logRetentionDays?: number | null
  replayRetentionDays?: number | null
  llmRetentionDays?: number | null
  statusPagesEnabled?: boolean | null
  statusPageCustomDomainEnabled?: boolean | null
  sessionReplayEnabled?: boolean | null
  slackEnabled?: boolean | null
  discordEnabled?: boolean | null
  incidentIoEnabled?: boolean | null
  samlEnabled?: boolean | null
  oidcEnabled?: boolean | null
  prioritySupportEnabled?: boolean | null
  slaEnabled?: boolean | null
  customRetentionEnabled?: boolean | null
  maxProjects?: number | null
  maxSystems: number
  monitorIntervalSeconds: number
  monthlyPriceCents: number
  yearlyPriceCents?: number | null
  trialDays?: number | null
  paygEnabled: boolean
  paygRateMicrosPerUnit: number
  overageRateCentsPerGb?: number | null
  errorOverageRateCentsPer1k?: number | null
  replayOverageRateCentsPerGb?: number | null
  llmOverageRateCentsPer1k?: number | null
  oncallPerUserMonthlyCents?: number | null
  oncallPerUserYearlyCents?: number | null
  oncallEnabled?: boolean | null
  stripeBasePriceId?: string | null
  stripeOveragePriceId?: string | null
  stripeYearlyBasePriceId?: string | null
  stripeYearlyOveragePriceId?: string | null
  stripeOncallPriceId?: string | null
  stripeOncallYearlyPriceId?: string | null
}

interface TierMigrationResponse {
  tierName: string
  targetVersion: number
  affectedSubscriptions: number
  dryRun: boolean
}

interface UpdateStripePriceIdsRequest {
  stripeBasePriceId?: string | null
  stripeOveragePriceId?: string | null
  stripeYearlyBasePriceId?: string | null
  stripeYearlyOveragePriceId?: string | null
  stripeOncallPriceId?: string | null
  stripeOncallYearlyPriceId?: string | null
}

// Monitoring types
interface MonitorSystem {
  id: string
  projectId?: number
  name: string
  host?: string
  status: 'up' | 'down' | 'pending'
  lastSeenAt?: string | number
  agentVersion?: string
  os?: string
  arch?: string
  createdAt?: string | number
  updatedAt?: string | number
}

interface LatestMetrics {
  cpu_percent: number
  mem_total: number
  mem_used: number
  mem_percent: number
  disk_total: number
  disk_used: number
  disk_percent: number
  net_recv_bytes: number
  net_sent_bytes: number
  net_recv_mbps?: number | null
  net_sent_mbps?: number | null
  load_1: number
  temp_max?: number | null
  gpu_percent?: number | null
  battery_percent?: number | null
}

interface MonitorSystemWithMetrics extends MonitorSystem {
  cpuPercent?: number
  memTotal?: number
  memUsed?: number
  memAvailable?: number
  diskTotal?: number
  diskUsed?: number
  load1?: number
  load5?: number
  load15?: number
  netRecvBytes?: number
  netSentBytes?: number
  tempMax?: number
  gpuPercent?: number
  batteryPercent?: number
  latest_metrics?: LatestMetrics
}

interface MonitorSystemDetail extends MonitorSystemWithMetrics {
  agentKey?: string
}

interface CreateMonitorSystemResponse {
  system: {
    id: string
    name: string
    host?: string
    status: string
    last_seen_at?: number
    agent_version?: string
    os?: string
    arch?: string
    created_at: number
    latest_metrics?: LatestMetrics
  }
  agent_key: string
  docker_command: string
}

interface HistoricalDataPoint {
  timestamp: number
  cpu_percent?: number
  mem_percent?: number
  disk_percent?: number
  net_recv_bytes?: number
  net_sent_bytes?: number
  load_1?: number
  load_5?: number
  load_15?: number
  temp_max?: number
  gpu_percent?: number
  battery_percent?: number
}

interface SystemMetricsHistory {
  data_points: HistoricalDataPoint[]
}

interface ContainerStats {
  name: string
  id: string
  image: string
  status: string
  cpuPercent?: number
  memUsed?: number
  memLimit?: number
  netRecvBytes?: number
  netSentBytes?: number
}

interface RawContainerStats {
  name: string
  id: string
  image: string
  status: string
  cpuPercent?: number
  cpu_percent?: number
  memUsed?: number
  mem_used?: number
  memLimit?: number
  mem_limit?: number
  netRecvBytes?: number
  net_recv_bytes?: number
  netSentBytes?: number
  net_sent_bytes?: number
}

interface ContainerHistoricalDataPoint {
  timestamp: number
  cpu_percent: number
  mem_used: number
  mem_limit: number
  net_recv_bytes: number
  net_sent_bytes: number
}

interface ContainerMetricsHistory {
  data_points: ContainerHistoricalDataPoint[]
}

interface SystemAlert {
  id: number
  systemId?: string
  scope: 'global' | 'system'
  metric: string
  condition: string
  threshold: number
  durationSeconds: number
  enabled: boolean
  incidentSeverity?: string | null
  lastTriggeredAt?: number
  createdAt: number
}

interface SystemAlertConfig {
  scope: 'global' | 'system'
  globalAlerts: SystemAlert[]
  systemAlerts: SystemAlert[]
  effectiveAlerts: SystemAlert[]
}

// Alert Silence Period Interfaces

interface SilencePeriod {
  id: number
  organizationId: number
  reason: string | null
  startsAt: number
  endsAt: number
  createdBy: number
  createdAt: number
}

interface CreateSilencePeriodRequest {
  reason?: string
  starts_at: number
  ends_at: number
}

// Uptime Monitoring Interfaces

interface UptimeMonitor {
  id: string
  organizationId: number
  name: string
  type: string
  active: boolean
  
  // Connection
  url?: string
  hostname?: string
  port?: number
  
  // HTTP
  method?: string
  headers?: Record<string, string>
  body?: string
  authMethod?: string
  authUser?: string
  expectedStatusCodes?: string
  maxRedirects?: number
  ignoreTls?: boolean
  
  // Keyword
  keyword?: string
  keywordInverse?: boolean
  
  // JSON Query
  jsonPath?: string
  jsonExpectedValue?: string
  
  // DNS
  dnsRecordType?: string
  dnsExpectedValue?: string
  dnsServer?: string
  
  // SSL
  sslExpiryWarnDays?: number
  
  // Database
  dbConnectionString?: string
  dbQuery?: string
  
  // Docker
  dockerContainerName?: string
  dockerHost?: string
  
  // Check config
  intervalSeconds: number
  timeoutSeconds: number
  retries: number
  retryIntervalSeconds: number
  
  // Status
  status: string
  lastCheckAt?: number
  lastStatusChangeAt?: number
  consecutiveFailures: number
  
  // Push token (only for push monitors)
  pushToken?: string
  
  // Incident severity override
  incidentSeverity?: string | null
  
  // Stats
  uptime24h?: number
  uptime7d?: number
  uptime30d?: number
  avgResponseTime?: number
  
  createdAt: number
  updatedAt: number
}

interface CreateUptimeMonitorRequest {
  name: string
  type: string
  
  // Connection
  url?: string
  hostname?: string
  port?: number
  
  // HTTP
  method?: string
  headers?: Record<string, string>
  body?: string
  authMethod?: string
  authUser?: string
  authPass?: string
  expectedStatusCodes?: string
  maxRedirects?: number
  ignoreTls?: boolean
  
  // Keyword
  keyword?: string
  keywordInverse?: boolean
  
  // JSON Query
  jsonPath?: string
  jsonExpectedValue?: string
  
  // DNS
  dnsRecordType?: string
  dnsExpectedValue?: string
  dnsServer?: string
  
  // SSL
  sslExpiryWarnDays?: number
  
  // Database
  dbConnectionString?: string
  dbQuery?: string
  
  // Docker
  dockerContainerName?: string
  dockerHost?: string
  
  // Check config
  intervalSeconds?: number
  timeoutSeconds?: number
  retries?: number
  retryIntervalSeconds?: number
  
  // Incident severity override
  incidentSeverity?: string
}

interface UpdateUptimeMonitorRequest extends Partial<CreateUptimeMonitorRequest> {
  active?: boolean
}

interface UptimeHeartbeat {
  timestamp: number
  status: number
  responseTimeMs: number
  statusCode: number
  message: string
  pingMs?: number
}

interface OrganizationIntegration {
  id: number
  integrationType: string
  teamName: string | null
  channelId: string | null
  channelName: string | null
  enabled: boolean
  isConfigured: boolean
}

interface SlackOAuthStartResponse {
  authUrl: string
}

interface SlackChannel {
  id: string
  name: string
}

interface SlackChannelList {
  channels: SlackChannel[]
}

interface SlackChannelSelection {
  channelId: string
  channelName: string
}

interface SlackUsergroup {
  id: string
  handle: string
  name: string
  description?: string
}

interface UpdateSlackIntegrationRequest {
  webhookUrl: string
  channelName?: string
  enabled?: boolean
}

interface OrgMember {
  userId: number
  email: string
  name?: string
  role: string
  joinedAt?: string
}

interface OrgInvitation {
  id: number
  email: string
  role: string
  status: string
  invitedBy: string
  invitedByEmail: string
  createdAt: string
  expiresAt: string
}

interface OrgMembersResponse {
  members: OrgMember[]
  pendingInvitations: OrgInvitation[]
}

interface InvitationDetailsResponse {
  orgName: string
  role: string
  invitedBy: string
  expiresAt: string
  valid: boolean
}

interface BulkInviteResult {
  success: string[]
  failed: Array<{ email: string; reason: string }>
}

interface TestIntegrationResponse {
  success: boolean
  message: string
}

// Incident Provider Types

interface IncidentProviderConfig {
  id: number
  providerType: string
  name: string
  configJson: Record<string, string>
  enabled: boolean
  createdAt: number
  updatedAt: number
}

interface CreateIncidentProviderRequest {
  providerType: string
  name: string
  apiKey: string
  configJson: Record<string, string>
}

interface UpdateIncidentProviderRequest {
  name?: string
  apiKey?: string
  configJson?: Record<string, string>
  enabled?: boolean
}

interface IncidentRoutingRule {
  id: number
  alertSource: string
  alertType?: string | null
  incidentSeverity: string
}

interface UpsertRoutingRuleRequest {
  alertSource: string
  alertType?: string | null
  incidentSeverity: string
}

interface IncidentEventLogEntry {
  id: number
  alertSource: string
  deduplicationKey: string
  incidentSeverity: string
  incidentStatus: string
  title: string
  description?: string | null
  providerIncidentId?: string | null
  success: boolean
  errorMessage?: string | null
  createdAt: number
}

// Status Page Interfaces

interface StatusPage {
  id: string
  organizationId: string
  name: string
  slug: string
  description?: string
  logoUrl?: string
  faviconUrl?: string
  primaryColor: string
  darkMode: boolean
  showUptimeHistory: boolean
  historyDays: number
  isPublic: boolean
  createdAt: string
  updatedAt: string
}

interface StatusPageDetail extends StatusPage {
  monitors: StatusPageMonitor[]
  customDomains: CustomDomain[]
}

interface StatusPageMonitor {
  id: number
  monitorId: string
  monitorName: string
  displayName?: string
  sortOrder: number
  url?: string
}

interface MonitorAssignment {
  monitorId: string
  displayName?: string
  sortOrder: number
}

interface StatusPageIncident {
  id: string
  statusPageId: string
  title: string
  status: string
  type: string
  impact: string
  scheduledStartAt?: string
  scheduledEndAt?: string
  resolvedAt?: string
  createdAt: string
  updatedAt: string
  updates: IncidentUpdate[]
}

interface IncidentUpdate {
  id: string
  status: string
  message: string
  createdAt: string
}

interface CustomDomain {
  id: number
  domain: string
  verificationToken: string
  verified: boolean
  verifiedAt?: string
  sslProvisioned: boolean
  createdAt: string
}

interface PublicStatusPage {
  name: string
  description?: string
  logoUrl?: string
  faviconUrl?: string
  primaryColor: string
  darkMode: boolean
  showUptimeHistory: boolean
  historyDays: number
  monitors: PublicMonitorStatus[]
  activeIncidents: StatusPageIncident[]
  scheduledMaintenance: StatusPageIncident[]
}

interface PublicMonitorStatus {
  name: string
  displayName?: string
  status: string
  uptimePercentage: number
  uptimeHistory?: UptimeDataPoint[]
}

interface UptimeDataPoint {
  date: string
  uptime: number
}

interface CreateStatusPageRequest {
  name: string
  slug: string
  description?: string
  logoUrl?: string
  faviconUrl?: string
  primaryColor?: string
  darkMode?: boolean
  showUptimeHistory?: boolean
  historyDays?: number
  isPublic?: boolean
}

interface UpdateStatusPageRequest {
  name?: string
  slug?: string
  description?: string
  logoUrl?: string
  faviconUrl?: string
  primaryColor?: string
  darkMode?: boolean
  showUptimeHistory?: boolean
  historyDays?: number
  isPublic?: boolean
}

interface CreateIncidentRequest {
  title: string
  status: string
  type?: string
  impact?: string
  message: string
  scheduledStartAt?: string
  scheduledEndAt?: string
}

interface UpdateIncidentRequest {
  title?: string
  status?: string
  impact?: string
  scheduledStartAt?: string
  scheduledEndAt?: string
}

interface CreateIncidentUpdateRequest {
  status: string
  message: string
}

interface AddCustomDomainRequest {
  domain: string
}

// On-Call Interfaces

interface Priority {
  id: number
  organizationId: number
  severity: string
  priorityLevel: string
  isPageable: boolean
  label: string
  description?: string
}

interface BusinessHours {
  id: number
  organizationId: number
  timezone: string
  enabled: boolean
  windows: BusinessHoursWindow[]
}

interface BusinessHoursWindow {
  id: number
  businessHoursId: number
  dayOfWeek: number
  startTime: string
  endTime: string
}

interface OnCallSchedule {
  id: number
  organizationId: number
  name: string
  rotationType: 'DAILY' | 'WEEKLY' | 'CUSTOM'
  handoffTime: string
  timezone: string
  createdAt: string
  updatedAt: string
  participants: OnCallParticipant[]
  overrides: OnCallOverride[]
  currentOnCall?: {
    userId: number
    userName: string
  }
  slackUsergroupId?: string
  slackUsergroupHandle?: string
}

interface OnCallParticipant {
  id: number
  scheduleId: number
  userId: number
  userName: string
  position: number
}

interface OnCallOverride {
  id: number
  scheduleId: number
  userId: number
  userName: string
  startAt: string
  endAt: string
  createdBy: number
}

interface EscalationPolicy {
  id: number
  organizationId: number
  name: string
  description?: string
  repeatCount: number
  createdAt: string
  updatedAt: string
  steps: EscalationStep[]
}

interface EscalationStep {
  id: number
  escalationPolicyId: number
  stepOrder: number
  timeoutMinutes: number
  smsFallbackDelayMinutes: number
  createdAt: string
  targets: EscalationTarget[]
}

interface EscalationTarget {
  id: number
  escalationStepId: number
  targetType: 'USER' | 'ON_CALL_SCHEDULE'
  targetId: number
  targetName: string
}

interface Incident {
  id: number
  organizationId: number
  escalationPolicyId: number
  title: string
  description?: string
  priorityLevel: string
  status: 'TRIGGERED' | 'ACKNOWLEDGED' | 'RESOLVED'
  alertSource: string
  deduplicationKey?: string
  triggeredAt: string
  acknowledgedAt?: string
  acknowledgedBy?: number
  acknowledgedByName?: string
  resolvedAt?: string
  resolvedBy?: number
  resolvedByName?: string
  metadata?: Record<string, unknown>
  nextEscalationAt?: string
  viewedByCurrentUser?: boolean
}

interface IncidentTimeline {
  id: number
  incidentId: number
  eventType: 'TRIGGERED' | 'ESCALATED' | 'ACKNOWLEDGED' | 'RESOLVED' | 'REASSIGNED' | 'NOTE_ADDED' | 'STEP_TIMEOUT' | 'NOTIFICATION_SENT' | 'VIEWED'
  actorUserId?: number
  actorUserName?: string
  details?: Record<string, unknown>
  createdAt: string
}

interface IncidentDetail extends Incident {
  timeline?: IncidentTimeline[]
}

interface OnCallIncident {
  id: number
  organizationId: number
  title: string
  description?: string
  priorityLevel: string
  status: string
  declaredBy: number
  declaredByName?: string
  declaredAt: string
  resolvedBy?: number
  resolvedByName?: string
  resolvedAt?: string
  alertCount: number
  alerts?: Array<{ id: number; title: string; status: string }>
  createdAt: string
  updatedAt: string
}

interface OnCallIncidentDetail extends OnCallIncident {
  timeline?: IncidentTimeline[]
}

interface SsoConfig {
  providerType: string
  isEnabled: boolean
  idpEntityId?: string
  idpSsoUrl?: string
  idpCertificate?: string
  oidcIssuerUrl?: string
  oidcClientId?: string
  oidcClientSecret?: string
  emailDomain?: string
  requireSso?: boolean
  spEntityId?: string
  hasClientSecret?: boolean
}

interface DeviceToken {
  id: number
  userId: number
  deviceToken: string
  platform: 'IOS' | 'ANDROID'
  deviceName?: string
  createdAt: string
  lastUsedAt?: string
}

interface CreateOnCallScheduleRequest {
  name: string
  rotationType: 'DAILY' | 'WEEKLY' | 'CUSTOM'
  handoffTime: string
  timezone: string
  participants: { userId: number; position: number }[]
}

interface UpdateOnCallScheduleRequest {
  name?: string
  rotationType?: 'DAILY' | 'WEEKLY' | 'CUSTOM'
  handoffTime?: string
  timezone?: string
  participants?: { userId: number; position: number }[]
}

interface CreateOverrideRequest {
  userId: number
  startAt: string
  endAt: string
}

interface CreateEscalationPolicyRequest {
  name: string
  description?: string
  repeatCount: number
  steps: {
    stepOrder: number
    timeoutMinutes: number
    smsFallbackDelayMinutes?: number
    targets: {
      targetType: 'USER' | 'ON_CALL_SCHEDULE'
      targetId: number
    }[]
  }[]
}

interface UpdateEscalationPolicyRequest {
  name?: string
  description?: string
  repeatCount?: number
  steps?: {
    stepOrder: number
    timeoutMinutes: number
    smsFallbackDelayMinutes?: number
    targets: {
      targetType: 'USER' | 'ON_CALL_SCHEDULE'
      targetId: number
    }[]
  }[]
}

interface UpdatePrioritiesRequest {
  priorities: {
    severity: string
    priorityLevel: string
    isPageable: boolean
    label: string
    description?: string
  }[]
}

interface UpdateBusinessHoursRequest {
  timezone: string
  enabled: boolean
  windows: {
    dayOfWeek: number
    startTime: string
    endTime: string
  }[]
}

interface RegisterDeviceRequest {
  deviceToken: string
  platform: 'IOS' | 'ANDROID'
  deviceName?: string
}

interface IncidentListFilters {
  status?: 'TRIGGERED' | 'ACKNOWLEDGED' | 'RESOLVED'
  priorityLevel?: string
  fromDate?: string
  toDate?: string
}

interface UpdateOnCallSeatsResponse {
  seats: number
  proratedAmountCents?: number
}

// ── AI Chat Types ─────────────────────────────────────────────────────

interface AiChatResponseData {
  message: string
  actions?: AiAction[]
  clarifications?: AiClarification[]
  data_queries?: AiDataQuery[]
  links?: AiLink[]
  context_needed?: string[]
}

interface AiAction {
  id: string
  type: string
  label: string
  method: string
  endpoint: string
  params?: Record<string, string>
}

interface AiClarification {
  id: string
  question: string
  field: string
  options?: { label: string; value: string }[]
  default?: string
}

interface AiDataQuery {
  id: string
  description: string
  endpoint: string
  params?: Record<string, string>
}

interface AiLink {
  label: string
  url: string
}

interface AiChatResponse {
  conversationId: number
  response: AiChatResponseData
  model?: string
  tokensUsed?: number
}

interface AiActionResult {
  success: boolean
  message: string
  data?: Record<string, string>
}

interface AiConversationSummary {
  id: number
  title: string | null
  createdAt: string
  updatedAt: string
}

interface AiMessageDto {
  id: number
  role: string
  content: string
  pageContext?: string
  model?: string
  tokensUsed?: number
  createdAt: string
}

interface AiConversationDetail {
  id: number
  title: string | null
  messages: AiMessageDto[]
  createdAt: string
  updatedAt: string
}

// --- LLM Observability types ---

interface LlmTimelinePoint {
  timestamp: string
  count: number
  tokens: number
  cost: number
  errors: number
}

interface LlmModelStats {
  model: string
  provider: string
  callCount: number
  totalTokens: number
  totalCost: number
  avgDurationMs: number
  errorRate: number
}

interface LlmOverviewResponse {
  totalGenerations: number
  totalTokens: number
  totalCost: number
  avgDurationMs: number
  errorRate: number
  timeline: LlmTimelinePoint[]
  topModels: LlmModelStats[]
}

interface LlmGeneration {
  generationId: string
  traceId: string
  spanId: string
  parentSpanId: string
  timestamp: string
  durationMs: number
  name: string
  model: string
  provider: string
  type: string
  inputTokens: number
  outputTokens: number
  totalTokens: number
  costUsd: number
  status: string
  errorMessage: string
  userId: string
  environment: string
  release: string
}

interface LlmGenerationDetail extends LlmGeneration {
  input: string
  output: string
  temperature: number
  maxTokens: number
  topP: number
  statusCode: number
  sessionId: string
  tags: Record<string, string>
  metadata: string
}

interface LlmGenerationsListResponse {
  generations: LlmGeneration[]
  total: number
  page: number
  pageSize: number
}

interface LlmTraceResponse {
  traceId: string
  generations: LlmGenerationDetail[]
  totalDurationMs: number
  totalTokens: number
  totalCost: number
}

interface LlmCostBreakdown {
  model: string
  provider: string
  totalCost: number
  totalTokens: number
  callCount: number
}

interface LlmCostsResponse {
  totalCost: number
  breakdown: LlmCostBreakdown[]
  timeline: LlmTimelinePoint[]
}

interface OnCallContactSettings {
  phoneNumber: string | null
  onCallPhoneOptIn: boolean
  onCallPhoneConsentedAt: string | null
  onCallPhoneConsentVersion: string | null
}

// --- Product Analytics types ---

type AnalyticsPeriod = 'today' | '7d' | '30d' | 'month' | '6mo' | '12mo' | 'custom'
type AnalyticsComparison = 'previous_period' | 'year_over_year' | 'none'

interface AnalyticsFilter {
  property: string
  operator: 'is' | 'is_not' | 'contains' | 'not_contains'
  value: string
}

interface AnalyticsParams {
  period?: AnalyticsPeriod
  from?: string
  to?: string
  filters?: AnalyticsFilter[]
  comparison?: AnalyticsComparison
}

interface AnalyticsOverview {
  uniqueVisitors: number
  totalPageviews: number
  bounceRate: number
  avgVisitDuration: number
  viewsPerVisit: number
  comparison?: {
    uniqueVisitors: number
    totalPageviews: number
    bounceRate: number
    avgVisitDuration: number
    viewsPerVisit: number
  }
}

interface AnalyticsTimeseriesPoint {
  timestamp: string
  visitors: number
  pageviews: number
}

interface AnalyticsBreakdownItem {
  name: string
  visitors: number
  pageviews: number
  bounceRate?: number
  avgDuration?: number
  percentage: number
}

interface AnalyticsRealtimeResponse {
  currentVisitors: number
}

interface AnalyticsFunnelStep {
  name: string
  visitors: number
  dropoff: number
  conversionRate: number
}

interface AnalyticsFunnelResponse {
  steps: AnalyticsFunnelStep[]
  overallConversion: number
}

class ApiClient {
  private authRedirectInProgress = false
  private refreshPromise: Promise<boolean> | null = null

  private getImpersonateToken(): string | null {
    return sessionStorage.getItem('impersonate_token')
  }

  private getRefreshToken(): string | null {
    return localStorage.getItem('refresh_token')
  }

  private setRefreshToken(token: string): void {
    localStorage.setItem('refresh_token', token)
  }

  private removeRefreshToken(): void {
    localStorage.removeItem('refresh_token')
  }

  private async refreshAccessToken(): Promise<boolean> {
    const refreshToken = this.getRefreshToken()

    // Demo users don't have refresh tokens — re-issue via demo-refresh
    if (!refreshToken && isDemo()) {
      try {
        const response = await fetch(`${API_BASE.replace('/v1', '')}/auth/demo-refresh`, {
          method: 'POST',
          headers: { 'Content-Type': 'application/json' },
          credentials: 'include',
        })
        if (!response.ok) return false
        const data = await response.json()
        if (data.demoEpochMs) setDemoEpoch(data.demoEpochMs)
        sessionStorage.setItem('authenticated', 'true')
        return true
      } catch {
        return false
      }
    }

    if (!refreshToken) return false

    try {
      const response = await fetch(`${API_BASE.replace('/v1', '')}/auth/refresh`, {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ refreshToken }),
        credentials: 'include',
      })

      if (!response.ok) {
        this.removeRefreshToken()
        return false
      }

      const data: AuthResponse = await response.json()
      if (data.refreshToken) {
        this.setRefreshToken(data.refreshToken)
      }
      sessionStorage.setItem('authenticated', 'true')
      return true
    } catch (err) {
      this.removeRefreshToken()
      return false
    }
  }

  private async request<T>(endpoint: string, options: RequestInit = {}): Promise<T> {
    const impersonateToken = this.getImpersonateToken()
    const headers: Record<string, string> = {
      'Content-Type': 'application/json',
      ...(options.headers as Record<string, string>),
    }
    // Only send Authorization header for impersonation; normal auth uses httpOnly cookie
    if (impersonateToken) headers['Authorization'] = `Bearer ${impersonateToken}`

    let response: Response
    try {
      response = await fetch(endpoint, { ...options, headers, credentials: 'include' })
    } catch (err) {
      // Create a clean error without the massive stack trace from fetch
      const networkError = new Error('NETWORK_ERROR')
      networkError.stack = undefined // Remove stack trace to keep error small
      throw networkError
    }

    if (response.status === 401) {
      // Try to refresh the token if we have one
      if (!this.refreshPromise) {
        this.refreshPromise = this.refreshAccessToken().finally(() => {
          this.refreshPromise = null
        })
      }

      const refreshed = await this.refreshPromise

      if (refreshed) {
        // Retry the original request with the new token
        return this.request<T>(endpoint, options)
      }

      // Refresh failed, logout and redirect
      this.logout()

      if (
        !this.authRedirectInProgress &&
        typeof window !== 'undefined' &&
        !AUTH_PAGE_PATHS.has(window.location.pathname)
      ) {
        this.authRedirectInProgress = true
        window.location.assign('/login')
      }

      throw new Error('Unauthorized')
    }

    if (!response.ok) {
      let errorMessage = `API Error: ${response.status} ${response.statusText}`
      try {
        const errorData = await response.json()
        if (errorData.error) {
          errorMessage = errorData.error
        }
      } catch {
        // If parsing fails, use the default message
      }
      const error = new Error(errorMessage) as Error & { status: number }
      error.status = response.status
      throw error
    }
    if (response.status === 204) return undefined as T
    return response.json()
  }

  private mapMonitorSystem(row: Record<string, unknown>): MonitorSystemWithMetrics {
    const latest = (row.latest_metrics ?? {}) as Record<string, unknown>

    return {
      id: row.id as string,
      projectId: (row.projectId ?? row.project_id) as number | undefined,
      name: row.name as string,
      host: row.host as string | undefined,
      status: row.status as 'up' | 'down' | 'pending',
      lastSeenAt: (row.lastSeenAt ?? row.last_seen_at) as string | number | undefined,
      agentVersion: (row.agentVersion ?? row.agent_version) as string | undefined,
      os: row.os as string | undefined,
      arch: row.arch as string | undefined,
      createdAt: (row.createdAt ?? row.created_at) as string | number | undefined,
      updatedAt: (row.updatedAt ?? row.updated_at) as string | number | undefined,
      cpuPercent: (row.cpuPercent ?? latest.cpu_percent) as number | undefined,
      memTotal: (row.memTotal ?? latest.mem_total) as number | undefined,
      memUsed: (row.memUsed ?? latest.mem_used) as number | undefined,
      memAvailable: (row.memAvailable ?? latest.mem_available) as number | undefined,
      diskTotal: (row.diskTotal ?? latest.disk_total) as number | undefined,
      diskUsed: (row.diskUsed ?? latest.disk_used) as number | undefined,
      load1: (row.load1 ?? latest.load_1) as number | undefined,
      load5: (row.load5 ?? latest.load_5) as number | undefined,
      load15: (row.load15 ?? latest.load_15) as number | undefined,
      netRecvBytes: (row.netRecvBytes ?? latest.net_recv_bytes) as number | undefined,
      netSentBytes: (row.netSentBytes ?? latest.net_sent_bytes) as number | undefined,
      tempMax: (row.tempMax ?? latest.temp_max) as number | undefined,
      gpuPercent: (row.gpuPercent ?? latest.gpu_percent) as number | undefined,
      batteryPercent: (row.batteryPercent ?? latest.battery_percent) as number | undefined,
      latest_metrics: row.latest_metrics as LatestMetrics | undefined,
    }
  }

  private mapSystemAlert(row: Record<string, unknown>): SystemAlert {
    return {
      id: row.id as number,
      systemId: (row.systemId ?? row.system_id) as string | undefined,
      scope: (row.scope ?? 'system') as 'global' | 'system',
      metric: row.metric as string,
      condition: row.condition as string,
      threshold: row.threshold as number,
      durationSeconds: (row.durationSeconds ?? row.duration_seconds ?? 0) as number,
      enabled: row.enabled === true,
      incidentSeverity: (row.incidentSeverity ?? row.incident_severity ?? null) as string | null,
      lastTriggeredAt: (row.lastTriggeredAt ?? row.last_triggered_at) as number | undefined,
      createdAt: (row.createdAt ?? row.created_at) as number,
    }
  }

  async signup(
    email: string,
    password: string,
    name: string | undefined,
    legalConsent: SignupLegalConsent,
    inviteToken?: string
  ): Promise<AuthResponse> {
    const signupUrl = inviteToken
      ? `${API_BASE.replace('/v1', '')}/auth/signup?inviteToken=${encodeURIComponent(inviteToken)}`
      : `${API_BASE.replace('/v1', '')}/auth/signup`
    const response = await this.request<AuthResponse>(signupUrl, {
      method: 'POST',
      body: JSON.stringify({ email, password, name, ...legalConsent }),
    })
    // Token is now set as httpOnly cookie by the backend
    // Store refresh token in localStorage
    if (response.refreshToken) {
      this.setRefreshToken(response.refreshToken)
    }
    sessionStorage.setItem('authenticated', 'true')
    return response
  }

  async login(email: string, password: string): Promise<AuthResponse> {
    const response = await this.request<AuthResponse>(`${API_BASE.replace('/v1', '')}/auth/login`, {
      method: 'POST',
      body: JSON.stringify({ email, password }),
    })
    // Token is now set as httpOnly cookie by the backend
    // Store refresh token in localStorage
    if (response.refreshToken) {
      this.setRefreshToken(response.refreshToken)
    }
    sessionStorage.setItem('authenticated', 'true')
    return response
  }

  async initSso(email?: string, orgSlug?: string): Promise<{ redirectUrl: string; providerType: string; state?: string }> {
    return this.request(`${API_BASE.replace('/v1', '')}/auth/sso/init`, {
      method: 'POST',
      body: JSON.stringify({ email, orgSlug }),
    })
  }

  async checkSsoRequired(email: string): Promise<{ required: boolean }> {
    return this.request(`${API_BASE}/sso/check-required`, {
      method: 'POST',
      body: JSON.stringify({ email }),
    })
  }

  async getSsoConfig(organizationId: number): Promise<SsoConfig> {
    return this.request<SsoConfig>(`${API_BASE}/sso/config?organizationId=${organizationId}`)
  }

  async configureSso(organizationId: number, config: SsoConfig): Promise<SsoConfig> {
    return this.request<SsoConfig>(`${API_BASE}/sso/config?organizationId=${organizationId}`, {
      method: 'PUT',
      body: JSON.stringify(config),
    })
  }

  async deleteSsoConfig(organizationId: number): Promise<void> {
    await this.request(`${API_BASE}/sso/config?organizationId=${organizationId}`, {
      method: 'DELETE',
    })
  }

  async impersonateUser(userId: number): Promise<{ token: string }> {
    return this.request(`${API_BASE}/admin/impersonate/${userId}`, { method: 'POST' })
  }

  async logout() {
    sessionStorage.removeItem('impersonate_token')
    sessionStorage.removeItem('authenticated')
    this.removeRefreshToken()
    // Clear demo mode
    setDemoEpoch(null)
    // Clear the httpOnly auth cookie via backend
    try {
      await fetch(`${API_BASE.replace('/v1', '')}/auth/logout`, {
        method: 'POST',
        credentials: 'include',
      })
    } catch (e) {
      // Ignore errors - cookie might already be cleared
    }
  }

  isAuthenticated(): boolean {
    // Check impersonation token first, then session flag
    // The session flag is set after successful login/signup; actual auth is via httpOnly cookie
    return !!this.getImpersonateToken() || sessionStorage.getItem('authenticated') === 'true'
  }

  async checkAuth(): Promise<boolean> {
    try {
      await this.getCurrentUser()
      sessionStorage.setItem('authenticated', 'true')
      return true
    } catch {
      sessionStorage.removeItem('authenticated')
      return false
    }
  }

  async getProjects(): Promise<Project[]> {
    return this.request<Project[]>(`${API_BASE}/projects`)
  }

  async getProject(projectId: number): Promise<Project> {
    return this.request<Project>(`${API_BASE}/projects/${projectId}`)
  }

  async getSdkVersions(): Promise<SdkVersionsResponse> {
    return this.request<SdkVersionsResponse>(`${API_BASE}/sdk-versions`)
  }

  async createProject(name: string, framework?: string, targets?: string[]): Promise<Project> {
    return this.request<Project>(`${API_BASE}/projects`, {
      method: 'POST',
      body: JSON.stringify({ name, framework, targets }),
    })
  }

  async addProjectTarget(projectId: number, target: string): Promise<ProjectKey> {
    return this.request<ProjectKey>(`${API_BASE}/projects/${projectId}/targets`, {
      method: 'POST',
      body: JSON.stringify({ target }),
    })
  }

  async updateProject(
    projectId: number,
    updates: { name?: string; framework?: string }
  ): Promise<void> {
    await this.request(`${API_BASE}/projects/${projectId}`, {
      method: 'PUT',
      body: JSON.stringify(updates),
    })
  }

  async deleteProject(projectId: number): Promise<void> {
    await this.request(`${API_BASE}/projects/${projectId}`, {
      method: 'DELETE',
    })
  }

  async getIssues(projectId: number, page = 1, limit = 25): Promise<Issue[]> {
    return this.request<Issue[]>(
      `${API_BASE}/projects/${projectId}/issues?page=${page}&limit=${limit}`
    )
  }

  async getIssue(issueId: string): Promise<IssueDetail> {
    return this.request<IssueDetail>(`${API_BASE}/issues/${issueId}`)
  }

  async getIssueEvents(issueId: string, limit = 50): Promise<Event[]> {
    return this.request<Event[]>(`${API_BASE}/issues/${issueId}/events?limit=${limit}`)
  }

  async getIssueTransactions(issueId: string, limit = 20): Promise<IssueTransaction[]> {
    return this.request<IssueTransaction[]>(
      `${API_BASE}/issues/${issueId}/transactions?limit=${limit}`
    )
  }

  async updateIssue(issueId: string, updates: { status?: string }): Promise<void> {
    await this.request(`${API_BASE}/issues/${issueId}`, {
      method: 'PATCH',
      body: JSON.stringify(updates),
    })
  }

  async completeOnboarding(
    organizationName: string, 
    companySize: string, 
    slug: string, 
    referralSource: string,
    utmSource?: string,
    utmMedium?: string,
    utmCampaign?: string,
    utmContent?: string,
    utmTerm?: string,
    sidebarHiddenItems?: string[]
  ): Promise<{ id: number; email: string; name?: string; emailVerified: boolean; onboardingCompleted: boolean; organizationSlug?: string; sidebarHiddenItems?: string[] }> {
    return this.request(`${API_BASE.replace('/v1', '')}/auth/complete-onboarding`, {
      method: 'POST',
      body: JSON.stringify({ 
        organizationName, 
        companySize, 
        slug, 
        referralSource,
        utmSource,
        utmMedium,
        utmCampaign,
        utmContent,
        utmTerm,
        sidebarHiddenItems
      }),
    })
  }

  async getCurrentUser(): Promise<{ id: number; email: string; name?: string; emailVerified: boolean; onboardingCompleted: boolean; isAdmin?: boolean; organizationSlug?: string; demoEpochMs?: number; sidebarHiddenItems?: string[] }> {
    return this.request(`${API_BASE}/user`)
  }

  async updateSidebarPreferences(hiddenItems: string[]): Promise<{ hiddenItems: string[] }> {
    return this.request(`${API_BASE}/user/sidebar-preferences`, {
      method: 'PUT',
      body: JSON.stringify({ hiddenItems }),
    })
  }

  async demoLogin(): Promise<{ token: string; demoEpochMs: number }> {
    const response = await this.request<{ token: string; demoEpochMs: number }>(`${API_BASE.replace('/v1', '')}/auth/demo-login`, {
      method: 'POST',
    })
    // Mark as authenticated (cookie is set by backend)
    sessionStorage.setItem('authenticated', 'true')
    return response
  }

  async checkSlugAvailability(slug: string): Promise<{ available: boolean }> {
    return this.request(`${API_BASE.replace('/v1', '')}/auth/check-slug?slug=${encodeURIComponent(slug)}`)
  }

  async getOrganizations(): Promise<Array<{ id: number; name: string; slug: string }>> {
    // This will get the user's organizations - for now return just the primary org
    // Backend should have an endpoint for this, but for now we can derive from other calls
    // This is a placeholder that the SSO settings component needs
    return [{ id: 1, name: "Default Organization", slug: "default" }]
  }

  async getOrganizationAccountSettings(organizationId: number): Promise<OrganizationAccountSettings> {
    return this.request<OrganizationAccountSettings>(`${API_BASE}/organizations/${organizationId}`)
  }

  async getAccountDeletionValidation(): Promise<AccountDeletionValidation> {
    return this.request<AccountDeletionValidation>(`${API_BASE}/account/deletion-validation`)
  }

  async getOrganizationDeletionValidation(organizationId: number): Promise<OrganizationDeletionValidation> {
    return this.request<OrganizationDeletionValidation>(`${API_BASE}/organizations/${organizationId}/deletion-validation`)
  }

  async deleteAccount(confirmation: string): Promise<{ message: string }> {
    return this.request<{ message: string }>(`${API_BASE}/account`, {
      method: 'DELETE',
      body: JSON.stringify({ confirmation }),
    })
  }

  async deleteOrganization(organizationId: number, confirmation: string): Promise<{ message: string }> {
    return this.request<{ message: string }>(`${API_BASE}/organizations/${organizationId}`, {
      method: 'DELETE',
      body: JSON.stringify({ confirmation }),
    })
  }

  // Organization team management
  async getOrgMembers(): Promise<OrgMembersResponse> {
    return this.request(`${API_BASE}/org/members`)
  }

  async inviteMember(email: string, role = 'member'): Promise<OrgInvitation> {
    return this.request(`${API_BASE}/org/invitations`, {
      method: 'POST',
      body: JSON.stringify({ email, role }),
    })
  }

  async bulkInviteMembers(emails: string[], role = 'member'): Promise<BulkInviteResult> {
    return this.request(`${API_BASE}/org/invitations/bulk`, {
      method: 'POST',
      body: JSON.stringify({ emails, role }),
    })
  }

  async updateMemberRole(userId: number, role: string): Promise<{ success: boolean }> {
    return this.request(`${API_BASE}/org/members/${userId}/role`, {
      method: 'PUT',
      body: JSON.stringify({ role }),
    })
  }

  async removeMember(userId: number): Promise<{ success: boolean }> {
    return this.request(`${API_BASE}/org/members/${userId}`, {
      method: 'DELETE',
    })
  }

  async revokeInvitation(invitationId: number): Promise<{ success: boolean }> {
    return this.request(`${API_BASE}/org/invitations/${invitationId}`, {
      method: 'DELETE',
    })
  }

  async resendInvitation(invitationId: number): Promise<{ success: boolean }> {
    return this.request(`${API_BASE}/org/invitations/${invitationId}/resend`, {
      method: 'POST',
    })
  }

  async getInvitationDetails(token: string): Promise<InvitationDetailsResponse> {
    return this.request(`${API_BASE}/org/invitations/details?token=${encodeURIComponent(token)}`)
  }

  async acceptInvitation(token: string): Promise<{ success: boolean }> {
    return this.request(`${API_BASE}/org/invitations/accept`, {
      method: 'POST',
      body: JSON.stringify({ token }),
    })
  }

  async getSubscription(): Promise<{ tier: { tierName: string } } | null> {
    try {
      return this.request(`${API_BASE}/subscription`)
    } catch {
      return null
    }
  }

  async resendVerificationEmail(email: string): Promise<{ message: string }> {
    return this.request(`${API_BASE.replace('/v1', '')}/auth/resend-verification`, {
      method: 'POST',
      body: JSON.stringify({ email }),
    })
  }

  async verifyEmail(token: string): Promise<{ message: string }> {
    return this.request(`${API_BASE.replace('/v1', '')}/auth/verify-email`, {
      method: 'POST',
      body: JSON.stringify({ token }),
    })
  }

  async forgotPassword(email: string): Promise<{ message: string }> {
    return this.request(`${API_BASE.replace('/v1', '')}/auth/forgot-password`, {
      method: 'POST',
      body: JSON.stringify({ email }),
    })
  }

  async resetPassword(token: string, newPassword: string): Promise<{ message: string }> {
    return this.request(`${API_BASE.replace('/v1', '')}/auth/reset-password`, {
      method: 'POST',
      body: JSON.stringify({ token, newPassword }),
    })
  }

  async getProjectStats(projectId: number, period: '24h' | '7d' | '30d' | '90d' = '7d'): Promise<ProjectStats> {
    return this.request<ProjectStats>(`${API_BASE}/projects/${projectId}/stats?period=${period}`)
  }

  async getProjectLogs(
    projectId: number,
    options: {
      cursor?: string
      limit?: number
      query?: string
      levels?: string[]
      service?: string
      environment?: string
      containerName?: string
      from?: string
      to?: string
      tags?: Record<string, string>
      excludeService?: string
      excludeEnvironment?: string
      excludeContainerName?: string
      excludeTags?: Record<string, string>
    } = {}
  ): Promise<LogQueryResponse> {
    const params = new URLSearchParams()
    if (options.cursor) params.set('cursor', options.cursor)
    params.set('limit', String(options.limit ?? 100))
    if (options.query) params.set('q', options.query)
    if (options.levels && options.levels.length > 0) {
      options.levels.forEach((level) => params.append('level', level))
    }
    if (options.service) params.set('service', options.service)
    if (options.environment) params.set('environment', options.environment)
    if (options.containerName) params.set('containerName', options.containerName)
    if (options.from) params.set('from', options.from)
    if (options.to) params.set('to', options.to)
    if (options.tags) {
      Object.entries(options.tags).forEach(([key, value]) => {
        if (key) params.append('tag', `${key}:${value}`)
      })
    }
    if (options.excludeService) params.set('excludeService', options.excludeService)
    if (options.excludeEnvironment) params.set('excludeEnvironment', options.excludeEnvironment)
    if (options.excludeContainerName) params.set('excludeContainerName', options.excludeContainerName)
    if (options.excludeTags) {
      Object.entries(options.excludeTags).forEach(([key, value]) => {
        if (key) params.append('excludeTag', `${key}:${value}`)
      })
    }

    const response = await this.request<RawLogResponse>(`${API_BASE}/projects/${projectId}/logs?${params.toString()}`)
    const rows: Record<string, unknown>[] = response.logs ?? []
    const logs = rows.map((row) => ({
      logId: row.logId ?? row.log_id,
      timestamp: row.timestamp,
      level: row.level,
      message: row.message,
      body: row.body ?? '',
      service: row.service ?? '',
      environment: row.environment ?? '',
      host: row.host ?? '',
      source: row.source ?? 'sdk',
      containerName: row.containerName ?? row.container_name ?? '',
      containerId: row.containerId ?? row.container_id ?? '',
      containerImage: row.containerImage ?? row.container_image ?? '',
      traceId: row.traceId ?? row.trace_id ?? '',
      spanId: row.spanId ?? row.span_id ?? '',
      tags: row.tags ?? {},
      resourceAttributes: row.resourceAttributes ?? row.resource_attributes ?? {},
    })) as LogEntry[]

    return {
      logs,
      nextCursor: response.nextCursor ?? response.next_cursor ?? null,
      hasMore: response.hasMore ?? response.has_more ?? false,
    }
  }

  async getSystemLogs(
    systemId: string,
    options: {
      cursor?: string
      limit?: number
      query?: string
      levels?: string[]
      service?: string
      environment?: string
      containerName?: string
      from?: string
      to?: string
      tags?: Record<string, string>
    } = {}
  ): Promise<LogQueryResponse> {
    const params = new URLSearchParams()
    if (options.cursor) params.set('cursor', options.cursor)
    params.set('limit', String(options.limit ?? 100))
    if (options.query) params.set('query', options.query)
    if (options.levels && options.levels.length > 0) {
      options.levels.forEach((level) => params.append('levels', level))
    }
    if (options.service) params.set('service', options.service)
    if (options.environment) params.set('environment', options.environment)
    if (options.containerName) params.set('container_name', options.containerName)
    if (options.from) params.set('from', options.from)
    if (options.to) params.set('to', options.to)
    if (options.tags) {
      Object.entries(options.tags).forEach(([key, value]) => {
        if (key) params.append('tag', `${key}:${value}`)
      })
    }

    const response = await this.request<RawLogResponse>(`${API_BASE}/monitor/systems/${systemId}/logs?${params.toString()}`)
    const rows: Record<string, unknown>[] = response.logs ?? []
    const logs = rows.map((row) => ({
      logId: row.logId ?? row.log_id,
      timestamp: row.timestamp,
      level: row.level,
      message: row.message,
      body: row.body ?? '',
      service: row.service ?? '',
      environment: row.environment ?? '',
      host: row.host ?? '',
      source: row.source ?? 'sdk',
      containerName: row.containerName ?? row.container_name ?? '',
      containerId: row.containerId ?? row.container_id ?? '',
      containerImage: row.containerImage ?? row.container_image ?? '',
      traceId: row.traceId ?? row.trace_id ?? '',
      spanId: row.spanId ?? row.span_id ?? '',
      tags: row.tags ?? {},
      resourceAttributes: row.resourceAttributes ?? row.resource_attributes ?? {},
    })) as LogEntry[]

    return {
      logs,
      nextCursor: response.nextCursor ?? response.next_cursor ?? null,
      hasMore: response.hasMore ?? response.has_more ?? false,
    }
  }

  async getProjectLogFilters(
    projectId: number,
    options: { from?: string; to?: string } = {}
  ): Promise<LogFilterOptionsWithCounts> {
    const params = new URLSearchParams()
    if (options.from) params.set('from', options.from)
    if (options.to) params.set('to', options.to)
    const query = params.toString()
    const response = await this.request<RawLogFilterResponse>(
      `${API_BASE}/projects/${projectId}/logs/filters${query ? `?${query}` : ''}`
    )
    // Support both old format (string[]) and new format (object with count)
    const mapServices = (arr: (string | { value: string; count: number })[]) =>
      arr.map((item: string | { value: string; count: number }) =>
        typeof item === 'string' ? { value: item, count: 0 } : { value: item.value, count: item.count ?? 0 }
      )
    return {
      services: mapServices(response.services ?? []),
      environments: mapServices(response.environments ?? []),
      levels: response.levels ?? [],
      tagKeys: response.tagKeys ?? response.tag_keys ?? [],
    }
  }

  async getProjectLogTagValues(
    projectId: number,
    key: string,
    options: { from?: string; to?: string; limit?: number } = {}
  ): Promise<{ key: string; values: string[] }> {
    const params = new URLSearchParams()
    params.set('key', key)
    if (options.from) params.set('from', options.from)
    if (options.to) params.set('to', options.to)
    if (options.limit) params.set('limit', String(options.limit))
    return this.request<{ key: string; values: string[] }>(
      `${API_BASE}/projects/${projectId}/logs/tag-values?${params.toString()}`
    )
  }

  createProjectLogTailStream(
    projectId: number,
    options: {
      query?: string
      levels?: string[]
      service?: string
      environment?: string
    } = {}
  ): EventSource {
    const params = new URLSearchParams()
    if (options.query) params.set('q', options.query)
    if (options.levels && options.levels.length > 0) {
      options.levels.forEach((level) => params.append('level', level))
    }
    if (options.service) params.set('service', options.service)
    if (options.environment) params.set('environment', options.environment)

    // Auth is handled via httpOnly cookie (EventSource sends cookies for same-origin)
    return new EventSource(`${API_BASE}/projects/${projectId}/logs/tail?${params.toString()}`, { withCredentials: true })
  }

  private buildLogFilterParams(options: {
    query?: string
    levels?: string[]
    service?: string
    environment?: string
    from?: string
    to?: string
    tags?: Record<string, string>
    excludeService?: string
    excludeEnvironment?: string
    excludeContainerName?: string
    excludeTags?: Record<string, string>
  }): URLSearchParams {
    const params = new URLSearchParams()
    if (options.query) params.set('q', options.query)
    if (options.levels && options.levels.length > 0) {
      options.levels.forEach((level) => params.append('level', level))
    }
    if (options.service) params.set('service', options.service)
    if (options.environment) params.set('environment', options.environment)
    if (options.from) params.set('from', options.from)
    if (options.to) params.set('to', options.to)
    if (options.tags) {
      Object.entries(options.tags).forEach(([key, value]) => {
        if (key) params.append('tag', `${key}:${value}`)
      })
    }
    if (options.excludeService) params.set('excludeService', options.excludeService)
    if (options.excludeEnvironment) params.set('excludeEnvironment', options.excludeEnvironment)
    if (options.excludeContainerName) params.set('excludeContainerName', options.excludeContainerName)
    if (options.excludeTags) {
      Object.entries(options.excludeTags).forEach(([key, value]) => {
        if (key) params.append('excludeTag', `${key}:${value}`)
      })
    }
    return params
  }

  async getProjectLogAggregate(
    projectId: number,
    options: {
      from?: string
      to?: string
      interval?: string
      query?: string
      levels?: string[]
      service?: string
      environment?: string
      tags?: Record<string, string>
      excludeService?: string
      excludeEnvironment?: string
      excludeContainerName?: string
      excludeTags?: Record<string, string>
      groupBy?: string
    } = {}
  ): Promise<LogAggregateResponse> {
    const params = this.buildLogFilterParams(options)
    if (options.interval) params.set('interval', options.interval)
    if (options.groupBy) params.set('groupBy', options.groupBy)
    const response = await this.request<RawLogAggregateResponse>(
      `${API_BASE}/projects/${projectId}/logs/aggregate?${params.toString()}`
    )
    return {
      buckets: (response.buckets ?? []).map((b) => ({
        timestamp: b.timestamp,
        count: b.count ?? 0,
        groups: b.groups ?? {},
      })),
      totalCount: response.total_count ?? response.totalCount ?? 0,
      interval: response.interval ?? 'auto',
    }
  }

  async getProjectLogTop(
    projectId: number,
    options: {
      field: string
      limit?: number
      from?: string
      to?: string
      query?: string
      levels?: string[]
      service?: string
      environment?: string
      tags?: Record<string, string>
      excludeService?: string
      excludeEnvironment?: string
      excludeContainerName?: string
      excludeTags?: Record<string, string>
    }
  ): Promise<LogTopResponse> {
    const params = this.buildLogFilterParams(options)
    params.set('field', options.field)
    if (options.limit) params.set('limit', String(options.limit))
    const response = await this.request<RawLogTopResponse>(
      `${API_BASE}/projects/${projectId}/logs/top?${params.toString()}`
    )
    return {
      field: response.field ?? options.field,
      values: (response.values ?? []).map((v) => ({
        value: v.value,
        count: v.count ?? 0,
      })),
      totalCount: response.total_count ?? response.totalCount ?? 0,
    }
  }

  async downloadProjectLogExport(
    projectId: number,
    options: {
      from?: string
      to?: string
      query?: string
      levels?: string[]
      service?: string
      environment?: string
      tags?: Record<string, string>
      excludeService?: string
      excludeEnvironment?: string
      excludeContainerName?: string
      excludeTags?: Record<string, string>
      limit?: number
    } = {}
  ): Promise<void> {
    const params = this.buildLogFilterParams(options)
    if (options.limit) params.set('limit', String(options.limit))

    const response = await fetch(
      `${API_BASE}/projects/${projectId}/logs/export?${params.toString()}`,
      { credentials: 'include' }
    )
    if (!response.ok) throw new Error('Export failed')

    const blob = await response.blob()
    const url = URL.createObjectURL(blob)
    const a = document.createElement('a')
    a.href = url
    a.download = 'logs-export.csv'
    document.body.appendChild(a)
    a.click()
    document.body.removeChild(a)
    URL.revokeObjectURL(url)
  }

  async getTransactions(
    projectId: number,
    options: { period?: '24h' | '7d' | '30d' | '90d'; environment?: string; operation?: string } = {}
  ): Promise<TransactionSummary[]> {
    const params = new URLSearchParams()
    params.set('period', options.period || '7d')
    if (options.environment) params.set('environment', options.environment)
    if (options.operation) params.set('operation', options.operation)
    const rows = await this.request<Array<TransactionSummary & {
      latest_event_id?: string
      failure_rate?: number
    }>>(`${API_BASE}/projects/${projectId}/transactions?${params.toString()}`)

    // Normalize wire-format variants to keep routing resilient.
    return rows.map((row) => ({
      ...row,
      latestEventId: row.latestEventId || row.latest_event_id,
      failureRate: row.failureRate ?? row.failure_rate ?? 0,
    }))
  }

  async getPerformanceStats(
    projectId: number,
    options: { period?: '24h' | '7d' | '30d' | '90d'; environment?: string; operation?: string } = {}
  ): Promise<PerformanceStats> {
    const params = new URLSearchParams()
    params.set('period', options.period || '7d')
    if (options.environment) params.set('environment', options.environment)
    if (options.operation) params.set('operation', options.operation)
    return this.request<PerformanceStats>(
      `${API_BASE}/projects/${projectId}/transactions/stats?${params.toString()}`
    )
  }

  async getTransaction(eventId: string): Promise<TransactionDetail> {
    return this.request<TransactionDetail>(`${API_BASE}/transactions/${encodeURIComponent(eventId)}`)
  }

  async getTransactionSpans(eventId: string): Promise<TransactionWithSpans> {
    return this.request<TransactionWithSpans>(
      `${API_BASE}/transactions/${encodeURIComponent(eventId)}/spans`
    )
  }

  async getTraceDetails(projectId: number, traceId: string): Promise<TraceDetail> {
    return this.request<TraceDetail>(
      `${API_BASE}/projects/${projectId}/traces/${encodeURIComponent(traceId)}`
    )
  }

  async getSpanDetails(projectId: number, spanId: string): Promise<SpanDetail> {
    return this.request<SpanDetail>(
      `${API_BASE}/projects/${projectId}/spans/${encodeURIComponent(spanId)}`
    )
  }

  async getRelatedErrors(eventId: string, limit = 20): Promise<Event[]> {
    return this.request<Event[]>(
      `${API_BASE}/transactions/${encodeURIComponent(eventId)}/related-errors?limit=${limit}`
    )
  }

  async getReleases(projectId: number): Promise<Release[]> {
    return this.request<Release[]>(`${API_BASE}/projects/${projectId}/releases`)
  }

  async getReleaseStats(projectId: number, version: string): Promise<ReleaseStats> {
    return this.request<ReleaseStats>(
      `${API_BASE}/projects/${projectId}/releases/${encodeURIComponent(version)}/stats`
    )
  }

  async getReplays(
    projectId: number,
    options: { page?: number; limit?: number; environment?: string; period?: '24h' | '7d' | '30d' | '90d' } = {}
  ): Promise<Replay[]> {
    const params = new URLSearchParams()
    params.set('page', String(options.page ?? 1))
    params.set('limit', String(options.limit ?? 25))
    params.set('period', options.period ?? '7d')
    if (options.environment) params.set('environment', options.environment)
    return this.request<Replay[]>(`${API_BASE}/projects/${projectId}/replays?${params.toString()}`)
  }

  async getReplay(replayId: string): Promise<ReplayDetail> {
    return this.request<ReplayDetail>(`${API_BASE}/replays/${encodeURIComponent(replayId)}`)
  }

  async getReplayRecording(replayId: string): Promise<ReplayRecordingResponse> {
    return this.request<ReplayRecordingResponse>(
      `${API_BASE}/replays/${encodeURIComponent(replayId)}/recording`
    )
  }

  async getReplayTimeline(replayId: string): Promise<ReplayTimelineResponse> {
    return this.request<ReplayTimelineResponse>(
      `${API_BASE}/replays/${encodeURIComponent(replayId)}/timeline`
    )
  }

  async getIssueIdForEvent(eventId: string): Promise<{ issueId: string } | null> {
    try {
      return await this.request<{ issueId: string }>(
        `${API_BASE}/events/${encodeURIComponent(eventId)}/issue`
      )
    } catch {
      return null
    }
  }

  async getReplaysForIssue(issueId: string, limit = 10): Promise<Replay[]> {
    return this.request<Replay[]>(
      `${API_BASE}/issues/${encodeURIComponent(issueId)}/replays?limit=${limit}`
    )
  }

  async getFeedback(
    projectId: number,
    options: { page?: number; limit?: number; status?: string } = {}
  ): Promise<Feedback[]> {
    const params = new URLSearchParams()
    params.set('page', String(options.page ?? 1))
    params.set('limit', String(options.limit ?? 25))
    if (options.status) params.set('status', options.status)
    return this.request<Feedback[]>(
      `${API_BASE}/projects/${projectId}/feedback?${params.toString()}`
    )
  }

  async getFeedbackDetail(feedbackId: string): Promise<FeedbackDetail> {
    return this.request<FeedbackDetail>(
      `${API_BASE}/feedback/${encodeURIComponent(feedbackId)}`
    )
  }

  async updateFeedback(feedbackId: string, updates: { status?: string }): Promise<void> {
    await this.request(`${API_BASE}/feedback/${encodeURIComponent(feedbackId)}`, {
      method: 'PATCH',
      body: JSON.stringify(updates),
    })
  }

  async getAuthTokens(): Promise<AuthToken[]> {
    return this.request<AuthToken[]>(`${API_BASE}/auth-tokens`)
  }

  async createAuthToken(
    name: string,
    scopes: string[],
    expiresInDays?: number
  ): Promise<AuthToken> {
    return this.request<AuthToken>(`${API_BASE}/auth-tokens`, {
      method: 'POST',
      body: JSON.stringify({ name, scopes, expiresInDays: expiresInDays ?? null }),
    })
  }

  async updateAuthToken(
    tokenId: number,
    updates: { name?: string; scopes?: string[] }
  ): Promise<void> {
    await this.request<void>(`${API_BASE}/auth-tokens/${tokenId}`, {
      method: 'PUT',
      body: JSON.stringify(updates),
    })
  }

  async deleteAuthToken(tokenId: number): Promise<void> {
    await this.request<void>(`${API_BASE}/auth-tokens/${tokenId}`, {
      method: 'DELETE',
    })
  }

  // Billing API
  async getBillingPlans() {
    return this.request<BillingPlansResponse>(`${API_BASE}/billing/plans`)
  }

  async getBillingUsage() {
    return this.request<BillingUsage>(`${API_BASE}/billing/usage`)
  }

  async createBillingCheckoutSession(body: CheckoutSessionRequest) {
    return this.request<CheckoutSessionResponse>(`${API_BASE}/billing/checkout`, {
      method: 'POST',
      body: JSON.stringify(body),
    })
  }

  async getBillingInvoices() {
    return this.request<Invoice[]>(`${API_BASE}/billing/invoices`)
  }

  async getBillingPaymentMethod() {
    return this.request<PaymentMethod>(`${API_BASE}/billing/payment-method`)
  }

  async createBillingSetupIntent() {
    return this.request<SetupIntentResponse>(`${API_BASE}/billing/setup-intent`, {
      method: 'POST',
    })
  }

  async confirmBillingSetupIntent(setupIntentId: string) {
    return this.request<{success: boolean}>(`${API_BASE}/billing/setup-intent/confirm`, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ setupIntentId }),
    })
  }

  async cancelBillingSubscription() {
    return this.request<CancelSubscriptionResponse>(`${API_BASE}/billing/cancel`, {
      method: 'POST',
    })
  }

  async updatePaygBudget(paygBudgetCents: number) {
    return this.request<{paygBudgetCents: number}>(`${API_BASE}/billing/payg-budget`, {
      method: 'PUT',
      body: JSON.stringify({paygBudgetCents}),
    })
  }

  async updateOnCallSeats(seats: number) {
    return this.request<UpdateOnCallSeatsResponse>(`${API_BASE}/billing/oncall-seats`, {
      method: 'PUT',
      body: JSON.stringify({ seats }),
    })
  }

  // Admin API
  async getAdminOverview() {
    return this.request<{
      totalOrganizations: number
      totalUsers: number
      totalEventsAllTime: number
      totalEventsLast30Days: number
      mrr: number
      subscriptionsByPlan: Record<string, number>
      eventsLast30Days: { date: string; count: number }[]
    }>(`${API_BASE}/admin/overview`)
  }

  async getAdminOrganizations(page = 1, limit = 25) {
    return this.request<Array<{
      id: number
      name: string
      slug: string
      plan: string
      eventCountThisMonth: number
      bytesIngestedThisMonth: number
      projectCount: number
      memberCount: number
      quotaUsedPercent: number | null
    }>>(`${API_BASE}/admin/organizations?page=${page}&limit=${limit}`)
  }

  async getAdminOrgDetail(orgId: number) {
    return this.request<{
      id: number
      name: string
      slug: string
      companySize: string | null
      plan: string
      subscriptionStatus: string | null
      memberCount: number
      projectCount: number
      eventCountThisMonth: number
      bytesIngestedThisMonth: number
      quotaUsedPercent: number | null
      members: Array<{ userId: number; email: string; name: string | null; role: string }>
      projects: Array<{ id: number; name: string; slug: string; platform: string | null }>
    }>(`${API_BASE}/admin/organizations/${orgId}`)
  }

  async getAdminOrgUsage(orgId: number, period = '7d') {
    return this.request<Array<{ date: string; eventType: string; eventCount: number; bytesIngested: number }>>(
      `${API_BASE}/admin/organizations/${orgId}/usage?period=${period}`
    )
  }

  async getAdminUsage(period = '7d') {
    return this.request<{
      daily: Array<{
        date: string
        error: number
        transaction: number
        replay: number
        feedback: number
        log: number
        total: number
      }>
      totalBytes: number
    }>(`${API_BASE}/admin/usage?period=${period}`)
  }

  async getAdminRevenue() {
    return this.request<{
      mrr: number
      subscriptionsByPlan: Record<string, number>
      estimatedCostPerOrg: Record<string, number>
      churnLast30Days: number
    }>(`${API_BASE}/admin/revenue`)
  }

  async getAdminInfrastructure() {
    return this.request<{
      clickhouseTables: Array<{
        table: string
        rows: number
        bytesOnDisk: number
        bytesOnDiskFormatted: string
      }>
      totalDiskBytes: number
      totalRows: number
      storageUsedPercent: number
      scalingTriggerAlerts: string[]
    }>(`${API_BASE}/admin/infrastructure`)
  }

  async getAdminTopConsumers(limit = 10) {
    return this.request<Array<{
      orgId: number
      orgName: string
      orgSlug: string
      plan: string
      eventCount: number
      bytesIngested: number
    }>>(`${API_BASE}/admin/top-consumers?limit=${limit}`)
  }

  async getAdminEmailStats(period = '30d') {
    return this.request<{
      totalSent: number
      byType: Record<string, number>
      last7Days: Array<{ date: string; count: number }>
      last30Days: Array<{ date: string; count: number }>
      estimatedCost: number
    }>(`${API_BASE}/admin/emails?period=${period}`)
  }

  async getAdminUsers(page = 1, limit = 25, search?: string) {
    const params = new URLSearchParams({ page: String(page), limit: String(limit) })
    if (search) params.append('search', search)
    return this.request<{
      users: Array<{
        id: number
        email: string
        name: string | null
        emailVerified: boolean
        isAdmin: boolean
        onboardingCompleted: boolean
        oauthProvider: string | null
        organizationCount: number
        createdAt: string | null
      }>
      total: number
      page: number
      limit: number
    }>(`${API_BASE}/admin/users?${params}`)
  }

  async updateAdminUser(userId: number, updates: { isAdmin?: boolean; emailVerified?: boolean }) {
    return this.request<{ success: boolean }>(`${API_BASE}/admin/users/${userId}`, {
      method: 'PATCH',
      body: JSON.stringify(updates),
    })
  }

  async deleteAdminUsers(userIds: number[]) {
    return this.request<{ success: boolean; deletedCount: number; errors: string[] }>(`${API_BASE}/admin/users`, {
      method: 'DELETE',
      body: JSON.stringify({ userIds }),
    })
  }

  async testNotification(type: string, channel: string, testEmail?: string) {
    return this.request<{
      success: boolean
      emailSent: boolean
      slackSent: boolean
      discordSent?: boolean
      errors?: string[]
    }>(`${API_BASE}/admin/test-notification`, {
      method: 'POST',
      body: JSON.stringify({ type, channel, testEmail }),
    })
  }

  async triggerIncident(data: {
    source: string
    severity: string
    title: string
    description: string
  }) {
    return this.request<{ success: boolean }>(`${API_BASE}/admin/incidents/trigger`, {
      method: 'POST',
      body: JSON.stringify(data),
    })
  }

  async getAdminAttribution(groupBy: 'campaign' | 'source' | 'medium' | 'all' = 'campaign') {
    return this.request<AdminAttributionResponse>(`${API_BASE}/admin/attribution?groupBy=${encodeURIComponent(groupBy)}`)
  }

  async getAdminBillingTiers(tier?: string) {
    const query = tier ? `?tier=${encodeURIComponent(tier)}` : ''
    return this.request<BillingPlan[] | BillingTierConfig[]>(`${API_BASE}/admin/billing/tiers${query}`)
  }

  async createAdminBillingTierVersion(tierName: string, body: CreateTierVersionRequest) {
    return this.request<BillingTierConfig>(`${API_BASE}/admin/billing/tiers/${encodeURIComponent(tierName)}/versions`, {
      method: 'POST',
      body: JSON.stringify(body),
    })
  }

  async migrateAdminBillingTier(tierName: string, targetVersion: number, dryRun = true) {
    return this.request<TierMigrationResponse>(`${API_BASE}/admin/billing/tiers/${encodeURIComponent(tierName)}/migrate`, {
      method: 'POST',
      body: JSON.stringify({targetVersion, dryRun}),
    })
  }

  async updateAdminBillingTierPriceIds(tierName: string, version: number, body: UpdateStripePriceIdsRequest) {
    return this.request<BillingTierConfig>(
      `${API_BASE}/admin/billing/tiers/${encodeURIComponent(tierName)}/versions/${version}`,
      {
        method: 'PATCH',
        body: JSON.stringify(body),
      }
    )
  }

  async getAdminBillingSubscriptions(limit = 500) {
    return this.request<AdminBillingSubscription[]>(`${API_BASE}/admin/billing/subscriptions?limit=${limit}`)
  }

  // Notification Preferences
  async getNotificationPreferences() {
    return this.request<NotificationPreferences>(`${API_BASE}/notification-preferences`)
  }

  async updateNotificationPreferences(preferences: Partial<NotificationPreference>) {
    return this.request(`${API_BASE}/notification-preferences`, {
      method: 'PUT',
      body: JSON.stringify(preferences),
    })
  }

  async updateProjectNotificationPreferences(
    projectId: number,
    preferences: Partial<NotificationPreference>
  ) {
    return this.request(`${API_BASE}/notification-preferences/${projectId}`, {
      method: 'PUT',
      body: JSON.stringify(preferences),
    })
  }

  async deleteProjectNotificationPreferences(projectId: number) {
    return this.request(`${API_BASE}/notification-preferences/${projectId}`, {
      method: 'DELETE',
    })
  }

  // Unified Alert Notification Preferences
  async getAlertNotificationPreferences(): Promise<AlertNotificationPreference[]> {
    const response = await this.request<AlertNotificationPreferencesResponse>(
      `${API_BASE}/alert-notification-preferences`
    )
    return response.preferences ?? []
  }

  async updateAlertNotificationPreference(
    alertSource: AlertSource,
    preferences: { emailEnabled: boolean; slackEnabled: boolean; discordEnabled: boolean }
  ): Promise<AlertNotificationPreference> {
    return this.request<AlertNotificationPreference>(`${API_BASE}/alert-notification-preferences/${alertSource}`, {
      method: 'PUT',
      body: JSON.stringify(preferences),
    })
  }

  // Monitoring API
  async getMonitorSystems() {
    const rows = await this.request<Record<string, unknown>[]>(`${API_BASE}/monitor/systems`)
    return rows.map((row) => this.mapMonitorSystem(row))
  }

  async getMonitorSystem(systemId: string) {
    const row = await this.request<Record<string, unknown>>(`${API_BASE}/monitor/systems/${systemId}`)
    return this.mapMonitorSystem(row) as MonitorSystemDetail
  }

  async createMonitorSystem(name: string) {
    return this.request<CreateMonitorSystemResponse>(`${API_BASE}/monitor/systems`, {
      method: 'POST',
      body: JSON.stringify({ name }),
    })
  }

  async deleteMonitorSystem(systemId: string) {
    return this.request<void>(`${API_BASE}/monitor/systems/${systemId}`, {
      method: 'DELETE',
    })
  }

  async getSystemMetrics(systemId: string, from?: string, to?: string, interval?: string) {
    const params = new URLSearchParams()
    if (from) params.append('from', from)
    if (to) params.append('to', to)
    if (interval) params.append('interval', interval)
    const query = params.toString()
    return this.request<SystemMetricsHistory>(
      `${API_BASE}/monitor/systems/${systemId}/metrics${query ? `?${query}` : ''}`
    )
  }

  async getSystemContainers(systemId: string) {
    const response = await this.request<{containers: RawContainerStats[]}>(
      `${API_BASE}/monitor/systems/${systemId}/containers`
    )
    return response.containers.map((row) => ({
      name: row.name,
      id: row.id,
      image: row.image,
      status: row.status,
      cpuPercent: row.cpuPercent ?? row.cpu_percent,
      memUsed: row.memUsed ?? row.mem_used,
      memLimit: row.memLimit ?? row.mem_limit,
      netRecvBytes: row.netRecvBytes ?? row.net_recv_bytes,
      netSentBytes: row.netSentBytes ?? row.net_sent_bytes,
    }))
  }

  async getContainerMetrics(
    systemId: string,
    containerName: string,
    from?: string,
    to?: string,
    interval?: string
  ) {
    const params = new URLSearchParams()
    if (from) params.append('from', from)
    if (to) params.append('to', to)
    if (interval) params.append('interval', interval)
    const query = params.toString()
    return this.request<ContainerMetricsHistory>(
      `${API_BASE}/monitor/systems/${systemId}/containers/${encodeURIComponent(containerName)}/metrics${query ? `?${query}` : ''}`
    )
  }

  async getSystemAlerts(systemId: string) {
    const config = await this.getSystemAlertConfig(systemId)
    return config.effectiveAlerts
  }

  async getSystemAlertConfig(systemId: string) {
    const response = await this.request<RawSystemAlertConfigResponse>(`${API_BASE}/monitor/systems/${systemId}/alerts/config`)
    return {
      scope: (response.scope ?? 'system') as 'global' | 'system',
      globalAlerts: (response.globalAlerts ?? response.global_alerts ?? []).map((row) => this.mapSystemAlert(row)),
      systemAlerts: (response.systemAlerts ?? response.system_alerts ?? []).map((row) => this.mapSystemAlert(row)),
      effectiveAlerts: (response.effectiveAlerts ?? response.effective_alerts ?? []).map((row) => this.mapSystemAlert(row)),
    } as SystemAlertConfig
  }

  async updateSystemAlertScope(systemId: string, scope: 'global' | 'system') {
    return this.request<void>(`${API_BASE}/monitor/systems/${systemId}/alerts/scope`, {
      method: 'PUT',
      body: JSON.stringify({scope}),
    })
  }

  async createSystemAlert(
    systemId: string,
    alert: {
      metric: string
      condition: string
      threshold: number
      durationSeconds?: number
      enabled?: boolean
    },
    scope: 'global' | 'system' = 'system'
  ) {
    return this.request<SystemAlert>(`${API_BASE}/monitor/systems/${systemId}/alerts?scope=${scope}`, {
      method: 'POST',
      body: JSON.stringify(alert),
    })
  }

  async updateSystemAlert(
    systemId: string,
    alertId: number,
    updates: Partial<SystemAlert>,
    scope: 'global' | 'system' = 'system'
  ) {
    return this.request<SystemAlert>(`${API_BASE}/monitor/systems/${systemId}/alerts/${alertId}?scope=${scope}`, {
      method: 'PUT',
      body: JSON.stringify(updates),
    })
  }

  async deleteSystemAlert(systemId: string, alertId: number, scope: 'global' | 'system' = 'system') {
    return this.request<void>(`${API_BASE}/monitor/systems/${systemId}/alerts/${alertId}?scope=${scope}`, {
      method: 'DELETE',
    })
  }

  // Alert Silence Period Methods

  private mapSilencePeriod(row: Record<string, unknown>): SilencePeriod {
    return {
      id: row.id as number,
      organizationId: (row.organization_id ?? row.organizationId) as number,
      reason: (row.reason ?? null) as string | null,
      startsAt: (row.starts_at ?? row.startsAt) as number,
      endsAt: (row.ends_at ?? row.endsAt) as number,
      createdBy: (row.created_by ?? row.createdBy) as number,
      createdAt: (row.created_at ?? row.createdAt) as number,
    }
  }

  async getSilencePeriods(): Promise<SilencePeriod[]> {
    const response = await this.request<Record<string, unknown>[]>(`${API_BASE}/monitor/silence-periods`)
    return response.map((row) => this.mapSilencePeriod(row))
  }

  async createSilencePeriod(data: CreateSilencePeriodRequest): Promise<SilencePeriod> {
    const response = await this.request<Record<string, unknown>>(`${API_BASE}/monitor/silence-periods`, {
      method: 'POST',
      body: JSON.stringify(data),
    })
    return this.mapSilencePeriod(response)
  }

  async deleteSilencePeriod(id: number): Promise<void> {
    return this.request<void>(`${API_BASE}/monitor/silence-periods/${id}`, {
      method: 'DELETE',
    })
  }

  // Uptime Monitoring Methods

  async getUptimeMonitors() {
    return this.request<UptimeMonitor[]>(`${API_BASE}/uptime/monitors`)
  }

  async getUptimeMonitor(monitorId: string) {
    return this.request<UptimeMonitor>(`${API_BASE}/uptime/monitors/${monitorId}`)
  }

  async createUptimeMonitor(data: CreateUptimeMonitorRequest) {
    return this.request<UptimeMonitor>(`${API_BASE}/uptime/monitors`, {
      method: 'POST',
      body: JSON.stringify(data),
    })
  }

  async updateUptimeMonitor(monitorId: string, data: UpdateUptimeMonitorRequest) {
    return this.request<UptimeMonitor>(`${API_BASE}/uptime/monitors/${monitorId}`, {
      method: 'PUT',
      body: JSON.stringify(data),
    })
  }

  async deleteUptimeMonitor(monitorId: string) {
    return this.request<void>(`${API_BASE}/uptime/monitors/${monitorId}`, {
      method: 'DELETE',
    })
  }

  async pauseUptimeMonitor(monitorId: string) {
    return this.request<void>(`${API_BASE}/uptime/monitors/${monitorId}/pause`, {
      method: 'POST',
    })
  }

  async resumeUptimeMonitor(monitorId: string) {
    return this.request<void>(`${API_BASE}/uptime/monitors/${monitorId}/resume`, {
      method: 'POST',
    })
  }

  async getUptimeHeartbeats(monitorId: string, from?: number, to?: number) {
    const params = new URLSearchParams()
    if (from) params.append('from', from.toString())
    if (to) params.append('to', to.toString())
    const query = params.toString()
    return this.request<UptimeHeartbeat[]>(
      `${API_BASE}/uptime/monitors/${monitorId}/heartbeats${query ? `?${query}` : ''}`
    )
  }

  // Integration Management Methods

  async getIntegrations() {
    return this.request<OrganizationIntegration[]>(`${API_BASE}/integrations`)
  }

  async startSlackOAuth() {
    return this.request<SlackOAuthStartResponse>(`${API_BASE}/integrations/slack/oauth/start`)
  }

  async getSlackChannels() {
    return this.request<SlackChannelList>(`${API_BASE}/integrations/slack/channels`)
  }

  async updateSlackChannel(channelId: string, channelName: string) {
    return this.request<void>(`${API_BASE}/integrations/slack/channel`, {
      method: 'PUT',
      body: JSON.stringify({ channelId, channelName }),
    })
  }

  async toggleSlackIntegration() {
    return this.request<void>(`${API_BASE}/integrations/slack/toggle`, {
      method: 'PUT',
    })
  }

  async deleteSlackIntegration() {
    return this.request<void>(`${API_BASE}/integrations/slack`, {
      method: 'DELETE',
    })
  }

  async testSlackIntegration() {
    return this.request<TestIntegrationResponse>(`${API_BASE}/integrations/slack/test`, {
      method: 'POST',
    })
  }

  async getSlackUsergroups() {
    return this.request<SlackUsergroup[]>(`${API_BASE}/integrations/slack/usergroups`)
  }

  async setScheduleSlackUsergroup(scheduleId: number, usergroupId: string, usergroupHandle: string) {
    return this.request<void>(`${API_BASE}/on-call/schedules/${scheduleId}/slack-usergroup`, {
      method: 'PUT',
      body: JSON.stringify({ usergroupId, usergroupHandle }),
    })
  }

  async removeScheduleSlackUsergroup(scheduleId: number) {
    return this.request<void>(`${API_BASE}/on-call/schedules/${scheduleId}/slack-usergroup`, {
      method: 'DELETE',
    })
  }

  // Discord Integration Methods

  async startDiscordOAuth() {
    return this.request<SlackOAuthStartResponse>(`${API_BASE}/integrations/discord/oauth/start`)
  }

  async getDiscordChannels() {
    return this.request<SlackChannelList>(`${API_BASE}/integrations/discord/channels`)
  }

  async updateDiscordChannel(channelId: string, channelName: string) {
    return this.request<void>(`${API_BASE}/integrations/discord/channel`, {
      method: 'PUT',
      body: JSON.stringify({ channelId, channelName }),
    })
  }

  async toggleDiscordIntegration() {
    return this.request<void>(`${API_BASE}/integrations/discord/toggle`, {
      method: 'PUT',
    })
  }

  async deleteDiscordIntegration() {
    return this.request<void>(`${API_BASE}/integrations/discord`, {
      method: 'DELETE',
    })
  }

  async testDiscordIntegration() {
    return this.request<TestIntegrationResponse>(`${API_BASE}/integrations/discord/test`, {
      method: 'POST',
    })
  }

  // Incident Provider Methods

  async getIncidentProviders(): Promise<IncidentProviderConfig[]> {
    return this.request<IncidentProviderConfig[]>(`${API_BASE.replace('/v1', '')}/api/incident-providers`)
  }

  async createIncidentProvider(data: CreateIncidentProviderRequest): Promise<{ id: number }> {
    return this.request<{ id: number }>(`${API_BASE.replace('/v1', '')}/api/incident-providers`, {
      method: 'POST',
      body: JSON.stringify(data),
    })
  }

  async updateIncidentProvider(id: number, data: UpdateIncidentProviderRequest): Promise<void> {
    await this.request(`${API_BASE.replace('/v1', '')}/api/incident-providers/${id}`, {
      method: 'PUT',
      body: JSON.stringify(data),
    })
  }

  async deleteIncidentProvider(id: number): Promise<void> {
    await this.request(`${API_BASE.replace('/v1', '')}/api/incident-providers/${id}`, {
      method: 'DELETE',
    })
  }

  async testIncidentProvider(id: number): Promise<{ success: boolean; error?: string }> {
    return this.request<{ success: boolean; error?: string }>(`${API_BASE.replace('/v1', '')}/api/incident-providers/${id}/test`, {
      method: 'POST',
    })
  }

  async getIncidentProviderRules(id: number): Promise<IncidentRoutingRule[]> {
    return this.request<IncidentRoutingRule[]>(`${API_BASE.replace('/v1', '')}/api/incident-providers/${id}/rules`)
  }

  async updateIncidentProviderRules(id: number, rules: UpsertRoutingRuleRequest[]): Promise<void> {
    await this.request(`${API_BASE.replace('/v1', '')}/api/incident-providers/${id}/rules`, {
      method: 'PUT',
      body: JSON.stringify(rules),
    })
  }

  async getIncidentProviderEvents(id: number, limit = 50): Promise<IncidentEventLogEntry[]> {
    return this.request<IncidentEventLogEntry[]>(`${API_BASE.replace('/v1', '')}/api/incident-providers/${id}/events?limit=${limit}`)
  }

  // Status Page Management Methods

  async getStatusPages() {
    return this.request<StatusPage[]>(`${API_BASE}/status-pages`)
  }

  async getStatusPage(pageId: string) {
    return this.request<StatusPageDetail>(`${API_BASE}/status-pages/${pageId}`)
  }

  async createStatusPage(data: CreateStatusPageRequest) {
    return this.request<StatusPage>(`${API_BASE}/status-pages`, {
      method: 'POST',
      body: JSON.stringify(data),
    })
  }

  async updateStatusPage(pageId: string, data: UpdateStatusPageRequest) {
    return this.request<StatusPage>(`${API_BASE}/status-pages/${pageId}`, {
      method: 'PUT',
      body: JSON.stringify(data),
    })
  }

  async deleteStatusPage(pageId: string) {
    return this.request<void>(`${API_BASE}/status-pages/${pageId}`, {
      method: 'DELETE',
    })
  }

  async addMonitorsToStatusPage(pageId: string, monitors: MonitorAssignment[]) {
    return this.request<StatusPageMonitor[]>(`${API_BASE}/status-pages/${pageId}/monitors`, {
      method: 'POST',
      body: JSON.stringify({ monitors }),
    })
  }

  async removeMonitorFromStatusPage(pageId: string, monitorId: string) {
    return this.request<void>(`${API_BASE}/status-pages/${pageId}/monitors/${monitorId}`, {
      method: 'DELETE',
    })
  }

  async getStatusPageIncidents(pageId: string) {
    return this.request<StatusPageIncident[]>(`${API_BASE}/status-pages/${pageId}/incidents`)
  }

  async createIncident(pageId: string, data: CreateIncidentRequest) {
    return this.request<StatusPageIncident>(`${API_BASE}/status-pages/${pageId}/incidents`, {
      method: 'POST',
      body: JSON.stringify(data),
    })
  }

  async updateIncident(pageId: string, incidentId: string, data: UpdateIncidentRequest) {
    return this.request<StatusPageIncident>(`${API_BASE}/status-pages/${pageId}/incidents/${incidentId}`, {
      method: 'PUT',
      body: JSON.stringify(data),
    })
  }

  async createIncidentUpdate(pageId: string, incidentId: string, data: CreateIncidentUpdateRequest) {
    return this.request<StatusPageIncident>(`${API_BASE}/status-pages/${pageId}/incidents/${incidentId}/updates`, {
      method: 'POST',
      body: JSON.stringify(data),
    })
  }

  async addCustomDomain(pageId: string, domain: string) {
    return this.request<CustomDomain>(`${API_BASE}/status-pages/${pageId}/domains`, {
      method: 'POST',
      body: JSON.stringify({ domain }),
    })
  }

  async verifyCustomDomain(pageId: string, domainId: number) {
    return this.request<CustomDomain>(`${API_BASE}/status-pages/${pageId}/domains/${domainId}/verify`, {
      method: 'POST',
    })
  }

  async removeCustomDomain(pageId: string, domainId: number) {
    return this.request<void>(`${API_BASE}/status-pages/${pageId}/domains/${domainId}`, {
      method: 'DELETE',
    })
  }

  // Public Status Page Methods (no auth required)

  async getPublicStatusPage(slug: string) {
    const publicUrl = `${import.meta.env.VITE_BACKEND_URL || 'https://api.moneat.io'}/public/status/${slug}`
    const response = await fetch(publicUrl)
    if (!response.ok) {
      throw new Error('Failed to fetch public status page')
    }
    return response.json() as Promise<PublicStatusPage>
  }

  async getPublicStatusPageByDomain(domain: string) {
    const publicUrl = `${import.meta.env.VITE_BACKEND_URL || 'https://api.moneat.io'}/public/status/domain/${domain}`
    const response = await fetch(publicUrl)
    if (!response.ok) {
      throw new Error('Failed to fetch public status page')
    }
    return response.json() as Promise<PublicStatusPage>
  }

  // On-Call Management

  // Priorities
  async getPriorities(): Promise<Priority[]> {
    return this.request(`${API_BASE}/priorities`)
  }

  async updatePriorities(request: UpdatePrioritiesRequest): Promise<Priority[]> {
    return this.request(`${API_BASE}/priorities`, {
      method: 'PUT',
      body: JSON.stringify(request),
    })
  }

  // Business Hours
  async getBusinessHours(): Promise<BusinessHours> {
    return this.request(`${API_BASE}/business-hours`)
  }

  async updateBusinessHours(request: UpdateBusinessHoursRequest): Promise<BusinessHours> {
    return this.request(`${API_BASE}/business-hours`, {
      method: 'PUT',
      body: JSON.stringify(request),
    })
  }

  // On-Call Schedules
  async getOnCallSchedules(): Promise<OnCallSchedule[]> {
    return this.request(`${API_BASE}/on-call/schedules`)
  }

  async getOnCallSchedule(id: number): Promise<OnCallSchedule> {
    return this.request(`${API_BASE}/on-call/schedules/${id}`)
  }

  async createOnCallSchedule(request: CreateOnCallScheduleRequest): Promise<OnCallSchedule> {
    return this.request(`${API_BASE}/on-call/schedules`, {
      method: 'POST',
      body: JSON.stringify(request),
    })
  }

  async updateOnCallSchedule(id: number, request: UpdateOnCallScheduleRequest): Promise<OnCallSchedule> {
    return this.request(`${API_BASE}/on-call/schedules/${id}`, {
      method: 'PUT',
      body: JSON.stringify(request),
    })
  }

  async deleteOnCallSchedule(id: number): Promise<void> {
    return this.request(`${API_BASE}/on-call/schedules/${id}`, {
      method: 'DELETE',
    })
  }

  async getCurrentOnCall(scheduleId: number): Promise<{ userId: number; userName: string }> {
    return this.request(`${API_BASE}/on-call/schedules/${scheduleId}/current`)
  }

  async createOverride(scheduleId: number, request: CreateOverrideRequest): Promise<OnCallOverride> {
    return this.request(`${API_BASE}/on-call/schedules/${scheduleId}/overrides`, {
      method: 'POST',
      body: JSON.stringify(request),
    })
  }

  async deleteOverride(overrideId: number): Promise<void> {
    return this.request(`${API_BASE}/on-call/overrides/${overrideId}`, {
      method: 'DELETE',
    })
  }

  // Escalation Policies
  async getEscalationPolicies(): Promise<EscalationPolicy[]> {
    return this.request(`${API_BASE}/escalation-policies`)
  }

  async getEscalationPolicy(id: number): Promise<EscalationPolicy> {
    return this.request(`${API_BASE}/escalation-policies/${id}`)
  }

  async createEscalationPolicy(request: CreateEscalationPolicyRequest): Promise<EscalationPolicy> {
    return this.request(`${API_BASE}/escalation-policies`, {
      method: 'POST',
      body: JSON.stringify(request),
    })
  }

  async updateEscalationPolicy(id: number, request: UpdateEscalationPolicyRequest): Promise<EscalationPolicy> {
    return this.request(`${API_BASE}/escalation-policies/${id}`, {
      method: 'PUT',
      body: JSON.stringify(request),
    })
  }

  async deleteEscalationPolicy(id: number): Promise<void> {
    return this.request(`${API_BASE}/escalation-policies/${id}`, {
      method: 'DELETE',
    })
  }

  // Incidents
  async getIncidents(filters?: IncidentListFilters): Promise<Incident[]> {
    const params = new URLSearchParams()
    if (filters?.status) params.append('status', filters.status)
    if (filters?.priorityLevel) params.append('priority', filters.priorityLevel)
    if (filters?.fromDate) params.append('fromDate', filters.fromDate)
    if (filters?.toDate) params.append('toDate', filters.toDate)
    
    const query = params.toString()
    return this.request(`${API_BASE}/incidents${query ? `?${query}` : ''}`)
  }

  async getIncident(id: number): Promise<IncidentDetail> {
    return this.request(`${API_BASE}/incidents/${id}`)
  }

  async getIncidentTimeline(id: number): Promise<IncidentTimeline[]> {
    return this.request(`${API_BASE}/incidents/${id}/timeline`)
  }

  async acknowledgeIncident(id: number): Promise<Incident> {
    return this.request(`${API_BASE}/incidents/${id}/acknowledge`, {
      method: 'POST',
    })
  }

  async resolveIncident(id: number): Promise<Incident> {
    return this.request(`${API_BASE}/incidents/${id}/resolve`, {
      method: 'POST',
    })
  }

  async reassignIncident(id: number, toUserId: number): Promise<Incident> {
    return this.request(`${API_BASE}/incidents/${id}/reassign`, {
      method: 'POST',
      body: JSON.stringify({ toUserId }),
    })
  }

  async addIncidentNote(id: number, note: string): Promise<IncidentTimeline> {
    return this.request(`${API_BASE}/incidents/${id}/notes`, {
      method: 'POST',
      body: JSON.stringify({ note }),
    })
  }

  async viewIncident(id: number): Promise<void> {
    await this.request(`${API_BASE}/incidents/${id}/view`, {
      method: 'POST',
    })
  }

  async markUnavailable(id: number): Promise<void> {
    await this.request(`${API_BASE}/incidents/${id}/unavailable`, {
      method: 'POST',
    })
  }

  // Device Tokens
  async registerDevice(request: RegisterDeviceRequest): Promise<DeviceToken> {
    return this.request(`${API_BASE}/devices`, {
      method: 'POST',
      body: JSON.stringify(request),
    })
  }

  async unregisterDevice(token: string): Promise<void> {
    return this.request(`${API_BASE}/devices/${encodeURIComponent(token)}`, {
      method: 'DELETE',
    })
  }

  // On-Call Incidents (Declared)
  async declareIncident(alertId: number, data: { title: string; description: string; priorityLevel: string }) {
    return this.request<{id: number}>(`${API_BASE}/incidents/${alertId}/declare`, {
      method: 'POST',
      body: JSON.stringify(data),
    })
  }

  async getOnCallIncidents(filters: { status?: string; priorityLevel?: string } = {}) {
    const params = new URLSearchParams()
    if (filters.status) params.set('status', filters.status)
    if (filters.priorityLevel) params.set('priorityLevel', filters.priorityLevel)
    return this.request<OnCallIncident[]>(`${API_BASE}/on-call-incidents?${params.toString()}`)
  }

  async getOnCallIncident(id: number) {
    return this.request<OnCallIncidentDetail>(`${API_BASE}/on-call-incidents/${id}`)
  }

  async resolveOnCallIncident(id: number) {
    return this.request<OnCallIncident>(`${API_BASE}/on-call-incidents/${id}/resolve`, {
      method: 'POST',
    })
  }
  
  async getOnCallIncidentTimeline(id: number) {
    return this.request<IncidentTimeline[]>(`${API_BASE}/on-call-incidents/${id}/timeline`)
  }
  
  async addOnCallIncidentNote(id: number, note: string) {
    return this.request<{ message: string }>(`${API_BASE}/on-call-incidents/${id}/notes`, {
      method: 'POST',
      body: JSON.stringify({ note }),
    })
  }

  // ── AI Chat ─────────────────────────────────────────────────────────────

  async sendChatMessage(conversationId: number | null, message: string, currentPage: string): Promise<AiChatResponse> {
    return this.request<AiChatResponse>(`${API_BASE}/ai/chat`, {
      method: 'POST',
      body: JSON.stringify({ conversationId, message, currentPage }),
    })
  }

  async executeAiAction(conversationId: number, actionId: string, params: Record<string, string> = {}): Promise<AiActionResult> {
    return this.request<AiActionResult>(`${API_BASE}/ai/execute-action`, {
      method: 'POST',
      body: JSON.stringify({ conversationId, actionId, params }),
    })
  }

  async getAiConversations(): Promise<AiConversationSummary[]> {
    return this.request<AiConversationSummary[]>(`${API_BASE}/ai/conversations`)
  }

  async getAiConversation(id: number): Promise<AiConversationDetail> {
    return this.request<AiConversationDetail>(`${API_BASE}/ai/conversations/${id}`)
  }

  async deleteAiConversation(id: number): Promise<void> {
    return this.request<void>(`${API_BASE}/ai/conversations/${id}`, { method: 'DELETE' })
  }

  // --- LLM Observability ---

  async getLlmOverview(projectId: number, range = '24h'): Promise<LlmOverviewResponse> {
    return this.request<LlmOverviewResponse>(`${API_BASE}/llm/overview?projectId=${projectId}&range=${range}`)
  }

  async getLlmGenerations(projectId: number, params: { range?: string; model?: string; provider?: string; type?: string; status?: string; page?: number; pageSize?: number } = {}): Promise<LlmGenerationsListResponse> {
    const searchParams = new URLSearchParams({ projectId: String(projectId) })
    if (params.range) searchParams.set('range', params.range)
    if (params.model) searchParams.set('model', params.model)
    if (params.provider) searchParams.set('provider', params.provider)
    if (params.type) searchParams.set('type', params.type)
    if (params.status) searchParams.set('status', params.status)
    if (params.page) searchParams.set('page', String(params.page))
    if (params.pageSize) searchParams.set('pageSize', String(params.pageSize))
    return this.request<LlmGenerationsListResponse>(`${API_BASE}/llm/generations?${searchParams}`)
  }

  async getLlmGenerationDetail(projectId: number, generationId: string): Promise<LlmGenerationDetail> {
    return this.request<LlmGenerationDetail>(`${API_BASE}/llm/generations/${generationId}?projectId=${projectId}`)
  }

  async getLlmTrace(projectId: number, traceId: string): Promise<LlmTraceResponse> {
    return this.request<LlmTraceResponse>(`${API_BASE}/llm/traces/${traceId}?projectId=${projectId}`)
  }

  async getLlmModels(projectId: number, range = '24h'): Promise<LlmModelStats[]> {
    return this.request<LlmModelStats[]>(`${API_BASE}/llm/models?projectId=${projectId}&range=${range}`)
  }

  async getLlmCosts(projectId: number, range = '24h'): Promise<LlmCostsResponse> {
    return this.request<LlmCostsResponse>(`${API_BASE}/llm/costs?projectId=${projectId}&range=${range}`)
  }

  async updatePhoneNumber(phoneNumber: string) {
    return this.request<{message: string}>(`${API_BASE}/user/phone-number`, {
      method: 'PUT',
      body: JSON.stringify({ phoneNumber }),
    })
  }

  async deletePhoneNumber() {
    return this.request<{message: string}>(`${API_BASE}/user/phone-number`, {
      method: 'DELETE',
    })
  }

  async getCallerNumber() {
    return this.request<{phoneNumber: string | null}>(`${API_BASE}/on-call/caller-number`)
  }

  async testSmsCall(channel: 'sms' | 'call') {
    return this.request<{success: boolean}>(`${API_BASE}/admin/test-sms-call`, {
      method: 'POST',
      body: JSON.stringify({ channel }),
    })
  }

  async getOnCallContact() {
    return this.request<OnCallContactSettings>(`${API_BASE}/user/on-call-contact`)
  }

  async updateOnCallContact(req: { phoneNumber: string; consentAccepted: boolean; consentVersion: string }) {
    return this.request<{message: string}>(`${API_BASE}/user/on-call-contact`, {
      method: 'PUT',
      body: JSON.stringify(req),
    })
  }

  async deleteOnCallContact() {
    return this.request<{message: string}>(`${API_BASE}/user/on-call-contact`, {
      method: 'DELETE',
    })
  }

  // --- Product Analytics ---

  private buildAnalyticsQuery(params?: AnalyticsParams): string {
    const qs = new URLSearchParams()
    if (params?.period) qs.append('period', params.period)
    if (params?.from) qs.append('from', params.from)
    if (params?.to) qs.append('to', params.to)
    if (params?.comparison && params.comparison !== 'none') qs.append('comparison', params.comparison)
    if (params?.filters) {
      for (const f of params.filters) {
        qs.append('filters[]', `${f.property}:${f.operator}:${f.value}`)
      }
    }
    const s = qs.toString()
    return s ? `?${s}` : ''
  }

  async getAnalyticsOverview(projectId: number, params?: AnalyticsParams): Promise<AnalyticsOverview> {
    return this.request<AnalyticsOverview>(`${API_BASE}/analytics/${projectId}/overview${this.buildAnalyticsQuery(params)}`)
  }

  async getAnalyticsTimeseries(projectId: number, params?: AnalyticsParams): Promise<AnalyticsTimeseriesPoint[]> {
    return this.request<AnalyticsTimeseriesPoint[]>(`${API_BASE}/analytics/${projectId}/timeseries${this.buildAnalyticsQuery(params)}`)
  }

  async getAnalyticsPages(projectId: number, params?: AnalyticsParams): Promise<AnalyticsBreakdownItem[]> {
    return this.request<AnalyticsBreakdownItem[]>(`${API_BASE}/analytics/${projectId}/pages${this.buildAnalyticsQuery(params)}`)
  }

  async getAnalyticsEntryPages(projectId: number, params?: AnalyticsParams): Promise<AnalyticsBreakdownItem[]> {
    return this.request<AnalyticsBreakdownItem[]>(`${API_BASE}/analytics/${projectId}/entry-pages${this.buildAnalyticsQuery(params)}`)
  }

  async getAnalyticsExitPages(projectId: number, params?: AnalyticsParams): Promise<AnalyticsBreakdownItem[]> {
    return this.request<AnalyticsBreakdownItem[]>(`${API_BASE}/analytics/${projectId}/exit-pages${this.buildAnalyticsQuery(params)}`)
  }

  async getAnalyticsSources(projectId: number, params?: AnalyticsParams): Promise<AnalyticsBreakdownItem[]> {
    return this.request<AnalyticsBreakdownItem[]>(`${API_BASE}/analytics/${projectId}/sources${this.buildAnalyticsQuery(params)}`)
  }

  async getAnalyticsUtm(projectId: number, utmParam: string, params?: AnalyticsParams): Promise<AnalyticsBreakdownItem[]> {
    return this.request<AnalyticsBreakdownItem[]>(`${API_BASE}/analytics/${projectId}/utm/${utmParam}${this.buildAnalyticsQuery(params)}`)
  }

  async getAnalyticsLocations(projectId: number, params?: AnalyticsParams): Promise<AnalyticsBreakdownItem[]> {
    return this.request<AnalyticsBreakdownItem[]>(`${API_BASE}/analytics/${projectId}/locations${this.buildAnalyticsQuery(params)}`)
  }

  async getAnalyticsDevices(projectId: number, type: 'browser' | 'os' | 'device', params?: AnalyticsParams): Promise<AnalyticsBreakdownItem[]> {
    const qs = this.buildAnalyticsQuery(params)
    return this.request<AnalyticsBreakdownItem[]>(`${API_BASE}/analytics/${projectId}/devices${qs}${qs ? '&' : '?'}type=${type}`)
  }

  async getAnalyticsEvents(projectId: number, params?: AnalyticsParams): Promise<AnalyticsBreakdownItem[]> {
    return this.request<AnalyticsBreakdownItem[]>(`${API_BASE}/analytics/${projectId}/events${this.buildAnalyticsQuery(params)}`)
  }

  async getAnalyticsRealtime(projectId: number): Promise<AnalyticsRealtimeResponse> {
    return this.request<AnalyticsRealtimeResponse>(`${API_BASE}/analytics/${projectId}/realtime`)
  }

  async getAnalyticsFunnel(projectId: number, steps: string[], params?: AnalyticsParams): Promise<AnalyticsFunnelResponse> {
    const qs = this.buildAnalyticsQuery(params)
    const sep = qs ? '&' : '?'
    const stepsParam = steps.map(s => `steps[]=${encodeURIComponent(s)}`).join('&')
    return this.request<AnalyticsFunnelResponse>(`${API_BASE}/analytics/${projectId}/funnel${qs}${sep}${stepsParam}`)
  }
}

export const api = new ApiClient()
export type {
  AuthResponse,
  Project,
  ProjectKey,
  Issue,
  IssueDetail,
  Event,
  ProjectStats,
  TimelinePoint,
  TopIssue,
  IssueTransaction,
  TransactionSummary,
  TransactionDetail,
  Span,
  TransactionWithSpans,
  TraceDetail,
  SpanDetail,
  SlowTransaction,
  PerformanceStats,
  Release,
  ReleaseStats,
  Replay,
  ReplayDetail,
  ReplayRecordingResponse,
  ReplayTimelineItem,
  ReplayTimelineResponse,
  LogEntry,
  LogQueryResponse,
  LogFilterOptions,
  LogFilterOptionsWithCounts,
  LogFilterOptionWithCount,
  LogAggregateBucket,
  LogAggregateResponse,
  LogTopValue,
  LogTopResponse,
  SdkVersionsResponse,
  AuthToken,
  BillingTierConfig,
  BillingPlan,
  BillingPlansResponse,
  BillingUsage,
  CheckoutSessionRequest,
  CheckoutSessionResponse,
  Invoice,
  PaymentMethod,
  SetupIntentResponse,
  CancelSubscriptionResponse,
  AdminBillingSubscription,
  CreateTierVersionRequest,
  TierMigrationResponse,
  NotificationPreference,
  ProjectNotificationPreference,
  NotificationPreferences,
  MonitorSystem,
  MonitorSystemWithMetrics,
  MonitorSystemDetail,
  HistoricalDataPoint,
  SystemMetricsHistory,
  ContainerStats,
  ContainerHistoricalDataPoint,
  ContainerMetricsHistory,
  SystemAlert,
  SystemAlertConfig,
  UptimeMonitor,
  CreateUptimeMonitorRequest,
  UpdateUptimeMonitorRequest,
  UptimeHeartbeat,
  OrganizationIntegration,
  SlackOAuthStartResponse,
  SlackChannel,
  SlackChannelList,
  SlackChannelSelection,
  SlackUsergroup,
  UpdateSlackIntegrationRequest,
  TestIntegrationResponse,
  IncidentProviderConfig,
  CreateIncidentProviderRequest,
  UpdateIncidentProviderRequest,
  IncidentRoutingRule,
  UpsertRoutingRuleRequest,
  IncidentEventLogEntry,
  StatusPage,
  StatusPageDetail,
  StatusPageMonitor,
  MonitorAssignment,
  StatusPageIncident,
  IncidentUpdate,
  CustomDomain,
  PublicStatusPage,
  PublicMonitorStatus,
  UptimeDataPoint,
  CreateStatusPageRequest,
  UpdateStatusPageRequest,
  CreateIncidentRequest,
  UpdateIncidentRequest,
  CreateIncidentUpdateRequest,
  AddCustomDomainRequest,
  OrgMember,
  OrgInvitation,
  OrgMembersResponse,
  InvitationDetailsResponse,
  BulkInviteResult,
  Priority,
  BusinessHours,
  BusinessHoursWindow,
  OnCallSchedule,
  OnCallParticipant,
  OnCallOverride,
  EscalationPolicy,
  EscalationStep,
  EscalationTarget,
  Incident,
  IncidentTimeline,
  IncidentDetail,
  OnCallIncident,
  OnCallIncidentDetail,
  DeviceToken,
  CreateOnCallScheduleRequest,
  UpdateOnCallScheduleRequest,
  CreateOverrideRequest,
  CreateEscalationPolicyRequest,
  UpdateEscalationPolicyRequest,
  UpdatePrioritiesRequest,
  UpdateBusinessHoursRequest,
  RegisterDeviceRequest,
  IncidentListFilters,
  SilencePeriod,
  CreateSilencePeriodRequest,
  AiChatResponse,
  AiChatResponseData,
  AiAction,
  AiClarification,
  AiDataQuery,
  AiLink,
  AiActionResult,
  AiConversationSummary,
  AiConversationDetail,
  AiMessageDto,
  LlmTimelinePoint,
  LlmModelStats,
  LlmOverviewResponse,
  LlmGeneration,
  LlmGenerationDetail,
  LlmGenerationsListResponse,
  LlmTraceResponse,
  LlmCostBreakdown,
  LlmCostsResponse,
  OnCallContactSettings,
  AnalyticsPeriod,
  AnalyticsComparison,
  AnalyticsFilter,
  AnalyticsParams,
  AnalyticsOverview,
  AnalyticsTimeseriesPoint,
  AnalyticsBreakdownItem,
  AnalyticsRealtimeResponse,
  AnalyticsFunnelStep,
  AnalyticsFunnelResponse,
}
