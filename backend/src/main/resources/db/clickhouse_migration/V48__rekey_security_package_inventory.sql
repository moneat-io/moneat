-- Rebuild SBOM inventory with the full visible inventory identity in the ReplacingMergeTree key.
-- V46 keyed only target/package fields, so containers on the same host/image could replace each other.
--
-- ClickHouse cannot safely add existing columns to this MergeTree ORDER BY with ALTER MODIFY ORDER BY; that
-- operation is metadata-only and keeps the old primary key. This migration copies into a replacement table,
-- atomically swaps names, then copies any rows that arrived in the old table during the first copy. The V46 table is
-- intentionally retained as security_package_inventory_v46_old instead of dropped so interrupted or unexpected
-- online migration writes have a recovery source.

DROP TABLE IF EXISTS security_package_inventory_rekeyed;

CREATE TABLE security_package_inventory_rekeyed (
    inventory_id UUID DEFAULT generateUUIDv4(),
    upload_id UUID,
    organization_id UInt64,
    source LowCardinality(String) DEFAULT 'direct',
    format LowCardinality(String) DEFAULT 'cyclonedx',
    target_type LowCardinality(String) DEFAULT '',
    target_name String DEFAULT '',
    host String DEFAULT '',
    container_id String DEFAULT '',
    image_name String DEFAULT '',
    package_name String DEFAULT '',
    package_version String DEFAULT '',
    package_type LowCardinality(String) DEFAULT '',
    ecosystem LowCardinality(String) DEFAULT '',
    purl String DEFAULT '',
    licenses Array(String),
    supplier String DEFAULT '',
    bom_ref String DEFAULT '',
    tags Map(String, String),
    collected_at DateTime64(3, 'UTC') DEFAULT now(),
    ingested_at DateTime64(3, 'UTC') DEFAULT now(),
    INDEX idx_security_pkg_name package_name TYPE bloom_filter GRANULARITY 1,
    INDEX idx_security_pkg_purl purl TYPE bloom_filter GRANULARITY 1,
    INDEX idx_security_pkg_target target_name TYPE bloom_filter GRANULARITY 1
) ENGINE = ReplacingMergeTree(ingested_at)
PARTITION BY toYYYYMM(collected_at)
ORDER BY (
    organization_id,
    target_type,
    target_name,
    host,
    image_name,
    container_id,
    package_name,
    package_version,
    package_type,
    ecosystem,
    purl
)
TTL toDateTime(collected_at) + INTERVAL 180 DAY
SETTINGS index_granularity = 8192;

INSERT INTO security_package_inventory_rekeyed (
    inventory_id, upload_id, organization_id, source, format, target_type, target_name,
    host, container_id, image_name, package_name, package_version, package_type, ecosystem,
    purl, licenses, supplier, bom_ref, tags, collected_at, ingested_at
)
SELECT
    inventory_id, upload_id, organization_id, source, format, target_type, target_name,
    host, container_id, image_name, package_name, package_version, package_type, ecosystem,
    purl, licenses, supplier, bom_ref, tags, collected_at, ingested_at
FROM security_package_inventory;

RENAME TABLE security_package_inventory TO security_package_inventory_v46_old,
    security_package_inventory_rekeyed TO security_package_inventory;

INSERT INTO security_package_inventory (
    inventory_id, upload_id, organization_id, source, format, target_type, target_name,
    host, container_id, image_name, package_name, package_version, package_type, ecosystem,
    purl, licenses, supplier, bom_ref, tags, collected_at, ingested_at
)
SELECT
    inventory_id, upload_id, organization_id, source, format, target_type, target_name,
    host, container_id, image_name, package_name, package_version, package_type, ecosystem,
    purl, licenses, supplier, bom_ref, tags, collected_at, ingested_at
FROM security_package_inventory_v46_old
WHERE inventory_id NOT IN (
    SELECT inventory_id
    FROM security_package_inventory
);
