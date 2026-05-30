ALTER TABLE workflow_versions
    ADD COLUMN graph JSONB NOT NULL DEFAULT '{"nodes":[],"edges":[]}'::JSONB,
    ADD COLUMN published BOOLEAN NOT NULL DEFAULT FALSE,
    ADD COLUMN input_schema JSONB NOT NULL DEFAULT '{}'::JSONB,
    ADD COLUMN tags JSONB NOT NULL DEFAULT '[]'::JSONB;

WITH version_parts AS (
    SELECT
        v.id,
        w.trigger_name,
        COALESCE(v.conditions, '[]'::JSONB) AS conditions,
        COALESCE(v.steps, '[]'::JSONB) AS steps,
        JSONB_ARRAY_LENGTH(COALESCE(v.conditions, '[]'::JSONB)) AS condition_count,
        JSONB_ARRAY_LENGTH(COALESCE(v.steps, '[]'::JSONB)) AS step_count
    FROM workflow_versions v
    INNER JOIN workflows w ON w.id = v.workflow_id
),
action_nodes AS (
    SELECT
        vp.id,
        COALESCE(
            JSONB_AGG(
                JSONB_BUILD_OBJECT(
                    'id', 'step-' || step_item.ordinality,
                    'type', 'action',
                    'action', step_item.step ->> 'name',
                    'params', COALESCE(step_item.step -> 'params', '{}'::JSONB),
                    'continue_on_error', TRUE
                )
                ORDER BY step_item.ordinality
            ),
            '[]'::JSONB
        ) AS nodes
    FROM version_parts vp
    LEFT JOIN LATERAL JSONB_ARRAY_ELEMENTS(vp.steps) WITH ORDINALITY AS step_item(step, ordinality) ON TRUE
    GROUP BY vp.id
),
graph_edges AS (
    SELECT
        vp.id,
        COALESCE(JSONB_AGG(edge.edge ORDER BY edge.ord), '[]'::JSONB) AS edges
    FROM version_parts vp
    LEFT JOIN LATERAL (
        -- trigger -> conditions, when the legacy version carried conditions
        SELECT JSONB_BUILD_OBJECT('from', 'trigger', 'to', 'conditions') AS edge, 0 AS ord
        WHERE vp.condition_count > 0

        UNION ALL

        -- Legacy steps are independent, so fan one edge out from the source to every step rather
        -- than chaining them step-to-step. With conditions the fan-out hangs off the "true" branch.
        SELECT JSONB_STRIP_NULLS(
            JSONB_BUILD_OBJECT(
                'from', CASE WHEN vp.condition_count > 0 THEN 'conditions' ELSE 'trigger' END,
                'to', 'step-' || series.n,
                'branch', CASE WHEN vp.condition_count > 0 THEN 'true' ELSE NULL END
            )
        ) AS edge, series.n AS ord
        FROM GENERATE_SERIES(1, vp.step_count) AS series(n)
    ) edge ON TRUE
    GROUP BY vp.id
)
UPDATE workflow_versions v
SET
    graph = JSONB_BUILD_OBJECT(
        'nodes',
        JSONB_BUILD_ARRAY(
            JSONB_BUILD_OBJECT(
                'id', 'trigger',
                'type', 'trigger',
                'trigger', vp.trigger_name
            )
        ) ||
            CASE
                WHEN vp.condition_count > 0 THEN JSONB_BUILD_ARRAY(
                    JSONB_BUILD_OBJECT(
                        'id', 'conditions',
                        'type', 'condition',
                        'kind', 'if',
                        'conditions', vp.conditions
                    )
                )
                ELSE '[]'::JSONB
            END ||
            action_nodes.nodes,
        'edges',
        graph_edges.edges
    ),
    published = TRUE
FROM version_parts vp
INNER JOIN action_nodes ON action_nodes.id = vp.id
INNER JOIN graph_edges ON graph_edges.id = vp.id
WHERE v.id = vp.id;

CREATE TABLE workflow_run_steps (
    id SERIAL PRIMARY KEY,
    run_id INTEGER NOT NULL REFERENCES workflow_runs(id) ON DELETE CASCADE,
    node_id VARCHAR(120) NOT NULL,
    type VARCHAR(64) NOT NULL,
    status VARCHAR(32) NOT NULL,
    started_at TIMESTAMPTZ,
    completed_at TIMESTAMPTZ,
    input JSONB NOT NULL DEFAULT '{}'::JSONB,
    output JSONB NOT NULL DEFAULT '{}'::JSONB,
    error_message TEXT,
    attempt INTEGER NOT NULL DEFAULT 1
);

CREATE UNIQUE INDEX idx_workflow_run_steps_attempt
    ON workflow_run_steps (run_id, node_id, attempt);

CREATE INDEX idx_workflow_run_steps_run
    ON workflow_run_steps (run_id, id);
