-- Add stable UUID public identifiers for remaining user-facing resources.
-- Numeric primary keys remain internal for joins and service internals.

ALTER TABLE users ADD COLUMN IF NOT EXISTS resource_id UUID NOT NULL DEFAULT gen_random_uuid();
ALTER TABLE organizations ADD COLUMN IF NOT EXISTS resource_id UUID NOT NULL DEFAULT gen_random_uuid();
ALTER TABLE memberships ADD COLUMN IF NOT EXISTS resource_id UUID NOT NULL DEFAULT gen_random_uuid();
ALTER TABLE org_invitations ADD COLUMN IF NOT EXISTS resource_id UUID NOT NULL DEFAULT gen_random_uuid();
ALTER TABLE auth_tokens ADD COLUMN IF NOT EXISTS resource_id UUID NOT NULL DEFAULT gen_random_uuid();
ALTER TABLE otlp_api_keys ADD COLUMN IF NOT EXISTS resource_id UUID NOT NULL DEFAULT gen_random_uuid();
ALTER TABLE otel_service_project_mappings ADD COLUMN IF NOT EXISTS resource_id UUID NOT NULL DEFAULT gen_random_uuid();
ALTER TABLE otel_observed_services ADD COLUMN IF NOT EXISTS resource_id UUID NOT NULL DEFAULT gen_random_uuid();
ALTER TABLE mcp_api_keys ADD COLUMN IF NOT EXISTS resource_id UUID NOT NULL DEFAULT gen_random_uuid();
ALTER TABLE pricing_tier_configs ADD COLUMN IF NOT EXISTS resource_id UUID NOT NULL DEFAULT gen_random_uuid();
ALTER TABLE subscriptions ADD COLUMN IF NOT EXISTS resource_id UUID NOT NULL DEFAULT gen_random_uuid();
ALTER TABLE promotional_credit_grants ADD COLUMN IF NOT EXISTS resource_id UUID NOT NULL DEFAULT gen_random_uuid();
ALTER TABLE sso_configurations ADD COLUMN IF NOT EXISTS resource_id UUID NOT NULL DEFAULT gen_random_uuid();

ALTER TABLE feature_flag_environments ADD COLUMN IF NOT EXISTS resource_id UUID NOT NULL DEFAULT gen_random_uuid();
ALTER TABLE feature_flags ADD COLUMN IF NOT EXISTS resource_id UUID NOT NULL DEFAULT gen_random_uuid();
ALTER TABLE feature_flag_variants ADD COLUMN IF NOT EXISTS resource_id UUID NOT NULL DEFAULT gen_random_uuid();
ALTER TABLE feature_flag_segments ADD COLUMN IF NOT EXISTS resource_id UUID NOT NULL DEFAULT gen_random_uuid();
ALTER TABLE feature_flag_sdk_keys ADD COLUMN IF NOT EXISTS resource_id UUID NOT NULL DEFAULT gen_random_uuid();
ALTER TABLE feature_flag_audit_events ADD COLUMN IF NOT EXISTS resource_id UUID NOT NULL DEFAULT gen_random_uuid();

ALTER TABLE release_files ADD COLUMN IF NOT EXISTS resource_id UUID NOT NULL DEFAULT gen_random_uuid();
ALTER TABLE synthetic_variables ADD COLUMN IF NOT EXISTS resource_id UUID NOT NULL DEFAULT gen_random_uuid();
ALTER TABLE hosts ADD COLUMN IF NOT EXISTS resource_id UUID NOT NULL DEFAULT gen_random_uuid();
ALTER TABLE host_alerts ADD COLUMN IF NOT EXISTS resource_id UUID NOT NULL DEFAULT gen_random_uuid();
ALTER TABLE organization_alert_templates ADD COLUMN IF NOT EXISTS resource_id UUID NOT NULL DEFAULT gen_random_uuid();
ALTER TABLE alert_silence_periods ADD COLUMN IF NOT EXISTS resource_id UUID NOT NULL DEFAULT gen_random_uuid();
ALTER TABLE agent_api_keys ADD COLUMN IF NOT EXISTS resource_id UUID NOT NULL DEFAULT gen_random_uuid();
ALTER TABLE cloud_sources ADD COLUMN IF NOT EXISTS resource_id UUID NOT NULL DEFAULT gen_random_uuid();
ALTER TABLE infrastructure_map_saved_views ADD COLUMN IF NOT EXISTS resource_id UUID NOT NULL DEFAULT gen_random_uuid();

ALTER TABLE escalation_policies ADD COLUMN IF NOT EXISTS resource_id UUID NOT NULL DEFAULT gen_random_uuid();
ALTER TABLE escalation_steps ADD COLUMN IF NOT EXISTS resource_id UUID NOT NULL DEFAULT gen_random_uuid();
ALTER TABLE escalation_step_targets ADD COLUMN IF NOT EXISTS resource_id UUID NOT NULL DEFAULT gen_random_uuid();
ALTER TABLE escalation_policy_alert_sources ADD COLUMN IF NOT EXISTS resource_id UUID NOT NULL DEFAULT gen_random_uuid();
ALTER TABLE alert_priorities ADD COLUMN IF NOT EXISTS resource_id UUID NOT NULL DEFAULT gen_random_uuid();
ALTER TABLE business_hours ADD COLUMN IF NOT EXISTS resource_id UUID NOT NULL DEFAULT gen_random_uuid();
ALTER TABLE business_hours_windows ADD COLUMN IF NOT EXISTS resource_id UUID NOT NULL DEFAULT gen_random_uuid();
ALTER TABLE on_call_schedules ADD COLUMN IF NOT EXISTS resource_id UUID NOT NULL DEFAULT gen_random_uuid();
ALTER TABLE on_call_participants ADD COLUMN IF NOT EXISTS resource_id UUID NOT NULL DEFAULT gen_random_uuid();
ALTER TABLE on_call_overrides ADD COLUMN IF NOT EXISTS resource_id UUID NOT NULL DEFAULT gen_random_uuid();
ALTER TABLE on_call_schedule_usergroups ADD COLUMN IF NOT EXISTS resource_id UUID NOT NULL DEFAULT gen_random_uuid();
ALTER TABLE on_call_incidents ADD COLUMN IF NOT EXISTS resource_id UUID NOT NULL DEFAULT gen_random_uuid();
ALTER TABLE on_call_incident_alerts ADD COLUMN IF NOT EXISTS resource_id UUID NOT NULL DEFAULT gen_random_uuid();
ALTER TABLE on_call_incident_timeline ADD COLUMN IF NOT EXISTS resource_id UUID NOT NULL DEFAULT gen_random_uuid();
ALTER TABLE on_call_alerts ADD COLUMN IF NOT EXISTS resource_id UUID NOT NULL DEFAULT gen_random_uuid();
ALTER TABLE on_call_alert_timeline ADD COLUMN IF NOT EXISTS resource_id UUID NOT NULL DEFAULT gen_random_uuid();
ALTER TABLE user_device_tokens ADD COLUMN IF NOT EXISTS resource_id UUID NOT NULL DEFAULT gen_random_uuid();
ALTER TABLE slack_user_mappings ADD COLUMN IF NOT EXISTS resource_id UUID NOT NULL DEFAULT gen_random_uuid();

ALTER TABLE workflows ADD COLUMN IF NOT EXISTS resource_id UUID NOT NULL DEFAULT gen_random_uuid();
ALTER TABLE workflow_versions ADD COLUMN IF NOT EXISTS resource_id UUID NOT NULL DEFAULT gen_random_uuid();
ALTER TABLE workflow_runs ADD COLUMN IF NOT EXISTS resource_id UUID NOT NULL DEFAULT gen_random_uuid();
ALTER TABLE workflow_run_steps ADD COLUMN IF NOT EXISTS resource_id UUID NOT NULL DEFAULT gen_random_uuid();
ALTER TABLE workflow_audit_events ADD COLUMN IF NOT EXISTS resource_id UUID NOT NULL DEFAULT gen_random_uuid();
ALTER TABLE workflow_connections ADD COLUMN IF NOT EXISTS resource_id UUID NOT NULL DEFAULT gen_random_uuid();
ALTER TABLE workflow_connection_groups ADD COLUMN IF NOT EXISTS resource_id UUID NOT NULL DEFAULT gen_random_uuid();
ALTER TABLE workflow_approvals ADD COLUMN IF NOT EXISTS resource_id UUID NOT NULL DEFAULT gen_random_uuid();

ALTER TABLE rbac_roles ADD COLUMN IF NOT EXISTS resource_id UUID NOT NULL DEFAULT gen_random_uuid();
ALTER TABLE rbac_role_assignments ADD COLUMN IF NOT EXISTS resource_id UUID NOT NULL DEFAULT gen_random_uuid();

ALTER TABLE detection_rules ADD COLUMN IF NOT EXISTS resource_id UUID NOT NULL DEFAULT gen_random_uuid();
ALTER TABLE security_signals ADD COLUMN IF NOT EXISTS resource_id UUID NOT NULL DEFAULT gen_random_uuid();
ALTER TABLE security_signal_evidence ADD COLUMN IF NOT EXISTS resource_id UUID NOT NULL DEFAULT gen_random_uuid();
ALTER TABLE security_signal_audit ADD COLUMN IF NOT EXISTS resource_id UUID NOT NULL DEFAULT gen_random_uuid();
ALTER TABLE alert_episodes ADD COLUMN IF NOT EXISTS resource_id UUID NOT NULL DEFAULT gen_random_uuid();

ALTER TABLE incident_provider_configs ADD COLUMN IF NOT EXISTS resource_id UUID NOT NULL DEFAULT gen_random_uuid();
ALTER TABLE incident_routing_rules ADD COLUMN IF NOT EXISTS resource_id UUID NOT NULL DEFAULT gen_random_uuid();
ALTER TABLE incident_event_log ADD COLUMN IF NOT EXISTS resource_id UUID NOT NULL DEFAULT gen_random_uuid();
ALTER TABLE status_page_monitors ADD COLUMN IF NOT EXISTS resource_id UUID NOT NULL DEFAULT gen_random_uuid();
ALTER TABLE status_page_custom_domains ADD COLUMN IF NOT EXISTS resource_id UUID NOT NULL DEFAULT gen_random_uuid();
ALTER TABLE ai_conversations ADD COLUMN IF NOT EXISTS resource_id UUID NOT NULL DEFAULT gen_random_uuid();
ALTER TABLE ai_messages ADD COLUMN IF NOT EXISTS resource_id UUID NOT NULL DEFAULT gen_random_uuid();
ALTER TABLE ai_context_snapshots ADD COLUMN IF NOT EXISTS resource_id UUID NOT NULL DEFAULT gen_random_uuid();

ALTER TABLE log_indexes ADD COLUMN IF NOT EXISTS resource_id UUID NOT NULL DEFAULT gen_random_uuid();
ALTER TABLE log_pipelines ADD COLUMN IF NOT EXISTS resource_id UUID NOT NULL DEFAULT gen_random_uuid();
ALTER TABLE log_saved_views ADD COLUMN IF NOT EXISTS resource_id UUID NOT NULL DEFAULT gen_random_uuid();
ALTER TABLE log_metric_rules ADD COLUMN IF NOT EXISTS resource_id UUID NOT NULL DEFAULT gen_random_uuid();
ALTER TABLE log_monitors ADD COLUMN IF NOT EXISTS resource_id UUID NOT NULL DEFAULT gen_random_uuid();

DROP INDEX IF EXISTS idx_dashboard_folders_resource_id;
DROP INDEX IF EXISTS idx_dashboards_resource_id;
DROP INDEX IF EXISTS idx_dashboard_widgets_resource_id;
DROP INDEX IF EXISTS idx_dashboard_widget_alerts_resource_id;
DROP INDEX IF EXISTS idx_custom_data_sources_resource_id;

CREATE UNIQUE INDEX IF NOT EXISTS idx_users_resource_id ON users(resource_id);
CREATE UNIQUE INDEX IF NOT EXISTS idx_organizations_resource_id ON organizations(resource_id);
CREATE UNIQUE INDEX IF NOT EXISTS idx_memberships_org_resource_id ON memberships(organization_id, resource_id);
CREATE UNIQUE INDEX IF NOT EXISTS idx_org_invitations_org_resource_id ON org_invitations(organization_id, resource_id);
CREATE UNIQUE INDEX IF NOT EXISTS idx_auth_tokens_user_resource_id ON auth_tokens(user_id, resource_id);
CREATE UNIQUE INDEX IF NOT EXISTS idx_otlp_api_keys_org_resource_id ON otlp_api_keys(organization_id, resource_id);
CREATE UNIQUE INDEX IF NOT EXISTS idx_otel_service_mappings_org_resource_id
    ON otel_service_project_mappings(organization_id, resource_id);
CREATE UNIQUE INDEX IF NOT EXISTS idx_otel_observed_services_org_resource_id
    ON otel_observed_services(organization_id, resource_id);
CREATE UNIQUE INDEX IF NOT EXISTS idx_mcp_api_keys_org_resource_id ON mcp_api_keys(organization_id, resource_id);
CREATE UNIQUE INDEX IF NOT EXISTS idx_pricing_tier_configs_resource_id ON pricing_tier_configs(resource_id);
CREATE UNIQUE INDEX IF NOT EXISTS idx_subscriptions_org_resource_id ON subscriptions(organization_id, resource_id);
CREATE UNIQUE INDEX IF NOT EXISTS idx_promotional_credit_grants_org_resource_id
    ON promotional_credit_grants(organization_id, resource_id);
CREATE UNIQUE INDEX IF NOT EXISTS idx_sso_configurations_org_resource_id
    ON sso_configurations(organization_id, resource_id);

CREATE UNIQUE INDEX IF NOT EXISTS idx_ff_envs_org_resource_id ON feature_flag_environments(organization_id, resource_id);
CREATE UNIQUE INDEX IF NOT EXISTS idx_ff_flags_org_resource_id ON feature_flags(organization_id, resource_id);
CREATE UNIQUE INDEX IF NOT EXISTS idx_ff_variants_flag_resource_id ON feature_flag_variants(flag_id, resource_id);
CREATE UNIQUE INDEX IF NOT EXISTS idx_ff_segments_org_resource_id ON feature_flag_segments(organization_id, resource_id);
CREATE UNIQUE INDEX IF NOT EXISTS idx_ff_sdk_keys_org_resource_id ON feature_flag_sdk_keys(organization_id, resource_id);
CREATE UNIQUE INDEX IF NOT EXISTS idx_ff_audit_org_resource_id ON feature_flag_audit_events(organization_id, resource_id);

CREATE UNIQUE INDEX IF NOT EXISTS idx_release_files_release_resource_id ON release_files(release_id, resource_id);
CREATE UNIQUE INDEX IF NOT EXISTS idx_synthetic_variables_org_resource_id
    ON synthetic_variables(organization_id, resource_id);
CREATE UNIQUE INDEX IF NOT EXISTS idx_hosts_org_resource_id ON hosts(organization_id, resource_id);
CREATE UNIQUE INDEX IF NOT EXISTS idx_host_alerts_org_resource_id ON host_alerts(organization_id, resource_id);
CREATE UNIQUE INDEX IF NOT EXISTS idx_org_alert_templates_org_resource_id
    ON organization_alert_templates(organization_id, resource_id);
CREATE UNIQUE INDEX IF NOT EXISTS idx_alert_silence_periods_org_resource_id
    ON alert_silence_periods(organization_id, resource_id);
CREATE UNIQUE INDEX IF NOT EXISTS idx_agent_api_keys_org_resource_id ON agent_api_keys(organization_id, resource_id);
CREATE UNIQUE INDEX IF NOT EXISTS idx_cloud_sources_org_resource_id ON cloud_sources(organization_id, resource_id);
CREATE UNIQUE INDEX IF NOT EXISTS idx_infra_map_saved_views_org_resource_id
    ON infrastructure_map_saved_views(organization_id, resource_id);

CREATE UNIQUE INDEX IF NOT EXISTS idx_escalation_policies_org_resource_id
    ON escalation_policies(organization_id, resource_id);
CREATE UNIQUE INDEX IF NOT EXISTS idx_escalation_steps_policy_resource_id
    ON escalation_steps(escalation_policy_id, resource_id);
CREATE UNIQUE INDEX IF NOT EXISTS idx_escalation_step_targets_step_resource_id
    ON escalation_step_targets(escalation_step_id, resource_id);
CREATE UNIQUE INDEX IF NOT EXISTS idx_escalation_policy_sources_org_resource_id
    ON escalation_policy_alert_sources(organization_id, resource_id);
CREATE UNIQUE INDEX IF NOT EXISTS idx_alert_priorities_org_resource_id
    ON alert_priorities(organization_id, resource_id);
CREATE UNIQUE INDEX IF NOT EXISTS idx_business_hours_org_resource_id
    ON business_hours(organization_id, resource_id);
CREATE UNIQUE INDEX IF NOT EXISTS idx_business_hours_windows_hours_resource_id
    ON business_hours_windows(business_hours_id, resource_id);
CREATE UNIQUE INDEX IF NOT EXISTS idx_on_call_schedules_org_resource_id
    ON on_call_schedules(organization_id, resource_id);
CREATE UNIQUE INDEX IF NOT EXISTS idx_on_call_participants_schedule_resource_id
    ON on_call_participants(schedule_id, resource_id);
CREATE UNIQUE INDEX IF NOT EXISTS idx_on_call_overrides_schedule_resource_id
    ON on_call_overrides(schedule_id, resource_id);
CREATE UNIQUE INDEX IF NOT EXISTS idx_on_call_schedule_usergroups_schedule_resource_id
    ON on_call_schedule_usergroups(schedule_id, resource_id);
CREATE UNIQUE INDEX IF NOT EXISTS idx_on_call_incidents_org_resource_id
    ON on_call_incidents(organization_id, resource_id);
CREATE UNIQUE INDEX IF NOT EXISTS idx_on_call_incident_alerts_incident_resource_id
    ON on_call_incident_alerts(incident_id, resource_id);
CREATE UNIQUE INDEX IF NOT EXISTS idx_on_call_incident_timeline_incident_resource_id
    ON on_call_incident_timeline(incident_id, resource_id);
CREATE UNIQUE INDEX IF NOT EXISTS idx_on_call_alerts_org_resource_id
    ON on_call_alerts(organization_id, resource_id);
CREATE UNIQUE INDEX IF NOT EXISTS idx_on_call_alert_timeline_alert_resource_id
    ON on_call_alert_timeline(alert_id, resource_id);
CREATE UNIQUE INDEX IF NOT EXISTS idx_user_device_tokens_user_resource_id
    ON user_device_tokens(user_id, resource_id);
CREATE UNIQUE INDEX IF NOT EXISTS idx_slack_user_mappings_user_resource_id
    ON slack_user_mappings(user_id, resource_id);

CREATE UNIQUE INDEX IF NOT EXISTS idx_workflows_org_resource_id ON workflows(organization_id, resource_id);
CREATE UNIQUE INDEX IF NOT EXISTS idx_workflow_versions_workflow_resource_id
    ON workflow_versions(workflow_id, resource_id);
CREATE UNIQUE INDEX IF NOT EXISTS idx_workflow_runs_org_resource_id ON workflow_runs(organization_id, resource_id);
CREATE UNIQUE INDEX IF NOT EXISTS idx_workflow_run_steps_run_resource_id ON workflow_run_steps(run_id, resource_id);
CREATE UNIQUE INDEX IF NOT EXISTS idx_workflow_audit_events_org_resource_id
    ON workflow_audit_events(organization_id, resource_id);
CREATE UNIQUE INDEX IF NOT EXISTS idx_workflow_connections_org_resource_id
    ON workflow_connections(organization_id, resource_id);
CREATE UNIQUE INDEX IF NOT EXISTS idx_workflow_connection_groups_org_resource_id
    ON workflow_connection_groups(organization_id, resource_id);
CREATE UNIQUE INDEX IF NOT EXISTS idx_workflow_approvals_org_resource_id
    ON workflow_approvals(organization_id, resource_id);

CREATE UNIQUE INDEX IF NOT EXISTS idx_rbac_roles_org_resource_id ON rbac_roles(organization_id, resource_id);
CREATE UNIQUE INDEX IF NOT EXISTS idx_rbac_assignments_org_resource_id
    ON rbac_role_assignments(organization_id, resource_id);

CREATE UNIQUE INDEX IF NOT EXISTS idx_detection_rules_org_resource_id ON detection_rules(organization_id, resource_id);
CREATE UNIQUE INDEX IF NOT EXISTS idx_security_signals_org_resource_id ON security_signals(organization_id, resource_id);
CREATE UNIQUE INDEX IF NOT EXISTS idx_security_signal_evidence_signal_resource_id
    ON security_signal_evidence(signal_id, resource_id);
CREATE UNIQUE INDEX IF NOT EXISTS idx_security_signal_audit_signal_resource_id
    ON security_signal_audit(signal_id, resource_id);
CREATE UNIQUE INDEX IF NOT EXISTS idx_alert_episodes_org_resource_id ON alert_episodes(organization_id, resource_id);

CREATE UNIQUE INDEX IF NOT EXISTS idx_incident_provider_configs_org_resource_id
    ON incident_provider_configs(organization_id, resource_id);
CREATE UNIQUE INDEX IF NOT EXISTS idx_incident_routing_rules_provider_resource_id
    ON incident_routing_rules(provider_config_id, resource_id);
CREATE UNIQUE INDEX IF NOT EXISTS idx_incident_event_log_org_resource_id ON incident_event_log(organization_id, resource_id);
CREATE UNIQUE INDEX IF NOT EXISTS idx_status_page_monitors_page_resource_id
    ON status_page_monitors(status_page_id, resource_id);
CREATE UNIQUE INDEX IF NOT EXISTS idx_status_page_domains_page_resource_id
    ON status_page_custom_domains(status_page_id, resource_id);
CREATE UNIQUE INDEX IF NOT EXISTS idx_ai_conversations_org_resource_id ON ai_conversations(organization_id, resource_id);
CREATE UNIQUE INDEX IF NOT EXISTS idx_ai_messages_conversation_resource_id
    ON ai_messages(conversation_id, resource_id);
CREATE UNIQUE INDEX IF NOT EXISTS idx_ai_context_snapshots_user_resource_id
    ON ai_context_snapshots(user_id, resource_id);

CREATE UNIQUE INDEX IF NOT EXISTS idx_log_indexes_org_resource_id ON log_indexes(organization_id, resource_id);
CREATE UNIQUE INDEX IF NOT EXISTS idx_log_pipelines_org_resource_id ON log_pipelines(organization_id, resource_id);
CREATE UNIQUE INDEX IF NOT EXISTS idx_log_saved_views_org_resource_id ON log_saved_views(organization_id, resource_id);
CREATE UNIQUE INDEX IF NOT EXISTS idx_log_metric_rules_org_resource_id ON log_metric_rules(organization_id, resource_id);
CREATE UNIQUE INDEX IF NOT EXISTS idx_log_monitors_org_resource_id ON log_monitors(organization_id, resource_id);

CREATE UNIQUE INDEX IF NOT EXISTS idx_dashboard_folders_org_resource_id ON dashboard_folders(org_id, resource_id);
CREATE UNIQUE INDEX IF NOT EXISTS idx_dashboards_org_resource_id ON dashboards(org_id, resource_id);
CREATE UNIQUE INDEX IF NOT EXISTS idx_dashboard_widgets_dashboard_resource_id
    ON dashboard_widgets(dashboard_id, resource_id);
CREATE UNIQUE INDEX IF NOT EXISTS idx_dashboard_widget_alerts_dashboard_resource_id
    ON dashboard_widget_alerts(dashboard_id, resource_id);
CREATE UNIQUE INDEX IF NOT EXISTS idx_custom_data_sources_org_resource_id ON custom_data_sources(org_id, resource_id);
