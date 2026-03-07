# Contributing to Moneat

Thank you for your interest in contributing to Moneat! This document outlines how to contribute and the requirements for doing so.

## License

This repository uses a dual-license model. All code **outside** of `ee/` is licensed under the [GNU Affero General Public License v3.0](LICENSE). The `ee/` directory contains enterprise modules licensed under the [Moneat Enterprise License](ee/LICENSE) — source-available, but production use requires a paid subscription. See the root [LICENSE](LICENSE) file for full details.

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

Enterprise modules (On-Call, SSO) live in the `ee/` directory and are licensed under the [Moneat Enterprise License](ee/LICENSE). The `ee/` directory is a Gradle subproject included as a `runtimeOnly` dependency — enterprise classes are on the classpath at runtime but never referenced at compile time from core code (except the `EnterpriseModule` interface in `FeatureRegistry`).

Enterprise modules are always built with the project. At runtime, the `FeatureRegistry` uses Java `ServiceLoader` to discover `EnterpriseModule` implementations. Licensed modules (SSO, On-Call) only activate when a valid `MONEAT_LICENSE_KEY` is set.

```bash
# 1. Start databases
docker compose up -d postgres clickhouse redis

# 2. Run backend (enterprise modules are included automatically)
cd backend && ./gradlew run

# 3. Run dashboard
cd dashboard && npm install && npm run dev
```

**Enterprise-specific environment variables** (add to `.env` as needed):

| Variable | Required for |
|----------|-------------|
| `MONEAT_LICENSE_KEY` | Activating licensed enterprise modules (SSO, On-Call) |
| `TWILIO_ACCOUNT_SID` / `TWILIO_AUTH_TOKEN` / `TWILIO_FROM_NUMBER` | On-Call voice alerts |
| `SAML_CERT` / `SAML_KEY` / `SAML_ENTITY_ID` | SSO (SAML) |

See [ee/README.md](ee/README.md) for full enterprise module documentation.

## Contribution Licensing

- Contributions to code **outside** `ee/` are licensed under the [GNU Affero General Public License v3.0](LICENSE).
- Contributions to code **inside** `ee/` are licensed under the [Moneat Enterprise License](ee/LICENSE).
- All contributors must sign the [Contributor License Agreement](CLA.md), which grants Moneat the right to include contributions in both the open-source core and the enterprise edition.

## Code of Conduct

Be respectful and constructive. We're building something useful together.
