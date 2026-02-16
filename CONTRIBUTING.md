# Contributing to Moneat

Thank you for your interest in contributing to Moneat! This document outlines how to contribute and the requirements for doing so.

## Developer Certificate of Origin (DCO)

By contributing to this project, you agree to the Developer Certificate of Origin (DCO). This means you certify that you wrote or otherwise have the right to submit the code you are contributing to the project.

You sign-off that you adhere to these requirements by adding a `Signed-off-by` line to your commit messages:

```
Signed-off-by: Your Name <your.email@example.com>
```

Git has a `-s` flag that can sign a commit for you automatically:

```bash
git commit -s -m "Your commit message"
```

The full text of the DCO is available at [developercertificate.org](https://developercertificate.org/).

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
5. Sign your commits with DCO (`git commit -s`)
6. Open a pull request against `main`

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

By contributing to Moneat, you agree that your contributions will be licensed under the [GNU Affero General Public License v3.0](LICENSE).

## Code of Conduct

Be respectful and constructive. We're building something useful together.
