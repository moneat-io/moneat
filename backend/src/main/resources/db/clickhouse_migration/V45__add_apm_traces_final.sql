-- Finalized per-trace table for APM dashboard queries.
--
-- Dashboard reads previously re-aggregated apm_trace_summaries per trace on every request
-- (GROUP BY trace_id_canonical with four argMinMerge String-state merges over millions of groups,
-- ~6-8s for a 24h window). With #469 firing 7-9 of those concurrently per dashboard load they
-- contended and crossed the 10s server cap -> Code 159 TIMEOUT_EXCEEDED.
--
-- apm_traces_final stores one finalized row per trace with plain columns, keyed by
-- (organization_id, trace_bucket, trace_id_canonical). Reads filter on the (org, trace_bucket) key
-- prefix and aggregate plain columns -- no argMin merges, no per-trace GROUP BY. On prod this dropped
-- a 24h overview read from ~6-8s to well under a second.
--
-- The expensive per-trace aggregation happens off the read path in TraceFinalizerBackgroundService,
-- which re-finalizes a recent sliding window from apm_trace_summaries on a schedule. ReplacingMergeTree
-- (versioned by finalized_at) makes re-finalization idempotent so late-arriving spans are absorbed;
-- reads use FINAL to collapse superseded rows. The table is populated by the finalizer (including an
-- initial catch-up of the recent window on first run), so no backfill runs in this migration -- that
-- keeps the migration fast and avoids the 600s migration timeout on large installs.

CREATE TABLE IF NOT EXISTS apm_traces_final (
    organization_id UInt64,
    trace_bucket DateTime('UTC'),
    trace_id_canonical String,
    root_service LowCardinality(String),
    root_resource String,
    root_name String,
    source LowCardinality(String),
    env LowCardinality(String),
    trace_start DateTime64(9, 'UTC'),
    duration_ns UInt64,
    span_count UInt32,
    error_count UInt32,
    has_error UInt8,
    finalized_at DateTime DEFAULT now()
) ENGINE = ReplacingMergeTree(finalized_at)
PARTITION BY toYYYYMM(trace_bucket)
ORDER BY (organization_id, trace_bucket, trace_id_canonical)
SETTINGS index_granularity = 8192;
