WITH target_workflows AS (
    SELECT
        w.id AS workflow_id,
        w.system_key,
        COALESCE(MAX(wv.version), 0) + 1 AS next_version
    FROM workflows w
    LEFT JOIN workflow_versions wv ON wv.workflow_id = w.id
    WHERE w.system_key IN ('default_alert_notifications', 'default_recovery_notifications')
    GROUP BY w.id, w.system_key
)
UPDATE workflow_versions wv
SET most_recent = FALSE
FROM target_workflows tw
WHERE wv.workflow_id = tw.workflow_id
    AND wv.most_recent = TRUE;

WITH target_workflows AS (
    SELECT
        w.id AS workflow_id,
        w.system_key,
        COALESCE(MAX(wv.version), 0) + 1 AS next_version
    FROM workflows w
    LEFT JOIN workflow_versions wv ON wv.workflow_id = w.id
    WHERE w.system_key IN ('default_alert_notifications', 'default_recovery_notifications')
    GROUP BY w.id, w.system_key
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
    workflow_id,
    next_version,
    '[]'::JSONB,
    CASE system_key
        WHEN 'default_alert_notifications' THEN
            $json$
            [
              {
                "name": "notification.email_org",
                "params": {
                  "format": "alert_lifecycle",
                  "subject": "[Moneat] {{alert.priority}} {{alert.display_title}}",
                  "body": "{{alert.display_title}}\n\n{{alert.description}}\n\nPriority: {{alert.priority}}\nSource: {{alert.source}}\nStatus: {{alert.status}}\n\nView: {{alert.url}}"
                }
              },
              {
                "name": "notification.slack",
                "params": {
                  "format": "alert_lifecycle",
                  "message": "*{{alert.priority}} {{alert.display_title}}*\n{{alert.description}}\n\n*Priority:* {{alert.priority}}\n*Source:* {{alert.source}}\n*Status:* {{alert.status}}\n{{alert.url}}",
                  "skip_if_unconfigured": "true"
                }
              },
              {
                "name": "notification.discord",
                "params": {
                  "format": "alert_lifecycle",
                  "title": "{{alert.priority}} {{alert.display_title}}",
                  "message": "{{alert.description}}\n\nPriority: {{alert.priority}}\nSource: {{alert.source}}\nStatus: {{alert.status}}\n{{alert.url}}",
                  "skip_if_unconfigured": "true"
                }
              }
            ]
            $json$::JSONB
        ELSE
            $json$
            [
              {
                "name": "notification.email_org",
                "params": {
                  "format": "alert_lifecycle",
                  "subject": "[Moneat] {{alert.priority}} Resolved: {{alert.display_title}}",
                  "body": "{{alert.display_title}}\n\n{{alert.description}}\n\nPriority: {{alert.priority}}\nSource: {{alert.source}}\nStatus: {{alert.status}}\n\nView: {{alert.url}}"
                }
              },
              {
                "name": "notification.slack",
                "params": {
                  "format": "alert_lifecycle",
                  "message": "*{{alert.priority}} Resolved: {{alert.display_title}}*\n{{alert.description}}\n\n*Priority:* {{alert.priority}}\n*Source:* {{alert.source}}\n*Status:* {{alert.status}}\n{{alert.url}}",
                  "skip_if_unconfigured": "true"
                }
              },
              {
                "name": "notification.discord",
                "params": {
                  "format": "alert_lifecycle",
                  "title": "{{alert.priority}} Resolved: {{alert.display_title}}",
                  "message": "{{alert.description}}\n\nPriority: {{alert.priority}}\nSource: {{alert.source}}\nStatus: {{alert.status}}\n{{alert.url}}",
                  "skip_if_unconfigured": "true"
                }
              }
            ]
            $json$::JSONB
    END,
    CASE system_key
        WHEN 'default_alert_notifications' THEN '["alert.deduplication_key"]'::JSONB
        ELSE '["alert.deduplication_key", "alert.status"]'::JSONB
    END,
    $json$
    [
      "alert.display_title",
      "alert.description",
      "alert.severity",
      "alert.priority",
      "alert.source",
      "alert.status",
      "alert.url",
      "alert.deduplication_key",
      "alert.dashboard.title",
      "alert.widget.title",
      "alert.condition",
      "alert.threshold",
      "alert.current_value"
    ]
    $json$::JSONB,
    TRUE,
    NOW()
FROM target_workflows;

UPDATE workflows
SET updated_at = NOW()
WHERE system_key IN ('default_alert_notifications', 'default_recovery_notifications');
