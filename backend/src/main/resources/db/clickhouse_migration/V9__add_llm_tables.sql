-- LLM generations table for AI/LLM observability
CREATE TABLE IF NOT EXISTS llm_generations (
    generation_id UUID,
    project_id UInt64,
    trace_id String,
    span_id String,
    parent_span_id String,
    timestamp DateTime64(3, 'UTC'),
    duration_ms Float64,

    -- LLM-specific fields
    name String,
    model String,
    provider String,
    type Enum8('chat' = 1, 'completion' = 2, 'embedding' = 3, 'tool_call' = 4, 'agent' = 5, 'chain' = 6, 'retriever' = 7),

    -- Input/Output
    input String,
    output String,
    input_tokens UInt32,
    output_tokens UInt32,
    total_tokens UInt32,
    cost_usd Float64,

    -- Parameters
    temperature Float32,
    max_tokens UInt32,
    top_p Float32,

    -- Status
    status Enum8('success' = 1, 'error' = 2),
    error_message String,
    status_code UInt16,

    -- Context
    user_id String,
    session_id String,
    environment String,
    release String,
    tags Map(String, String),
    metadata String,

    received_at DateTime64(3, 'UTC') DEFAULT now64(3)
) ENGINE = MergeTree()
PARTITION BY toYYYYMM(timestamp)
ORDER BY (project_id, timestamp, trace_id, generation_id)
TTL toDateTime(timestamp) + INTERVAL 90 DAY
SETTINGS index_granularity = 8192;

-- Materialized view for hourly aggregations by model/provider
CREATE MATERIALIZED VIEW IF NOT EXISTS llm_generations_hourly_mv
ENGINE = SummingMergeTree()
PARTITION BY toYYYYMM(hour)
ORDER BY (project_id, hour, model, provider, type)
AS SELECT
    project_id,
    toStartOfHour(timestamp) AS hour,
    model,
    provider,
    type,
    count() AS call_count,
    countIf(status = 'error') AS error_count,
    sum(input_tokens) AS total_input_tokens,
    sum(output_tokens) AS total_output_tokens,
    sum(total_tokens) AS total_tokens_sum,
    sum(cost_usd) AS total_cost,
    sum(duration_ms) AS total_duration_ms,
    min(duration_ms) AS min_duration_ms,
    max(duration_ms) AS max_duration_ms
FROM llm_generations
GROUP BY project_id, hour, model, provider, type;
