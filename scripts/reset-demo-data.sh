#!/bin/bash
set -e

echo "🗑️  Resetting all demo data..."

# Check if we're in the scripts directory or project root
if [ -d "backend" ]; then
    # Already in project root
    PROJECT_ROOT="."
elif [ -d "../backend" ]; then
    # In scripts directory
    PROJECT_ROOT=".."
else
    echo "❌ Cannot find backend directory. Please run from project root or scripts directory."
    exit 1
fi

cd "$PROJECT_ROOT"

# Check if database is accessible
echo "Checking database connection..."
if ! docker-compose ps postgres | grep -q "Up"; then
  echo "❌ PostgreSQL is not running. Please start database:"
  echo "   docker-compose up -d postgres"
  exit 1
fi

# Check if ClickHouse is running
if ! docker-compose ps clickhouse | grep -q "Up"; then
  echo "❌ ClickHouse is not running. Please start ClickHouse:"
  echo "   docker-compose up -d clickhouse"
  exit 1
fi

echo ""
echo "⚠️  WARNING: This will delete ALL demo data!"
echo "   - Demo user (demo@moneat.dev)"
echo "   - Acme Mobile Inc organization"
echo "   - All projects, issues, events, and related data"
echo ""
read -p "Are you sure you want to continue? (yes/no): " -r
echo

if [[ ! $REPLY =~ ^[Yy][Ee][Ss]$ ]]; then
    echo "Cancelled."
    exit 0
fi

echo "🗑️  Deleting PostgreSQL demo data..."
docker exec moneat-postgres psql -U moneat -d moneat << 'SQL'
-- Delete in correct order to respect foreign keys
DELETE FROM subscriptions WHERE organization_id IN (SELECT id FROM organizations WHERE slug = 'acme-mobile');
DELETE FROM status_page_incidents WHERE status_page_id IN (SELECT id FROM status_pages WHERE organization_id IN (SELECT id FROM organizations WHERE slug = 'acme-mobile'));
DELETE FROM status_page_monitors WHERE status_page_id IN (SELECT id FROM status_pages WHERE organization_id IN (SELECT id FROM organizations WHERE slug = 'acme-mobile'));
DELETE FROM status_pages WHERE organization_id IN (SELECT id FROM organizations WHERE slug = 'acme-mobile');
DELETE FROM uptime_monitors WHERE organization_id IN (SELECT id FROM organizations WHERE slug = 'acme-mobile');
DELETE FROM systems WHERE organization_id IN (SELECT id FROM organizations WHERE slug = 'acme-mobile');
DELETE FROM project_keys WHERE project_id IN (SELECT id FROM projects WHERE organization_id IN (SELECT id FROM organizations WHERE slug = 'acme-mobile'));
DELETE FROM releases WHERE project_id IN (SELECT id FROM projects WHERE organization_id IN (SELECT id FROM organizations WHERE slug = 'acme-mobile'));
DELETE FROM projects WHERE organization_id IN (SELECT id FROM organizations WHERE slug = 'acme-mobile');
DELETE FROM organization_members WHERE organization_id IN (SELECT id FROM organizations WHERE slug = 'acme-mobile');
DELETE FROM organizations WHERE slug = 'acme-mobile';
DELETE FROM users WHERE email = 'demo@moneat.dev';
SQL

echo "🗑️  Deleting ClickHouse demo data..."
# Get project IDs before deleting (projects 1, 2, 3 are usually demo projects)
docker exec moneat-clickhouse clickhouse-client --query "
ALTER TABLE moneat.issues DELETE WHERE project_id IN (1, 2, 3);
ALTER TABLE moneat.events DELETE WHERE project_id IN (1, 2, 3);
ALTER TABLE moneat.logs DELETE WHERE project_id IN (1, 2, 3);
ALTER TABLE moneat.user_feedback DELETE WHERE project_id IN (1, 2, 3);
ALTER TABLE moneat.replay_events DELETE WHERE project_id IN (1, 2, 3);
ALTER TABLE moneat.replay_segments DELETE WHERE project_id IN (1, 2, 3);
ALTER TABLE moneat.sessions DELETE WHERE project_id IN (1, 2, 3);
ALTER TABLE moneat.spans DELETE WHERE project_id IN (1, 2, 3);
"

echo ""
echo "✅ Demo data deleted successfully!"
echo ""
echo "To re-seed demo data, run:"
echo "   ./scripts/seed-demo-data.sh"
echo ""
