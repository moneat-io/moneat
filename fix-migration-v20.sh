#!/bin/bash

# Script to remove and reapply migration V20

set -e

echo "=== Fixing Migration V20 ==="
echo ""

# Get database connection details from environment or docker-compose
CONTAINER_NAME="moneat-db-1"

echo "Step 1: Checking if migration V20 is recorded in flyway_schema_history..."
docker exec -i $CONTAINER_NAME psql -U postgres -d moneat -c \
  "SELECT version, description, installed_on, success FROM flyway_schema_history WHERE version = '20';"

echo ""
echo "Step 2: Removing migration V20 from flyway_schema_history..."
docker exec -i $CONTAINER_NAME psql -U postgres -d moneat -c \
  "DELETE FROM flyway_schema_history WHERE version = '20';"

echo ""
echo "Step 3: Checking if organization_integrations table exists..."
docker exec -i $CONTAINER_NAME psql -U postgres -d moneat -c \
  "SELECT table_name FROM information_schema.tables WHERE table_name = 'organization_integrations';"

echo ""
echo "Step 4: Dropping organization_integrations table if it exists (without access_token column)..."
docker exec -i $CONTAINER_NAME psql -U postgres -d moneat -c \
  "DROP TABLE IF EXISTS organization_integrations CASCADE;"

echo ""
echo "Step 5: Applying migration V20..."
docker exec -i $CONTAINER_NAME psql -U postgres -d moneat < backend/src/main/resources/db/migration/V20__add_slack_integration.sql

echo ""
echo "Step 6: Manually recording migration in flyway_schema_history..."
docker exec -i $CONTAINER_NAME psql -U postgres -d moneat -c \
  "INSERT INTO flyway_schema_history (installed_rank, version, description, type, script, checksum, installed_by, execution_time, success) 
   VALUES ((SELECT COALESCE(MAX(installed_rank), 0) + 1 FROM flyway_schema_history), '20', 'add slack integration', 'SQL', 'V20__add_slack_integration.sql', 0, 'manual', 0, true);"

echo ""
echo "Step 7: Verifying the table structure..."
docker exec -i $CONTAINER_NAME psql -U postgres -d moneat -c \
  "SELECT column_name, data_type FROM information_schema.columns WHERE table_name = 'organization_integrations' ORDER BY ordinal_position;"

echo ""
echo "=== Migration V20 Fixed Successfully ==="
