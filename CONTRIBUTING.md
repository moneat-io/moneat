# Contributing to Moneat

Thank you for your interest in contributing to Moneat! This document outlines how to contribute and the requirements for doing so.

## License

All code in this repository is licensed under the [GNU Affero General Public License v3.0](LICENSE). Enterprise features live in a separate private repository ([moneat-enterprise](https://github.com/moneat-io/moneat-enterprise)).

## Contributor License Agreement (CLA)

All contributions to Moneat require signing our [Contributor License Agreement](CLA.md). The CLA grants Moneat the right to sublicense your contributions.

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

# 4. Run dashboard
cd dashboard && npm install && npm run dev
```

**Custom paths:** If your enterprise checkout is not at `../moneat-enterprise`:

```bash
# Backend — override Gradle enterprise path
cd backend && ./gradlew run -Penterprise -PenterprisePath=/path/to/enterprise/backend
```

**How it works:** The `-Penterprise` flag adds the `:enterprise` subproject as a `runtimeOnly` dependency. The backend's `FeatureRegistry` uses Java `ServiceLoader` to auto-discover all `EnterpriseModule` implementations at startup — no additional configuration needed.

**Enterprise-specific environment variables** (add to `.env` as needed):

| Variable | Required for |
|----------|-------------|
| `TWILIO_ACCOUNT_SID` / `TWILIO_AUTH_TOKEN` / `TWILIO_FROM_NUMBER` | On-Call voice alerts |
| `SAML_CERT` / `SAML_KEY` / `SAML_ENTITY_ID` | SSO (SAML) |

#### Docker with enterprise features

```bash
# Build with enterprise backend modules (uses ../moneat-enterprise by default)
./scripts/docker-build.sh --enterprise

# Note: Enterprise overlay now applies to backend only. All UI is open source.

# Or with a custom enterprise path
ENTERPRISE_PATH=/path/to/enterprise ./scripts/docker-build.sh --enterprise

# Run with enterprise features (set MONEAT_LICENSE_KEY in your .env)
docker compose up -d

# Optionally include the Datadog agent
docker compose --profile datadog up -d

# Enterprise-specific secrets (Twilio, SAML, etc.) go in your .env file
```

See [docs/DEVELOPMENT.md](docs/DEVELOPMENT.md) for detailed setup instructions.

## License

- All contributions to this repository are licensed under the [GNU Affero General Public License v3.0](LICENSE).
- All contributors must sign the [Contributor License Agreement](CLA.md).

## Code of Conduct

Be respectful and constructive. We're building something useful together.
