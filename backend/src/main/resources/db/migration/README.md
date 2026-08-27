# Database Migrations

This project uses [Flyway](https://flywaydb.org/) for automated database migrations.

## How it Works

Migrations run automatically on application startup before the server starts accepting requests.

## Migration Files

- Located in: `backend/src/main/resources/db/migration/`
- Naming convention: `V{version}__{description}.sql`
  - Example: `V1__initial_schema.sql`
  - Version must be numeric and sequential
  - Use underscores in description

## Existing Migrations

- `V1__initial_schema.sql` - Initial database schema (users, orgs, projects, etc.)
- `V2__add_email_verification.sql` - Email verification columns
- `V3__add_onboarding.sql` - User onboarding flag
- `V4__add_password_reset.sql` - Password reset tokens
- `V5__add_auth_tokens.sql` - API authentication tokens
- `V6__add_release_stats.sql` - Release tracking columns
- `V16__add_user_legal_acceptance.sql` - Signup legal consent audit trail

## Adding New Migrations

1. Create a new file: `V{next_version}__{description}.sql`
2. Write your SQL (ALTER TABLE, CREATE TABLE, etc.)
3. Restart the app - migration runs automatically

Before opening or updating a pull request, check the migration number against
the target branch:

```bash
git fetch origin develop
python3 scripts/check-migration-versions.py --base-ref origin/develop
```

If the target branch advanced and your migration is stale, rename added
migrations automatically:

```bash
python3 scripts/check-migration-versions.py --base-ref origin/develop --fix
```

If a branch contains a duplicate migration version, keep the SQL payload of
the duplicate and move that file to the next available version. The checker
allows this one-time collision repair while continuing to reject ordinary
changes to existing migrations.

## Flyway Metadata

Flyway tracks applied migrations in the `flyway_schema_history` table. Never modify this table manually.
