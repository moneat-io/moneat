# Contributing to Moneat

Thank you for your interest in contributing to Moneat! This document outlines how to contribute and the requirements for doing so.

## Dual-License Structure

Moneat uses an open-core dual-license model:

- **Core (AGPLv3)**: All code outside the `enterprise/` directory is licensed under the GNU Affero General Public License v3.0. Contributions to core are under AGPLv3 with CLA rights (see below).
- **Enterprise (Proprietary)**: All code inside the `enterprise/` directory is licensed under the Moneat Enterprise License (see `enterprise/LICENSE`). Contributions to enterprise code are under the proprietary license.

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
2. Create a feature branch from `main`
3. Make your changes following the coding conventions below
4. Ensure tests pass: `cd backend && ./gradlew test` and `cd dashboard && npm test`
5. Open a pull request against `main`
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
# Start infrastructure
docker-compose up -d

# Run backend
cd backend && ./gradlew run

# Run dashboard
cd dashboard && npm install && npm run dev
```

See [docs/DEVELOPMENT.md](docs/DEVELOPMENT.md) for detailed setup instructions.

## License

- Contributions to files **outside** `enterprise/` are licensed under the [GNU Affero General Public License v3.0](LICENSE).
- Contributions to files **inside** `enterprise/` are licensed under the [Moneat Enterprise License](enterprise/LICENSE).
- All contributors must sign the [Contributor License Agreement](CLA.md).

## Code of Conduct

Be respectful and constructive. We're building something useful together.
