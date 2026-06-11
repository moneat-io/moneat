# AI Agent Instructions for Moneat

Moneat is a Sentry-compatible, OpenTelemetry-compatible observability platform built with Kotlin/Ktor backend, React frontend, PostgreSQL for operational data, and ClickHouse for high-performance event analytics.

## Build, Test, and Lint

### Backend (Kotlin/Ktor)
```bash
cd backend
./gradlew build          # Build and test
./gradlew test           # Run tests only
./gradlew run            # Run locally (uses port 8080)
./gradlew shadowJar      # Build fat JAR for production
./gradlew seedE2EData    # Seed E2E test data
```

**Run single test class:**
```bash
./gradlew test --tests com.moneat.services.EventServiceTest
```

**Run single test method:**
```bash
./gradlew test --tests com.moneat.services.EventServiceTest.testEventIngestion
```

### Dashboard (React/Vite)
```bash
cd dashboard
npm run dev              # Dev server (port 3000)
npm run build            # Production build
npm run lint             # ESLint
npm run preview          # Preview production build
```

### Documentation (Docusaurus)
```bash
cd docs
npm install
npm run start            # Dev server (port 3000)
npm run build            # Production build (output in docs/build/)
npm run serve            # Serve built docs locally
```

The docs are built alongside the dashboard in the Docker image and served at `/docs/` by nginx.

### Email Templates (Maizzle)
```bash
cd emails
npm run dev              # Preview with live reload (port 3001)
npm run build:production # Build for production
```

Built templates are in `emails/build/templates/email/` with `{{ variable }}` placeholders for Kotlin template engine.

### Infrastructure

**IMPORTANT:**
- **Database services** (PostgreSQL, ClickHouse, Redis) run via Docker (see `docker-compose.yml`)
- **Backend** (Kotlin/Ktor) runs **locally** on port 8080 and connects to the database services
- **Frontend** (React/Vite) runs **locally** on port 3000

Start infrastructure with `docker-compose up -d`, then run the backend and frontend locally.

Backend connects to services at (configurable via `.env`):
- PostgreSQL: `localhost:5499`
- ClickHouse: `localhost:8123` (HTTP), `localhost:9000` (native)
- Redis: `localhost:6379`

## Architecture Overview

### Request Flow
1. **Sentry ingestion**: Client → `/api/{projectId}/envelope/` → `IngestRoutes.kt` → `EventService.kt` → PostgreSQL + ClickHouse
2. **OTLP ingestion**: Client → `/v1/{logs,traces,metrics}/otlp` → `OtlpTraceRoutes.kt` / `OtlpMetricsRoutes.kt` / `LogRoutes.kt` → ClickHouse (authenticated via `Authorization: Bearer <OTLP API key>`)
3. **Dashboard API**: React → `/v1/*` → `ApiRoutes.kt` → `DashboardService.kt` → PostgreSQL/ClickHouse
4. **Authentication**: All `/v1/*` endpoints require JWT (except `/auth/*` and OTLP ingestion endpoints which use OTLP API keys)

### Data Storage Strategy
- **PostgreSQL**: Users, organizations, projects, project_keys, subscriptions (relational data)
- **ClickHouse**: Events, issues (materialized view), sessions, spans, logs (high-volume time-series)
- **Redis**: Caching, rate limiting, background job queues

### Backend Package Structure
```
com.moneat/
├── Application.kt           # Entry point, configures plugins
├── config/                  # Environment, Sentry, ClickHouse, Redis clients
├── plugins/                 # Ktor plugins: Security, HTTP, Routing, Databases, etc.
├── routes/                  # HTTP endpoints: IngestRoutes, ApiRoutes, AuthRoutes
├── services/                # Business logic: EventService, DashboardService, AuthService
├── models/                  # Data classes: SentryModels, ApiModels, database tables
├── otlp/                    # OpenTelemetry (OTLP) ingestion: routes, services, auth, parsing
│   ├── routes/              # OtlpTraceRoutes, OtlpMetricsRoutes
│   ├── services/            # OtlpTraceService, OtlpMetricsService, OtlpApiKeyService
│   └── models/              # OtlpApiKeyModels
├── utils/                   # Shared utilities
└── logging/                 # Custom logging configuration
```

### Frontend Structure
```
dashboard/src/
├── routes/                  # TanStack Router file-based routes
├── components/              # Reusable UI components (shadcn/ui)
├── lib/api.ts               # API client with JWT auth
├── hooks/                   # React hooks
└── contexts/                # React contexts (auth, etc.)
```

### Dashboard: TypeScript & ESLint (Sonar-friendly)
To avoid Sonar/ESLint code smells in `dashboard/`:

**Exports & globals:**
- ❌ **Don't export mutable `let`**: `export let x = null`
- ✅ **Use `const` with object/ref**: `export const x = { current: null }` for test hooks, etc.
- ✅ **Prefer `globalThis.window`** over `window` (SSR-safe)
- ✅ **Prefer `globalThis.sessionStorage`** / `globalThis.localStorage` over bare globals

**Assignments & operators:**
- ✅ **Use nullish coalescing assignment** when assigning only if null: `x ??= value` instead of `if (!x) x = value`

**Strings & DOM:**
- ❌ **Avoid nested template literals**: `` `${base}/path${qs ? `?${qs}` : ''}` ``
- ✅ **Use `urlWithQuery(path, qs)`** from `dashboard/src/lib/api/utils.ts` or string concat: `path + (qs ? '?' + qs : '')`
- ✅ **Use `child.remove()`** instead of `parent.removeChild(child)`

**Regex:**
- ✅ **Use `RegExp.exec()`** instead of `string.match()` when you need capture groups

**Functions & types:**
- ✅ **Limit parameters to ≤7** – use an options object for functions with many params
- ✅ **Use type aliases** for repeated union types: `type Status = 'a' | 'b' | 'c'`
- ✅ **Re-export with `export { X } from './path'`** instead of import-then-export

**Complexity:**
- **Keep cognitive complexity ≤15** – extract helpers, use early returns, avoid deep nesting

## Key Conventions

### Testing Guidelines

- ❌ **Do not call production private methods from new or touched tests** — avoid reflection helpers like
  `getDeclaredMethod`, `declaredFunctions`, `isAccessible = true`, or equivalent access hacks for behavior coverage.
- ✅ **Test observable behavior through public APIs, routes, or service methods** whenever practical.
- ✅ **Extract complex private logic into a small collaborator** with an explicit `internal` or public API when direct
  unit coverage is warranted.
- ✅ **Test-local private helper methods are fine** — the rule is about reaching into production internals.

### Warnings and lint fixes (suppress rarely)

**CRITICAL:** When asked to fix a compiler warning, linter finding, or static analysis issue (Detekt, ESLint, Sonar, Kotlin compiler, etc.):

- ❌ **Do not suppress to defer work** — do not use `@Suppress`, `eslint-disable`, `// NOSONAR`, `SuppressWarnings`, file- or line-level ignores, or similar because fixing the finding properly is too large or inconvenient right now. That is not a valid reason to silence the tool.
- ✅ **Fix the root cause** — refactor, correct types, satisfy the rule, or extract helpers so the violation is resolved properly.
- ✅ **Suppress only when it is appropriate forever** — e.g. a documented false positive, or a case where the rule genuinely does not apply on that line and a short comment explains why. **New feature code** should not ship with suppressions that are really “TODO fix later”; land the feature clean or fix the underlying issue before merge.
- ✅ **When the rule genuinely does not fit the file** — e.g. a demo data seeder may trigger hundreds of “magic number” findings: those literals *are* the seed data, not something that should become hundreds of named constants in one file. Prefer **configuration** (exclude that file or path in `detekt.yml`, ESLint `overrides`, etc.) so the tool skips that context, rather than line-level suppressions or a wall of constants that add no clarity.

If a rule is wrong for the whole project, **adjust the tool configuration** (e.g. `detekt.yml`, ESLint config) deliberately — do not mask individual violations with suppressions.

### Detekt Code Style Guidelines
**CRITICAL:** Follow detekt rules to maintain code quality. The project enforces these via CI:

- ❌ **NEVER use wildcard imports**: `import com.moneat.models.*`
- ✅ **ALWAYS use explicit imports**: `import com.moneat.models.User`

- ❌ **NEVER exceed 120 characters per line**
- ✅ **Keep lines under 120 characters** - break long lines appropriately

- ❌ **Don't use magic numbers**: `if (count > 5)`
- ✅ **Use named constants**: `const val MAX_RETRIES = 5`

- **Run detekt before committing:**
  ```bash
  cd backend
  ./gradlew detekt          # Check for issues
  ./gradlew detektFormat    # Auto-fix formatting
  ```

- **SonarQube** runs in CI as the `sonarqube` job in `.github/workflows/test.yml` (after `backend-unit`). Follow the "Kotlin: Validation & Control Flow" and "Dashboard: TypeScript & ESLint" guidelines below to avoid common Sonar code smells.

While some rules are currently disabled in `detekt.yml`, always follow best practices for new code:
- Use explicit imports (no wildcards)
- Keep lines ≤ 120 characters
- Extract complex conditions into named variables
- Limit function parameters (prefer data classes)
- Use descriptive variable/function names

### Frontend TypeScript Code Style Guidelines
The dashboard code is checked by SonarCloud. Follow these rules when writing TypeScript/React code:

- ❌ **Don't use `typeof x !== 'undefined'`** — compare directly: `x !== undefined`
- ❌ **Don't duplicate module imports** — merge all imports from the same path into one statement
- ❌ **Don't use nested template literals** — use a helper (e.g. `urlWithQuery`) or a variable instead
- ❌ **Don't leave unused imports** — remove or convert to a re-export if needed
- ❌ **Don't add unnecessary type assertions** — avoid `as SomeType` when TypeScript already narrows the type
- ✅ **Extract repeated union types into a named type alias** — e.g. `type IncidentSeverity = 'CRITICAL' | 'HIGH' | 'MEDIUM' | 'LOW' | null`
- ✅ **Define helper functions at module scope** when they don't close over instance state — avoids the "move function to outer scope" smell

### Kotlin: Validation & Control Flow (Sonar-friendly)
To avoid Sonar code smells and keep code consistent:

- ❌ **Don't use if-throw for validation**: `if (x !in valid) throw IllegalArgumentException("msg")`
- ✅ **Use `require()` for internal argument preconditions**: `require(x in valid) { "Invalid value" }` — use only in internal helpers/library code, not Ktor handlers (throws `IllegalArgumentException` → HTTP 500 unless mapped by `StatusPages`)
- ✅ **Use `check()` for internal state preconditions**: `check(existing == null) { "Already exists" }` — same caveat; use only where `IllegalStateException` is appropriate internally
- ✅ **Replace if-throw with `check()` for response validation**: `check(!response.isError()) { "Error: ${body.take(500)}" }` instead of `if (response.isError()) throw IllegalStateException(...)` — Sonar prefers this pattern
- ✅ **In Ktor route handlers**, throw `BadRequestException` (or domain-specific exceptions) for client validation errors, or explicitly map `IllegalArgumentException`/`IllegalStateException` to 4xx/409 via the `StatusPages` plugin — do not rely on `require()`/`check()` inside handlers unless `StatusPages` maps them

- **Remove unused local variables** – delete any variable that is never read.

- **Keep cognitive complexity low (≤15)** – extract helper functions when logic gets nested:
  - Use `runCatching { }.getOrNull()` only in non-suspending, synchronous helpers (e.g., parsing/mapping functions). **Avoid in suspending/coroutine contexts**: `runCatching` swallows all `Throwable`s including `CancellationException`, which can break coroutine cancellation. In suspend functions, use `suspendRunCatching` from `com.moneat.utils` instead — it rethrows `CancellationException` automatically and composes with the `Result` API (`getOrElse`, `getOrNull`, `getOrDefault`, `onFailure`). Keep the manual `catch (e: CancellationException) { throw e }` pattern only when `Result` doesn't compose cleanly (e.g. the catch block mutates state and then rethrows).
  - In Ktor route handlers, `coroutineScope`, `async`, workers, and other coroutine paths, wrap failures with
    `suspendRunCatching { ... }` whenever the guarded call may suspend. Reserve plain `runCatching` for synchronous
    parsing/formatting helpers.
  - Extract complex parsing or mapping into private functions
  - Prefer early returns over nested conditionals

### Exposed DSL (Kotlin Database ORM)
**CRITICAL:** Always use the current Exposed DSL syntax to avoid deprecation warnings:

- ❌ **DEPRECATED**: `Table.select { condition }`
- ✅ **CORRECT**: `Table.selectAll().where { condition }`

**Examples:**
```kotlin
// BAD - deprecated
Users.select { Users.id eq userId }
Users.select { (Users.id eq userId) and (Users.active eq true) }

// GOOD - current syntax
Users.selectAll().where { Users.id eq userId }
Users.selectAll().where { (Users.id eq userId) and (Users.active eq true) }
```

This applies to all tables and joins. The project uses `-Werror` (warnings as errors), so deprecated DSL will cause build failures.

### Repository Layer
When adding or extending the repository layer:

- ❌ **NEVER put domain models in the same file as a repository interface**
- ✅ **ALWAYS put domain models in separate files** — e.g. `ProjectRow` in `repositories/models/ProjectRow.kt` or `models/ProjectRow.kt`, not in `ProjectRepository.kt`
- Repository interfaces live in `{domain}/repositories/{Entity}Repository.kt`
- Implementations live in `{domain}/repositories/{Entity}RepositoryImpl.kt`
- Domain models (data classes returned by repositories) live in their own files under `{domain}/repositories/models/` or `{domain}/models/`

### Database Migrations
- **PostgreSQL**: Uses Flyway; migrations in `backend/src/main/resources/db/migration/*.sql`
- **ClickHouse**: Custom versioned migrations in `backend/src/main/resources/db/clickhouse_migration/V*__*.sql`
- Migration naming: `V{number}__{description}.sql` (e.g., `V1__initial_schema.sql`)
- Never modify existing migrations; always create new ones

### Public Resource IDs
Public Moneat-owned resources should use opaque UUID `resource_id` values at API boundaries. Keep numeric primary keys
for internal joins, repository internals, ClickHouse joins, queue payloads, and auth claims unless those values are
directly serialized to users.

- Do not expose auto-increment database IDs in public JSON responses or accept them in public route params.
- Public DTO `id` fields should usually remain named `id`, but their value should be the UUID `resource_id`; avoid
  exposing both `id` and `resourceId` after cleanup.
- Add `resource_id UUID NOT NULL DEFAULT gen_random_uuid()` to user-facing PostgreSQL tables that lack a public ID.
  Mirror the column in Exposed with UUID types.
- Add scoped uniqueness for new public IDs. Use `UNIQUE (organization_id, resource_id)` for organization-owned
  resources, `UNIQUE (user_id, resource_id)` for user-owned resources, `UNIQUE (parent_id, resource_id)` when route
  resolution is always parent-scoped, and `UNIQUE (resource_id)` only for truly global resources.
- Resolver helpers should parse UUID route params, reject malformed IDs with 400, resolve the numeric primary key under
  the caller's organization/user scope, and return 404 for unknown or inaccessible resources.
- Update backend DTOs, route params, frontend API types, query keys, forms, and tests in the same branch so no numeric
  public ID contract remains.
- Add route or contract tests proving numeric path IDs are rejected, UUID path IDs resolve only within caller scope, and
  serialized public responses do not include numeric DB IDs.
- Do not migrate protocol-native or third-party IDs just because they look numeric or UUID-like. Leave trace IDs, span
  IDs, event IDs, replay IDs, container IDs, PIDs, status codes, and externally supplied cloud/security `resource_id`
  values in their native format unless Moneat owns that resource identity.

### Event Fingerprinting
Events are grouped into issues using fingerprints generated from:
1. Exception type + message
2. Stack trace (top 3 frames)
3. Platform identifier

Fingerprint logic is in `EventService.kt` - modify carefully as it affects grouping.

### Environment Configuration
- Development: `.env` file (not committed; see `.env.example`)
- Production: Environment variables passed to Docker containers
- **CRITICAL**: See `ESSENTIAL_ENV_VARS.md` for complete list of required variables
- Required vars: `JWT_SECRET`, `DATA_SOURCE_ENCRYPTION_KEY`, `DATABASE_PASSWORD`, `CLICKHOUSE_PASSWORD`, `FRONTEND_URL`, `BACKEND_URL`
- Application validates environment on startup and fails fast if critical variables are missing or unsafe
- **NEVER use `System.getenv()` directly** - Always use `EnvConfig.get()` which handles both environment variables and `.env` files consistently

### Production Safety Rules
**IMPORTANT:** When writing code that uses environment variables or configurable URLs:

1. **Never use localhost defaults for production-facing configurations**
   - ❌ BAD: `val frontendUrl = EnvConfig.get("FRONTEND_URL", "http://localhost:3000")`
   - ✅ GOOD: `val frontendUrl = EnvConfig.get("FRONTEND_URL", "https://moneat.io")`

2. **Always use production URLs as defaults in code**
   - Frontend URL should default to `https://moneat.io`
   - Backend URL should default to `https://api.moneat.io`
   - Localhost URLs should ONLY be in `.env.example` files for local development

3. **Add validation for critical environment variables**
   - Any new critical config (secrets, passwords, keys) must be added to `EnvironmentValidator.kt`
   - Validation should fail fast on application startup if missing
   - Reference: `backend/src/main/kotlin/com/moneat/config/EnvironmentValidator.kt`

4. **Document new environment variables**
   - Update `ESSENTIAL_ENV_VARS.md` with any new required variables
   - Mark as CRITICAL if the application cannot run safely without it
   - Mark as CONDITIONAL if only required when a feature is enabled

5. **Local development should be explicit**
   - Developers should explicitly set `FRONTEND_URL=http://localhost:3000` in their local `.env`
   - Do not force localhost defaults in code just for convenience

### Self-Monitoring
Moneat can monitor itself using Sentry SDK:
- Set `SENTRY_DSN` to point to a Moneat project (see `docs/SENTRY_SETUP.md`)
- Backend errors auto-reported via `SentryConfig.kt`
- Dashboard uses `@sentry/react` for frontend errors

### Authentication Flow
1. User signs up/logs in via `/auth/signup` or `/auth/login`
2. Backend returns JWT token (stored in localStorage)
3. Dashboard includes token in `Authorization: Bearer {token}` header
4. Backend validates JWT in `Security.kt` plugin

### Ingestion Authentication
- **Sentry**: Uses DSN format: `http://{public_key}@{host}/api/{project_id}` with `X-Sentry-Auth` header containing `sentry_key={public_key}`. Public key validated against `project_keys` table in PostgreSQL.
- **OTLP**: Uses `Authorization: Bearer <OTLP API key>` header. OTLP API keys are organization-level and shared across logs, traces, and metrics. Managed via `OtlpApiKeyService` and the Settings → OTLP API Keys UI.

### E2E Testing
The `e2e/` directory contains Android and KMP test apps:
```bash
cd e2e
./seed-data.sh           # Creates test users, projects, DSNs
./run-android.sh         # Run Android test app
./run-kmp.sh             # Run KMP test app
```
These apps send real errors to the local Moneat instance for integration testing.

## Common Patterns

### Adding a New API Endpoint
1. Define route in `routes/ApiRoutes.kt` or `IngestRoutes.kt`
2. Add business logic to appropriate service in `services/`
3. Create/update models in `models/ApiModels.kt`
4. Add JWT auth with `authenticate("jwt-auth") { ... }` for protected endpoints

### Adding a New ClickHouse Table
1. Create migration in `backend/src/main/resources/db/clickhouse_migration/V{N}__*.sql`
2. Update `ClickHouseClient.kt` if custom queries needed
3. Add corresponding model in `models/`
4. Increment version in `ClickHouseMigrations.kt`

### Adding a Dashboard Route
1. Create file in `dashboard/src/routes/{name}.tsx` (TanStack Router file-based routing)
2. Define route component with `createFileRoute` export
3. Add navigation link in `__root.tsx` if needed
4. Use `useQuery` from TanStack Query for data fetching

## Deployment

Production deployment uses blue/green strategy on DigitalOcean droplet:
- See `DEPLOYMENT.md` for full setup
- CI/CD via GitHub Actions (`.github/workflows/deploy.yml`)
- Docker Compose with volumes for persistent data
- Nginx reverse proxy with SSL (Let's Encrypt)

## Testing Notes

- Backend tests use H2 in-memory database (not PostgreSQL)
- Test fixtures in `backend/src/test/kotlin/`
- E2E tests require running infrastructure (`docker-compose up -d`)
- Use `// ──── Label ────` (4 × `─` U+2500 on each side) to separate logical sections within a test file
