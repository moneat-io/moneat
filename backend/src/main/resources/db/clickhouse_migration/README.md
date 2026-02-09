# ClickHouse Migrations

This project uses a Flyway-style migration system for ClickHouse schema management.

## How it Works

Migrations run automatically on application startup before the server starts accepting requests. The migration system:

1. Creates a `schema_migrations` table to track applied migrations
2. Scans for migration files in version order
3. Applies only new migrations that haven't been run yet
4. Records each successful migration with a checksum

## Migration Files

- Located in: `backend/src/main/resources/db/clickhouse_migration/`
- Naming convention: `V{version}__{description}.sql`
  - Example: `V1__initial_schema.sql`
  - Version must be numeric and sequential
  - Use underscores in description

## Existing Migrations

- `V1__initial_schema.sql` - Initial ClickHouse tables (events, spans, issues, sessions, replays, feedback, metrics)
- `V2__add_logs_table.sql` - Centralized logging table

## Adding New Migrations

1. Create a new file: `V{next_version}__{description}.sql`
2. Write your ClickHouse SQL (CREATE TABLE, ALTER TABLE, etc.)
3. Restart the app - migration runs automatically

## Migration Metadata

The migration system tracks applied migrations in the `schema_migrations` table. Never modify this table manually.

## Important Notes

- Migrations are run sequentially in version order
- Each migration file must be idempotent (use `IF NOT EXISTS` where possible)
- Failed migrations will prevent application startup
- Migration checksums are validated to detect accidental file modifications
