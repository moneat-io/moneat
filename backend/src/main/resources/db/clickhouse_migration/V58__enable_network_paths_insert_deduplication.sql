-- Enable query-token based insert deduplication for NDM network path retries.

ALTER TABLE IF EXISTS network_paths MODIFY SETTING non_replicated_deduplication_window = 1000;
