CREATE TABLE workflows (
    id SERIAL PRIMARY KEY,
    organization_id INTEGER NOT NULL REFERENCES organizations(id) ON DELETE CASCADE,
    name VARCHAR(255) NOT NULL,
    trigger_name VARCHAR(120) NOT NULL,
    enabled BOOLEAN NOT NULL DEFAULT TRUE,
    system_key VARCHAR(120),
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE INDEX idx_workflows_org_trigger
    ON workflows (organization_id, trigger_name)
    WHERE enabled = TRUE;

CREATE UNIQUE INDEX idx_workflows_org_system_key
    ON workflows (organization_id, system_key)
    WHERE system_key IS NOT NULL;

CREATE TABLE workflow_versions (
    id SERIAL PRIMARY KEY,
    workflow_id INTEGER NOT NULL REFERENCES workflows(id) ON DELETE CASCADE,
    version INTEGER NOT NULL,
    conditions JSONB NOT NULL DEFAULT '[]'::JSONB,
    steps JSONB NOT NULL DEFAULT '[]'::JSONB,
    once_for_template JSONB NOT NULL DEFAULT '[]'::JSONB,
    engine_config JSONB NOT NULL DEFAULT '{}'::JSONB,
    most_recent BOOLEAN NOT NULL DEFAULT TRUE,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    UNIQUE (workflow_id, version)
);

CREATE UNIQUE INDEX idx_workflow_versions_one_recent
    ON workflow_versions (workflow_id)
    WHERE most_recent = TRUE;

CREATE TABLE workflow_runs (
    id SERIAL PRIMARY KEY,
    workflow_id INTEGER NOT NULL REFERENCES workflows(id) ON DELETE CASCADE,
    workflow_version_id INTEGER NOT NULL REFERENCES workflow_versions(id) ON DELETE RESTRICT,
    organization_id INTEGER NOT NULL REFERENCES organizations(id) ON DELETE CASCADE,
    trigger_name VARCHAR(120) NOT NULL,
    once_for TEXT NOT NULL,
    scope JSONB NOT NULL DEFAULT '{}'::JSONB,
    status VARCHAR(32) NOT NULL DEFAULT 'pending',
    progress JSONB NOT NULL DEFAULT '[]'::JSONB,
    error_message TEXT,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    completed_at TIMESTAMPTZ,
    failed_at TIMESTAMPTZ
);

CREATE UNIQUE INDEX idx_workflow_runs_idempotency_key
    ON workflow_runs (workflow_id, once_for);

CREATE INDEX idx_workflow_runs_workflow_created
    ON workflow_runs (workflow_id, created_at DESC);

WITH default_alert_workflows AS (
    INSERT INTO workflows (organization_id, name, trigger_name, enabled, system_key, created_at, updated_at)
    SELECT
        o.id,
        'Send alert notifications',
        'alert.triggered',
        TRUE,
        'default_alert_notifications',
        NOW(),
        NOW()
    FROM organizations o
    ON CONFLICT (organization_id, system_key) WHERE system_key IS NOT NULL DO NOTHING
    RETURNING id
)
INSERT INTO workflow_versions (
    workflow_id,
    version,
    conditions,
    steps,
    once_for_template,
    engine_config,
    most_recent,
    created_at
)
SELECT
    id,
    1,
    '[]'::JSONB,
    '[
      {
        "name": "notification.email_org",
        "params": {
          "subject": "[Moneat] {{alert.title}}",
          "body": "{{alert.title}}\n\n{{alert.description}}\n\nSeverity: {{alert.severity}}\nSource: {{alert.source}}\nStatus: {{alert.status}}\n\nView in Moneat: {{alert.url}}"
        }
      },
      {
        "name": "notification.slack",
        "params": {
          "message": "*{{alert.title}}*\n{{alert.description}}\n\n*Severity:* {{alert.severity}}\n*Source:* {{alert.source}}\n*Status:* {{alert.status}}\n{{alert.url}}",
          "skip_if_unconfigured": "true"
        }
      },
      {
        "name": "notification.discord",
        "params": {
          "title": "{{alert.title}}",
          "message": "{{alert.description}}\n\nSeverity: {{alert.severity}}\nSource: {{alert.source}}\nStatus: {{alert.status}}\n{{alert.url}}",
          "skip_if_unconfigured": "true"
        }
      }
    ]'::JSONB,
    '["alert.deduplication_key"]'::JSONB,
    '[
      "alert.title",
      "alert.description",
      "alert.severity",
      "alert.source",
      "alert.status",
      "alert.url",
      "alert.deduplication_key"
    ]'::JSONB,
    TRUE,
    NOW()
FROM default_alert_workflows;

WITH default_resolved_workflows AS (
    INSERT INTO workflows (organization_id, name, trigger_name, enabled, system_key, created_at, updated_at)
    SELECT
        o.id,
        'Send recovery notifications',
        'alert.resolved',
        TRUE,
        'default_recovery_notifications',
        NOW(),
        NOW()
    FROM organizations o
    ON CONFLICT (organization_id, system_key) WHERE system_key IS NOT NULL DO NOTHING
    RETURNING id
)
INSERT INTO workflow_versions (
    workflow_id,
    version,
    conditions,
    steps,
    once_for_template,
    engine_config,
    most_recent,
    created_at
)
SELECT
    id,
    1,
    '[]'::JSONB,
    '[
      {
        "name": "notification.email_org",
        "params": {
          "subject": "[Moneat] Resolved: {{alert.title}}",
          "body": "{{alert.title}}\n\n{{alert.description}}\n\nSource: {{alert.source}}\nStatus: {{alert.status}}\n\nView in Moneat: {{alert.url}}"
        }
      },
      {
        "name": "notification.slack",
        "params": {
          "message": "*Resolved: {{alert.title}}*\n{{alert.description}}\n\n*Source:* {{alert.source}}\n*Status:* {{alert.status}}\n{{alert.url}}",
          "skip_if_unconfigured": "true"
        }
      },
      {
        "name": "notification.discord",
        "params": {
          "title": "Resolved: {{alert.title}}",
          "message": "{{alert.description}}\n\nSource: {{alert.source}}\nStatus: {{alert.status}}\n{{alert.url}}",
          "skip_if_unconfigured": "true"
        }
      }
    ]'::JSONB,
    '["alert.deduplication_key", "alert.status"]'::JSONB,
    '[
      "alert.title",
      "alert.description",
      "alert.source",
      "alert.status",
      "alert.url",
      "alert.deduplication_key"
    ]'::JSONB,
    TRUE,
    NOW()
FROM default_resolved_workflows;
