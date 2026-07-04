# AI Agent Instructions for Moneat

Moneat is an open-source, self-hostable observability platform. It ingests
Sentry-compatible, Datadog-compatible, and OpenTelemetry/OTLP traffic, then
presents source-neutral Moneat telemetry workflows: errors, logs, traces,
metrics, services, spans, dashboards, alerts, incidents, uptime, synthetics,
security, workflows, and sources.

Treat vendor protocols as ingestion and compatibility layers, not the core
product model. Use vendor names in setup, migration, import/export,
compatibility, and troubleshooting surfaces. In core product UI/API work,
prefer telemetry concepts and source metadata over labels such as "Sentry
errors" or "Datadog traces."

## Agent Operating Defaults

- Read the surrounding implementation before editing. Prefer existing helpers,
  feature package boundaries, API clients, and tests over new abstractions.
- Keep changes scoped to the requested behavior. Do not remove real
  functionality to make a check pass.
- Fix warnings, lint, Detekt, ESLint, Sonar, and compiler findings at the root
  cause. Do not add suppressions, `NOSONAR`, or disable comments unless the rule
  is truly wrong for that line or file forever and the reason is documented.
- When asked to publish a PR/MR or green the pipeline, carry the work through
  push, GitHub check status, Sonar quality gate, and unresolved review-thread
  verification before calling it done.
- Use `git diff --check` as a final hygiene gate for code changes.
- New source files should include the existing AGPL header used by nearby
  Kotlin/TypeScript files.

## Local Environment

Keep this file portable. Do not reference personal machine names, usernames,
absolute local paths, private hostnames, or one developer's workstation-specific
service layout.

Use the public repo setup paths unless the current checkout already provides a
local wrapper or generated environment file. Prefer `.env.example`, the README,
and checked-in scripts as the public source of truth. Public self-hosting docs
and release artifacts may describe Docker Compose or Helm; keep those
instructions separate from any private workstation setup.

## Repository Map

- `backend/`: Kotlin/Ktor backend. Code is organized by feature package
  (`events`, `logs`, `otlp`, `datadog`, `dashboards`, `monitor`, `workflows`,
  `security`, `org`, `billing`, etc.), with shared infrastructure in `config`,
  `plugins`, `di`, `shared`, `monitoring`, `mcp`, `utils`, and `logging`.
- `ee/backend/`: enterprise modules that are always on the runtime classpath and
  license-gated at runtime. Keep core/EE API and DTO contracts aligned.
- `dashboard/`: React 19 + Vite app, TanStack Router file routes, shadcn-style
  UI components, docs/blog/prerender pipeline, and typed API client modules.
- `dashboard/src/lib/api/`: frontend API client, split into `modules/` and
  `types/`. Update these with backend DTO or route contract changes.
- `dashboard/src/docs/`: in-app docs MDX and sidebar. The old standalone
  Docusaurus flow is not the current docs build path.
- `emails/`: Maizzle email templates copied into backend resources when built.
- `e2e/`: Android, KMP, and web test apps that send real telemetry to a running
  Moneat instance.
- `packages/analytics/`: browser analytics package.
- `scripts/`: repo automation, screenshots, and migration-version checks.
- `charts/moneat/`: Helm chart when present. Treat it as public self-hosting
  packaging, not the local development path.

## Build, Test, and Lint

CI uses Java 21 and Node 24. The backend Gradle build compiles Kotlin with a
JVM toolchain of 17 and warnings as errors.

Backend:

```bash
cd backend
./gradlew test --no-daemon
./gradlew test --tests com.moneat.services.EventServiceTest --no-daemon
./gradlew detekt --no-daemon
./gradlew build --no-daemon
./gradlew integrationTest --no-daemon
./gradlew seedE2EData
./gradlew seedDemoData
```

Notes:

- `./gradlew build` depends on Detekt and installs `.githooks/`.
- `./gradlew check` depends on `integrationTest`; integration tests use
  Testcontainers and are heavier than normal unit/route tests.
- If Gradle reports a corrupt backend jar such as `invalid CEN header`, remove
  the stale jar and rerun backend/EE Gradle tasks sequentially rather than
  changing product logic.

Dashboard:

```bash
cd dashboard
npm ci
npm run dev
npm run lint
npm run typecheck
npm run test
npm run test:coverage
npm run build
```

`npm run build` runs lint, typecheck, Vite build, and the prerender step that
writes SEO route HTML and `sitemap.xml`.

Email templates:

```bash
cd emails
npm run dev
npm run build:production
```

Helm chart, when touching `charts/moneat/`:

```bash
helm lint --strict charts/moneat
helm template charts/moneat
helm template charts/moneat -f charts/moneat/examples/external-services.yaml
helm template charts/moneat -f charts/moneat/examples/production-like.yaml
```

## CI Gates

The required PR workflow is `.github/workflows/test.yml`.

- `migration-versions` checks PostgreSQL and ClickHouse migration numbering
  against the PR target branch.
- `backend-unit` runs `./gradlew test --no-daemon` and JaCoCo reporting.
- `backend-detekt` runs `./gradlew detekt --no-daemon`.
- `frontend-unit` runs dashboard lint, typecheck, tests, and coverage.
- `sonarqube` waits on backend/frontend jobs, compiles Kotlin bytecode, uploads
  coverage/detekt data, and enforces the Sonar quality gate.
- Coverage gates are hard by default: backend 65%, frontend 80%, global 60%.
  Add focused tests with behavior changes, especially on new code.
- For new MCP analytics or query tools, avoid long service parameter lists by
  using request DTOs, bound user-controlled arrays before query construction,
  and add focused tests for parser limits, saved-resource CRUD, and generated
  query behavior so Sonar new-code coverage has real signal.
- Change classification may skip backend or frontend jobs. Workflow, env,
  scripts, Docker/compose, and Sonar changes can force both sides.

## Architecture Conventions

### Backend

- Add routes inside the feature package that owns the domain, not a flat global
  route file unless that is the established path for the feature.
- Keep business logic in services and persistence in repositories. Repository
  interfaces live in `{domain}/repositories/`; implementations live beside
  them; row/domain data classes returned by repositories live in separate model
  files, not inside the repository interface file.
- Register new services/repositories in `backend/src/main/kotlin/com/moneat/di`
  or the relevant module hook.
- Shared API/resource helpers belong in `com.moneat.shared`.
- Enterprise-only behavior belongs in `ee/backend`; core can call through
  module interfaces or bridge hooks when needed.
- All dashboard `/v1/*` endpoints require JWT unless they are explicitly public
  or use a separate ingestion/API-key auth path.

### Storage

- PostgreSQL stores operational relational data: users, organizations, projects,
  keys, subscriptions, dashboards, workflows, settings, and other user-owned
  resources.
- ClickHouse stores high-volume telemetry/time-series data: events, issues,
  sessions, spans, logs, metrics, profiles, analytics, and similar records.
- Redis is used for caching, rate limiting, background queues, and ingestion
  buffers.
- Temporal backs workflow execution. Keep trusted and egress-only worker
  boundaries intact.

### Ingestion

- Sentry-compatible ingestion accepts DSN/project traffic under `/api/...` and
  validates project keys.
- OTLP ingestion uses `/v1/logs/otlp`, `/v1/traces/otlp`, and
  `/v1/metrics/otlp` with `Authorization: Bearer <OTLP API key>`.
- Datadog-compatible ingestion should translate into canonical Moneat telemetry
  early while preserving raw/source-specific fields needed for fidelity,
  debugging, and reprocessing.
- New ingestion pipelines should have explicit queue keys, DLQ behavior,
  operational metrics, and focused tests for enqueue/worker/failure paths.
- Redis Streams are the ingestion queue. Stream redelivery must remain
  idempotent for ClickHouse inserts and usage accounting.
- Process-role controls such as API-only, scheduler, ingestion worker, and
  workflow egress must not accidentally start unrelated background jobs.

## Public Resource IDs

Public Moneat-owned resources use opaque UUID `resource_id` values at API
boundaries. Numeric primary keys remain valid for internal joins, repositories,
ClickHouse joins, queue payloads, auth claims, and protocol-native IDs, but they
must not leak into public JSON or public route params for Moneat-owned
resources.

Rules:

- Public DTO `id` fields should normally stay named `id`, but their value should
  be the UUID resource ID string. Avoid exposing both `id` and `resourceId`
  after cleanup unless a compatibility boundary explicitly requires it.
- Do not accept auto-increment IDs in public route params. Malformed UUIDs are
  `400`; unknown or inaccessible UUIDs are `404`.
- Add `resource_id UUID NOT NULL DEFAULT gen_random_uuid()` to user-facing
  PostgreSQL tables that lack a public ID, mirror it in Exposed with UUID types,
  and add scoped uniqueness:
  `UNIQUE (organization_id, resource_id)` for organization-owned resources,
  `UNIQUE (user_id, resource_id)` for user-owned resources, or
  `UNIQUE (parent_id, resource_id)` when parent scoped.
- Prefer existing resolver/helper surfaces such as `ProjectIdResolver`,
  `PublicUuidParsing`, `PublicResourceIds`, workflow/on-call resource ID
  helpers, or the nearest domain-specific equivalent. Do not reparse UUIDs and
  reimplement scope checks ad hoc.
- Update backend DTOs, route params, repository mappings, frontend API types,
  query keys, route params, forms, fixtures, and tests in the same change.
- Add route or contract tests proving numeric path IDs are rejected, UUID path
  IDs resolve only within caller scope, and serialized responses do not contain
  numeric DB IDs.
- Keep protocol-native and third-party identifiers in their native format:
  trace IDs, span IDs, event IDs, replay IDs, container IDs, PIDs, HTTP status
  codes, cloud resource IDs, and externally supplied `resource_id` fields are
  not automatically Moneat public IDs.

When touching public DTOs, run or update
`backend/src/test/kotlin/com/moneat/contracts/PublicSerializableResourceIdsTest.kt`
if it exists on the branch.

## Dashboard and Product UX

- Wire dashboard UI to real backend APIs. Do not leave inert buttons, mock data,
  placeholder counts, or fake success paths when a real behavior exists.
- User-facing text should describe the object or action in product terms. Avoid
  raw implementation labels such as "dashboard alert" when an alert title,
  issue title, monitor name, or human-readable description exists.
- Counts and links must refer to the same concept. For example, alert episode
  counts should link to alert episode views, not declared incidents.
- Keep API types in `dashboard/src/lib/api/types` aligned with backend DTOs,
  especially UUID string IDs.
- Put frontend API calls in `dashboard/src/lib/api/modules`; do not scatter
  `fetch` calls across components.
- Route files live in `dashboard/src/routes` and export TanStack `Route`.
  Components live in domain folders under `dashboard/src/components`.
- Component filenames under `dashboard/src/components` are PascalCase except
  shadcn primitives in `components/ui`; hook files are camelCase `useXxx`.
- Prefer module-scope helpers for pure logic, and keep pure non-JSX logic in
  `.ts` files when it is tested or shared.
- Use existing shadcn/Radix primitives and `lucide-react` icons.
- Browser-smoke visible dashboard changes when practical, especially navigation,
  setup, dashboards, and docs pages.

### Custom Dashboards and Data Sources

- Preserve the handler pattern under `dashboards/services/handlers`: one
  source type handler owns connection testing, schema, and query execution.
- Non-secret connection behavior belongs in `extra_config` (scheme, base path,
  auth method, TLS mode, timeouts, source-specific options). Secrets belong in
  dedicated credential fields or encrypted storage.
- Do not guess HTTP scheme/base path from a host string when the UI provides
  explicit structured options. Keep host parsing, endpoint preview, payload
  mapping, and backend effective-host logic aligned.
- Test connection requests should mirror the saved payload's
  connection-relevant fields.
- Keep source setup copy source-neutral unless the screen is explicitly about a
  specific vendor/source.

## Kotlin and Backend Style

- No wildcard imports.
- Keep lines at or below 120 characters.
- Avoid magic numbers in production code; use named constants. Tests and seed
  data have targeted exceptions in Detekt config.
- Use current Exposed DSL:

```kotlin
Users.selectAll().where { Users.id eq userId }
```

Do not use deprecated `Table.select { ... }`.

- Keep Exposed queries inside transaction boundaries. If a helper may be called
  both inside and outside a transaction, use the existing transaction-aware
  pattern from nearby shared helpers.
- In suspend/coroutine paths, use `suspendRunCatching` from `com.moneat.utils`
  when catching failures into `Result`; plain `runCatching` can swallow
  `CancellationException`. Plain `runCatching` is fine for synchronous parsing
  helpers.
- In Ktor handlers, throw `BadRequestException` or a mapped domain exception
  for client validation. Do not rely on `require()`/`check()` producing a 4xx
  unless `StatusPages` maps it.
- For internal preconditions, use `require()` for bad arguments and `check()`
  for invalid state.
- Empty `catch` blocks are disallowed. If an exception is intentionally ignored,
  make that clear with a minimal comment or log where appropriate.
- Do not call production private methods from tests through reflection. Test
  observable behavior through public APIs/routes/services, or extract complex
  private logic into a small testable collaborator.

## Frontend TypeScript Style

- Use `globalThis.window`, `globalThis.localStorage`, and
  `globalThis.sessionStorage` for SSR-safe browser globals when needed.
- Avoid exported mutable `let`; use a `const` holder/ref for test hooks.
- Avoid nested template literals. Use `urlWithQuery` from
  `dashboard/src/lib/api/utils.ts` or simple string composition.
- Use `RegExp.exec()` for capture groups.
- Do not duplicate imports from the same module. Remove unused imports.
- Avoid unnecessary type assertions; let TypeScript narrowing work.
- Extract repeated union types into named aliases.
- Keep functions to seven or fewer parameters; use an options object when a
  call shape is growing.
- Prefer `child.remove()` over `parent.removeChild(child)`.
- Use nullish coalescing assignment (`x ??= value`) when assigning only for
  null/undefined.
- Keep cognitive complexity low with early returns and extracted helpers.

## Database Migrations

PostgreSQL migrations live in `backend/src/main/resources/db/migration/`.
ClickHouse migrations live in
`backend/src/main/resources/db/clickhouse_migration/`.

- Never modify an existing migration. Add a new `V{number}__description.sql`.
- Versions are numeric and sequential in both systems.
- ClickHouse migrations should be idempotent where practical and preserve
  checksum expectations.
- Before opening or updating a PR with migrations, compare against the target
  branch:

```bash
python3 scripts/check-migration-versions.py --base-ref origin/develop
```

- If the target branch advanced, renumber newly added migrations with:

```bash
python3 scripts/check-migration-versions.py --base-ref origin/develop --fix
```

The checker covers Flyway and ClickHouse migrations, duplicate versions, stale
added versions, and rename/delete edits to existing migrations.

## Environment and Secrets

- Use `EnvConfig.get()` for runtime configuration. Do not call
  `System.getenv()` directly outside configuration plumbing.
- Add new critical or conditional runtime variables to
  `EnvironmentValidator.kt` and `ESSENTIAL_ENV_VARS.md`.
- Production-facing defaults in code must be production URLs, not localhost.
  Localhost belongs in `.env.example` or local env files.
- Required secrets must fail fast on startup if missing or unsafe.
- Workflow secrets must remain distinct from `JWT_SECRET` and
  `DATA_SOURCE_ENCRYPTION_KEY`.
- Keep `DATA_SOURCE_ENCRYPTION_KEY` for dashboard data-source credentials; do
  not reuse workflow encryption/signing keys.

## Docs, Self-Hosting, and Release Packaging

- Current docs are MDX pages under `dashboard/src/docs/pages` plus
  `dashboard/src/docs/sidebar.ts`. Dashboard build/prerender validates the docs
  route output.
- Keep Docker Compose and Helm self-hosting instructions separate. Do not imply
  Helm users must use the interactive Docker installer.
- Helm charts should use release-pinned image tags, Kubernetes secrets for
  sensitive values, and clear external-service examples.
- If release workflow or chart packaging changes, validate both the workflow
  syntax and Helm render paths.

## Feature-Specific Notes

- Event fingerprinting in `events/services/EventService.kt` affects issue
  grouping. Change it only with focused tests and a clear migration/behavior
  story.
- Overview alerts and declared incidents are separate concepts. Do not populate
  declared-incident counts from alert episodes.
- MCP tools/resources should enforce the same auth, organization scoping, and
  public resource ID rules as REST endpoints.
- Security, workflow, on-call, dashboard, and custom-data-source changes often
  span backend, EE, dashboard API types, and tests. Audit adjacent contract
  surfaces before finishing.
- Public docs/marketing/comparison pages may name competitors and protocols;
  core app navigation and workflow copy should stay source-neutral.

## PR Review Checklist

Before handing work back:

- Relevant focused tests pass.
- Formatting/lint/static analysis for touched areas pass or any skipped command
  is called out with the reason.
- Public API changes are reflected in frontend API modules/types and fixtures.
- New env vars are validated and documented.
- Migrations pass the migration-version checker.
- Dashboard visible changes have a browser smoke where practical.
- `git diff --check` is clean.
