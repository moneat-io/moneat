# Copilot Instructions for Moneat

Moneat is a Sentry-compatible error monitoring platform built with Kotlin/Ktor backend, React frontend, PostgreSQL for operational data, and ClickHouse for high-performance event analytics.

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
npm run dev              # Dev server (port 5173)
npm run build            # Production build
npm run lint             # ESLint
npm run preview          # Preview production build
```

### Email Templates (Maizzle)
```bash
cd emails
npm run dev              # Preview with live reload (port 3001)
npm run build:production # Build for production
```

Built templates are in `emails/build/templates/email/` with `{{ variable }}` placeholders for Kotlin template engine.

### Infrastructure
```bash
docker-compose up -d     # Start PostgreSQL, ClickHouse, Redis
docker-compose down      # Stop services
docker-compose down -v   # Reset data volumes
```

## Architecture Overview

### Request Flow
1. **Ingestion path**: Client → `/api/{projectId}/envelope/` → `IngestRoutes.kt` → `EventService.kt` → PostgreSQL + ClickHouse
2. **Dashboard API**: React → `/v1/*` → `ApiRoutes.kt` → `DashboardService.kt` → PostgreSQL/ClickHouse
3. **Authentication**: All `/v1/*` endpoints require JWT (except `/auth/*`)

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

## Key Conventions

### Database Migrations
- **PostgreSQL**: Uses Flyway; migrations in `backend/src/main/resources/db/migration/*.sql`
- **ClickHouse**: Custom versioned migrations in `backend/src/main/resources/db/clickhouse_migration/V*__*.sql`
- Migration naming: `V{number}__{description}.sql` (e.g., `V1__initial_schema.sql`)
- Never modify existing migrations; always create new ones

### Event Fingerprinting
Events are grouped into issues using fingerprints generated from:
1. Exception type + message
2. Stack trace (top 3 frames)
3. Platform identifier

Fingerprint logic is in `EventService.kt` - modify carefully as it affects grouping.

### Environment Configuration
- Development: `.env` file (not committed; see `.env.example`)
- Production: Environment variables passed to Docker containers
- Required vars: `DATABASE_URL`, `CLICKHOUSE_URL`, `REDIS_URL`, `JWT_SECRET`

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
- Uses Sentry DSN format: `http://{public_key}@{host}/api/{project_id}`
- `X-Sentry-Auth` header contains `sentry_key={public_key}`
- Public key validated against `project_keys` table in PostgreSQL

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
