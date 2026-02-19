# Contributing to Moneat

Thank you for your interest in contributing to Moneat! This document outlines how to contribute and the requirements for doing so.

## Dual-License Structure

Moneat uses an open-core dual-license model:

- **Core (AGPLv3)**: All code outside the `enterprise/` directory is licensed under the GNU Affero General Public License v3.0. Contributions to core are under AGPLv3 with CLA rights (see below).
- **Enterprise (Proprietary)**: Enterprise code lives in a separate private repository ([moneat-enterprise](https://github.com/moneat-io/moneat-enterprise)) and is licensed under the Moneat Enterprise License. Contributions to enterprise code are under the proprietary license.

## Contributor License Agreement (CLA)

All contributions to Moneat require signing our [Contributor License Agreement](CLA.md). The CLA grants Moneat the right to sublicense your contributions, which enables us to include community code in both the open-source core and the proprietary enterprise edition.

**How it works:**
1. Submit a pull request
2. The CLA-assistant bot will comment asking you to sign
3. Reply with: `I have read the CLA Document and I hereby sign the CLA`
4. This is a one-time requirement — you won't be asked again

The CLA does **not** change your copyright ownership. You retain full rights to your contributions and can use them in other projects.

## How to Contribute

### Reporting Issues

- Search existing issues before creating a new one
- Include steps to reproduce, expected behavior, and actual behavior
- Include relevant logs, screenshots, or error messages

### Pull Requests

1. Fork the repository
2. Create a feature branch from `develop`
3. Make your changes following the coding conventions below
4. Ensure tests pass: `cd backend && ./gradlew test` and `cd dashboard && npm test`
5. Open a pull request against `develop`
6. Sign the CLA when prompted by the bot

### Coding Conventions

**Backend (Kotlin/Ktor):**
- Use the current Exposed DSL syntax: `Table.selectAll().where { ... }` (not the deprecated `Table.select { ... }`)
- Use `EnvConfig.get()` for environment variables (never `System.getenv()` directly)
- Production URLs as defaults in code; localhost only in `.env` files

**Frontend (React/TypeScript):**
- Use TanStack Router file-based routing
- Use TanStack Query for data fetching
- Follow existing shadcn/ui component patterns

**Database:**
- Never modify existing migrations; always create new ones
- PostgreSQL migrations: `backend/src/main/resources/db/migration/V{N}__{description}.sql`
- ClickHouse migrations: `backend/src/main/resources/db/clickhouse_migration/V{N}__{description}.sql`

### Development Setup

```bash
# Start infrastructure + app (community features only)
docker compose up -d

# Or run services individually for development:

# Start only the databases
docker compose up -d postgres clickhouse redis

# Run backend (community)
cd backend && ./gradlew run

# Run dashboard
cd dashboard && npm install && npm run dev
```

### Working on Enterprise Features

Enterprise modules (Analytics, On-Call, SSO) live in a separate private repository. To work with enterprise features locally:

```bash
# 1. Clone the enterprise repo as a sibling directory
git clone git@github.com:moneat-io/moneat-enterprise.git ../moneat-enterprise

# 2. Start databases
docker compose up -d postgres clickhouse redis

# 3. Run backend with enterprise modules loaded
#    Gradle auto-discovers ../moneat-enterprise/backend via settings.gradle.kts
cd backend && ./gradlew run -Penterprise

# 4. Run dashboard — enterprise routes are synced automatically via predev hook
cd dashboard && npm run dev
```

**Custom paths:** If your enterprise checkout is not at `../moneat-enterprise`:

```bash
# Backend — override Gradle enterprise path
cd backend && ./gradlew run -Penterprise -PenterprisePath=/path/to/enterprise/backend

# Dashboard — override sync script source
ENTERPRISE_PATH=/path/to/enterprise npm run dev
```

**How it works:** The `-Penterprise` flag adds the `:enterprise` subproject as a `runtimeOnly` dependency. The backend's `FeatureRegistry` uses Java `ServiceLoader` to auto-discover all `EnterpriseModule` implementations at startup — no additional configuration needed.

**Enterprise-specific environment variables** (add to `.env` as needed):

| Variable | Required for |
|----------|-------------|
| `TWILIO_ACCOUNT_SID` / `TWILIO_AUTH_TOKEN` / `TWILIO_FROM_NUMBER` | On-Call voice alerts |
| `SAML_CERT` / `SAML_KEY` / `SAML_ENTITY_ID` | SSO (SAML) |

**Enterprise routes:** Files in `moneat-enterprise/dashboard/src/routes/` are the source of truth. They are copied into `dashboard/src/routes/` automatically by the dashboard pre-hooks (`predev`/`prebuild`) when enterprise sources are present, or by running `scripts/sync-enterprise-routes.sh` manually. Never edit the copies in `dashboard/src/routes/` directly.

#### Docker with enterprise features

```bash
# Build with enterprise modules (uses ../moneat-enterprise by default)
./scripts/docker-build.sh --enterprise

# Or with a custom enterprise path
ENTERPRISE_PATH=/path/to/enterprise ./scripts/docker-build.sh --enterprise

# Run with enterprise compose override
docker compose -f docker-compose.yml -f docker-compose.enterprise.yml up -d

# Optionally create .env.enterprise for enterprise-specific secrets (Twilio, SAML, etc.)
```

See [docs/DEVELOPMENT.md](docs/DEVELOPMENT.md) for detailed setup instructions.

## License

- Contributions to files **outside** `enterprise/` are licensed under the [GNU Affero General Public License v3.0](LICENSE).
- Contributions to the [moneat-enterprise](https://github.com/moneat-io/moneat-enterprise) repository are licensed under the Moneat Enterprise License.
- All contributors must sign the [Contributor License Agreement](CLA.md).

## Code of Conduct

Be respectful and constructive. We're building something useful together.
