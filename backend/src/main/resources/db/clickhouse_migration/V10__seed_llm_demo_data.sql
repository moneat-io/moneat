-- Seed LLM demo data with real trace structure for demo projects (-1, -2, -3).
-- This migration intentionally rebuilds demo LLM rows to avoid duplicates.

ALTER TABLE llm_generations
DELETE WHERE project_id IN (toUInt64(-1), toUInt64(-2), toUInt64(-3));

-- llm_generations_hourly_mv is a SummingMergeTree populated by materialized view;
-- source-table deletes do not automatically retract aggregate rows.
ALTER TABLE llm_generations_hourly_mv
DELETE WHERE project_id IN (toUInt64(-1), toUInt64(-2), toUInt64(-3));

INSERT INTO llm_generations (
    generation_id,
    project_id,
    trace_id,
    span_id,
    parent_span_id,
    timestamp,
    duration_ms,
    name,
    model,
    provider,
    type,
    input,
    output,
    input_tokens,
    output_tokens,
    total_tokens,
    cost_usd,
    temperature,
    max_tokens,
    top_p,
    status,
    error_message,
    status_code,
    user_id,
    session_id,
    environment,
    release,
    tags,
    metadata,
    received_at
)
SELECT
    generateUUIDv4() AS generation_id,
    CASE intDiv(number, 3) % 3
        WHEN 0 THEN toUInt64(-1)
        WHEN 1 THEN toUInt64(-2)
        ELSE toUInt64(-3)
    END AS project_id,
    concat('demo-trace-', toString(intDiv(number, 3))) AS trace_id,
    concat('demo-span-', toString(number)) AS span_id,
    CASE
        WHEN number % 3 = 0 THEN ''
        ELSE concat('demo-span-', toString(number - 1))
    END AS parent_span_id,
    now64(3) - INTERVAL (intDiv(number, 3) % 168) HOUR + INTERVAL ((number % 3) * 2) SECOND AS timestamp,
    CASE number % 3
        WHEN 0 THEN 220 + ((number * 11) % 300)
        WHEN 1 THEN 80 + ((number * 7) % 180)
        ELSE 350 + ((number * 13) % 900)
    END AS duration_ms,
    CASE number % 3
        WHEN 0 THEN 'agent.plan'
        WHEN 1 THEN 'retriever.search'
        ELSE 'chat.generate'
    END AS name,
    CASE intDiv(number, 3) % 4
        WHEN 0 THEN 'gpt-4o-mini'
        WHEN 1 THEN 'gpt-4o'
        WHEN 2 THEN 'claude-3-5-sonnet'
        ELSE 'gemini-1.5-pro'
    END AS model,
    CASE intDiv(number, 3) % 4
        WHEN 0 THEN 'openai'
        WHEN 1 THEN 'openai'
        WHEN 2 THEN 'anthropic'
        ELSE 'google'
    END AS provider,
    CASE number % 3
        WHEN 0 THEN 'agent'
        WHEN 1 THEN 'retriever'
        ELSE 'chat'
    END AS type,
    concat(
        '{"messages":[{"role":"user","content":"Demo request #',
        toString(intDiv(number, 3)),
        '"}],"step":',
        toString(number % 3),
        '}'
    ) AS input,
    CASE
        WHEN number % 29 = 0 AND number % 3 = 2 THEN ''
        ELSE concat(
            '{"text":"Demo response for trace ',
            toString(intDiv(number, 3)),
            ', step ',
            toString(number % 3),
            '"}'
        )
    END AS output,
    CASE number % 3
        WHEN 0 THEN 90 + (number % 70)
        WHEN 1 THEN 120 + (number % 80)
        ELSE 220 + (number % 140)
    END AS input_tokens,
    CASE
        WHEN number % 29 = 0 AND number % 3 = 2 THEN 0
        ELSE
            CASE number % 3
                WHEN 0 THEN 40 + (number % 30)
                WHEN 1 THEN 55 + (number % 35)
                ELSE 110 + (number % 70)
            END
    END AS output_tokens,
    (
        CASE number % 3
            WHEN 0 THEN 90 + (number % 70)
            WHEN 1 THEN 120 + (number % 80)
            ELSE 220 + (number % 140)
        END
        +
        CASE
            WHEN number % 29 = 0 AND number % 3 = 2 THEN 0
            ELSE
                CASE number % 3
                    WHEN 0 THEN 40 + (number % 30)
                    WHEN 1 THEN 55 + (number % 35)
                    ELSE 110 + (number % 70)
                END
        END
    ) AS total_tokens,
    (
        (
            CASE number % 3
                WHEN 0 THEN 90 + (number % 70)
                WHEN 1 THEN 120 + (number % 80)
                ELSE 220 + (number % 140)
            END
        ) * 0.00000035
        +
        (
            CASE
                WHEN number % 29 = 0 AND number % 3 = 2 THEN 0
                ELSE
                    CASE number % 3
                        WHEN 0 THEN 40 + (number % 30)
                        WHEN 1 THEN 55 + (number % 35)
                        ELSE 110 + (number % 70)
                    END
            END
        ) * 0.0000011
    ) AS cost_usd,
    CASE number % 3
        WHEN 0 THEN toFloat32(0.2)
        WHEN 1 THEN toFloat32(0.0)
        ELSE toFloat32(0.7)
    END AS temperature,
    CASE number % 3
        WHEN 0 THEN toUInt32(256)
        WHEN 1 THEN toUInt32(192)
        ELSE toUInt32(512)
    END AS max_tokens,
    CASE number % 3
        WHEN 0 THEN toFloat32(0.95)
        WHEN 1 THEN toFloat32(1.0)
        ELSE toFloat32(0.9)
    END AS top_p,
    CASE
        WHEN number % 29 = 0 AND number % 3 = 2 THEN 'error'
        ELSE 'success'
    END AS status,
    CASE
        WHEN number % 29 = 0 AND number % 3 = 2 THEN 'Model provider timeout'
        ELSE ''
    END AS error_message,
    CASE
        WHEN number % 29 = 0 AND number % 3 = 2 THEN toUInt16(504)
        ELSE toUInt16(200)
    END AS status_code,
    concat('demo-user-', toString(intDiv(number, 3) % 120)) AS user_id,
    concat('demo-session-', toString(intDiv(number, 3))) AS session_id,
    'production' AS environment,
    CASE intDiv(number, 3) % 3
        WHEN 0 THEN '1.3.0'
        WHEN 1 THEN '2.1.0'
        ELSE '3.0.1'
    END AS release,
    map(
        'demo', 'true',
        'trace_index', toString(intDiv(number, 3)),
        'workflow', CASE number % 3 WHEN 0 THEN 'planner' WHEN 1 THEN 'retriever' ELSE 'generator' END
    ) AS tags,
    concat(
        '{"source":"clickhouse_migration_v10","trace_step":',
        toString(number % 3),
        '}'
    ) AS metadata,
    timestamp AS received_at
FROM numbers(600);
