#!/bin/bash

set -e

echo "🗄️  Initializing ClickHouse tables..."

# Check if ClickHouse is running
if ! docker exec moneat-clickhouse wget --spider -q localhost:8123/ping >/dev/null 2>&1; then
  echo "❌ ClickHouse is not running. Please run 'docker-compose up -d' first."
  exit 1
fi

# Initialize tables
echo "Creating ClickHouse tables..."
docker exec -i moneat-clickhouse clickhouse-client --database moneat --multiquery <backend/src/main/resources/db/clickhouse_init.sql

# Verify tables were created
echo ""
echo "Verifying tables..."
TABLES=$(docker exec moneat-clickhouse clickhouse-client --database moneat --query "SHOW TABLES")

if echo "$TABLES" | grep -q "events"; then
  echo "✅ events table created"
else
  echo "❌ events table NOT created"
fi

if echo "$TABLES" | grep -q "issues"; then
  echo "✅ issues table created"
else
  echo "❌ issues table NOT created"
fi

if echo "$TABLES" | grep -q "issues_mv"; then
  echo "✅ issues_mv materialized view created"
else
  echo "❌ issues_mv NOT created"
fi

echo ""
echo "✅ ClickHouse initialization complete!"
