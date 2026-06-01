-- Rebuild SBOM inventory with the full visible inventory identity in the ReplacingMergeTree key.
-- V46 keyed only target/package fields, so containers on the same host/image could replace each other.
--
-- Retry safety:
-- * The executor records a migration only after every statement succeeds.
-- * A crash after the swap can leave security_package_inventory already rekeyed while this migration is
--   still pending; a crash during an older copy of this migration could also leave only
--   security_package_inventory_v46_old and security_package_inventory_rekeyed.
-- * This file copies every possible active/staging table into security_package_inventory_v48_recovery
--   before dropping security_package_inventory_v48_rebuild, then rebuilds from retained recovery plus
--   current/staging sources. The merge() regexes deliberately exclude the destination tables.

CREATE TABLE IF NOT EXISTS security_package_inventory (
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

CREATE TABLE IF NOT EXISTS security_package_inventory_v48_recovery (
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
    ingested_at DateTime64(3, 'UTC') DEFAULT now()
) ENGINE = MergeTree
PARTITION BY toYYYYMM(collected_at)
ORDER BY (
    inventory_id
)
TTL toDateTime(collected_at) + INTERVAL 180 DAY
SETTINGS index_granularity = 8192;

INSERT INTO security_package_inventory_v48_recovery (
    inventory_id, upload_id, organization_id, source, format, target_type, target_name,
    host, container_id, image_name, package_name, package_version, package_type, ecosystem,
    purl, licenses, supplier, bom_ref, tags, collected_at, ingested_at
)
SELECT
    inventory_id, upload_id, organization_id, source, format, target_type, target_name,
    host, container_id, image_name, package_name, package_version, package_type, ecosystem,
    purl, licenses, supplier, bom_ref, tags, collected_at, ingested_at
FROM merge(currentDatabase(), '^security_package_inventory(_v46_old|_rekeyed|_v48_rebuild)?$')
WHERE inventory_id NOT IN (
    SELECT inventory_id
    FROM security_package_inventory_v48_recovery
);

DROP TABLE IF EXISTS security_package_inventory_v48_rebuild;

CREATE TABLE security_package_inventory_v48_rebuild (
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

INSERT INTO security_package_inventory_v48_rebuild (
    inventory_id, upload_id, organization_id, source, format, target_type, target_name,
    host, container_id, image_name, package_name, package_version, package_type, ecosystem,
    purl, licenses, supplier, bom_ref, tags, collected_at, ingested_at
)
SELECT
    inventory_id, upload_id, organization_id, source, format, target_type, target_name,
    host, container_id, image_name, package_name, package_version, package_type, ecosystem,
    purl, licenses, supplier, bom_ref, tags, collected_at, ingested_at
FROM security_package_inventory_v48_recovery
WHERE inventory_id NOT IN (
    SELECT inventory_id
    FROM security_package_inventory_v48_rebuild
);

INSERT INTO security_package_inventory_v48_rebuild (
    inventory_id, upload_id, organization_id, source, format, target_type, target_name,
    host, container_id, image_name, package_name, package_version, package_type, ecosystem,
    purl, licenses, supplier, bom_ref, tags, collected_at, ingested_at
)
SELECT
    inventory_id, upload_id, organization_id, source, format, target_type, target_name,
    host, container_id, image_name, package_name, package_version, package_type, ecosystem,
    purl, licenses, supplier, bom_ref, tags, collected_at, ingested_at
FROM merge(currentDatabase(), '^security_package_inventory(_v46_old|_rekeyed)?$')
WHERE inventory_id NOT IN (
    SELECT inventory_id
    FROM security_package_inventory_v48_rebuild
);

EXCHANGE TABLES security_package_inventory AND security_package_inventory_v48_rebuild;

INSERT INTO security_package_inventory (
    inventory_id, upload_id, organization_id, source, format, target_type, target_name,
    host, container_id, image_name, package_name, package_version, package_type, ecosystem,
    purl, licenses, supplier, bom_ref, tags, collected_at, ingested_at
)
SELECT
    inventory_id, upload_id, organization_id, source, format, target_type, target_name,
    host, container_id, image_name, package_name, package_version, package_type, ecosystem,
    purl, licenses, supplier, bom_ref, tags, collected_at, ingested_at
FROM security_package_inventory_v48_rebuild
WHERE inventory_id NOT IN (
    SELECT inventory_id
    FROM security_package_inventory
);
