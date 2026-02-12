# Moneat - Mobile-First Error Monitoring Platform

A Sentry-compatible error monitoring platform built with Kotlin/Ktor, focused on mobile monitoring with native on-call escalation and cost-effective VPS deployment.

## Features

### Error Monitoring
- ✅ Sentry-compatible envelope ingestion API
- ✅ Real-time error tracking and grouping
- ✅ Issue management with fingerprinting
- ✅ PostgreSQL for operational data
- ✅ ClickHouse for high-performance event analytics
- ✅ JWT-based authentication
- ✅ Multi-project support
- ✅ Self-monitoring with Sentry integration (optional)
- ✅ React dashboard

### On-Call & Incident Management
- ✅ Native escalation engine (PagerDuty/Opsgenie-style)
- ✅ On-call schedules with rotation support
- ✅ Priority-based escalation (P0-P5)
- ✅ Business hours configuration
- ✅ Push notifications via Expo mobile app
- ✅ Incident timeline and audit trail
- ✅ Visual escalation policy editor
- ✅ Slack DM notifications with interactive buttons

### Mobile App (Expo React Native)
- ✅ iOS and Android support
- ✅ Push notifications for on-call alerts
- ✅ Incident management (view, acknowledge, resolve)
- ✅ On-call schedule visibility
- ✅ JWT authentication with secure storage

## Quick Start

### Prerequisites

- Docker and Docker Compose
- Java 17+ (for local development)
- Node.js 18+ (for dashboard development)

### Setup

1. **Start the infrastructure:**

```bash
docker-compose up -d
```

This starts:
- PostgreSQL (port 5499)
- ClickHouse (ports 8123, 9000)
- Redis (port 6379)

2. **Build and run the backend:**

```bash
cd backend
./gradlew run
```

The API will be available at `http://localhost:8080`

### Testing the Ingestion API

1. **Sign up a user:**

```bash
curl -X POST http://localhost:8080/auth/signup \
  -H "Content-Type: application/json" \
  -d '{
    "email": "test@example.com",
    "password": "password123",
    "name": "Test User",
    "acceptTerms": true,
    "acceptPrivacy": true,
    "termsVersion": "2026-02-08",
    "privacyVersion": "2026-02-08"
  }'
```

2. **Create a project and get DSN** (manual SQL for now):

```bash
docker exec -it moneat-postgres psql -U moneat -d moneat
```

```sql
-- Insert a project
INSERT INTO projects (organization_id, name, slug, platform) 
VALUES (1, 'My App', 'my-app', 'android');

-- Insert a project key
INSERT INTO project_keys (project_id, public_key, secret_key, name, is_active) 
VALUES (1, 'abc123', 'secret123', 'Default Key', true);
```

3. **Send a test error:**

```bash
curl -X POST http://localhost:8080/api/1/store/ \
  -H "X-Sentry-Auth: Sentry sentry_key=abc123, sentry_version=7" \
  -H "Content-Type: application/json" \
  -d '{
    "event_id": "fc6d8c0c4332497da0dd03f967f87c82",
    "timestamp": '$(date +%s)',
    "level": "error",
    "platform": "javascript",
    "message": "This is a test error",
    "exception": {
      "values": [{
        "type": "Error",
        "value": "Test error message"
      }]
    }
  }'
```

## Architecture

```
moneat/
├── backend/                 # Kotlin/Ktor backend
├── dashboard/               # React frontend
├── mobile/                  # Expo React Native mobile app
├── emails/                  # Maizzle email templates
└── docker-compose.yml       # Local development infrastructure
```

### Tech Stack

**Backend:**
- Kotlin 1.9.22
- Ktor 2.3.7 (Web framework)
- Exposed (SQL DSL)
- PostgreSQL (Operational data)
- ClickHouse (Event analytics)

**Frontend:**
- React with TanStack Router
- shadcn/ui components
- TanStack Query for data fetching

**Mobile:**
- React Native with Expo
- Push notifications via Expo Push API
- JWT authentication with secure storage
- Redis (Caching, queues)

**Frontend (planned):**
- React 18
- Vite
- TanStack Query/Router
- Tailwind CSS
- shadcn/ui

## API Endpoints

### Authentication
- `POST /auth/signup` - Create new user
- `POST /auth/login` - Login

### Ingestion (Sentry-compatible)
- `POST /api/{projectId}/envelope/` - Envelope format (primary)
- `POST /api/{projectId}/store/` - Legacy JSON format
- `GET /api/{projectId}/security/` - CORS preflight

### Dashboard API (requires JWT auth)
- `GET /v1/projects` - List projects
- `GET /v1/projects/{id}` - Get project details
- `GET /v1/projects/{id}/issues` - List issues
- `GET /v1/issues/{id}` - Get issue detail
- `GET /v1/issues/{id}/events` - Get issue events

### On-Call API (requires JWT auth)
- `GET /v1/escalation-policies` - List escalation policies
- `POST /v1/escalation-policies` - Create policy
- `GET /v1/on-call/schedules` - List on-call schedules
- `POST /v1/on-call/schedules` - Create schedule
- `GET /v1/on-call/schedules/{id}/current` - Get current on-call person
- `GET /v1/incidents` - List incidents
- `POST /v1/incidents/{id}/acknowledge` - Acknowledge incident
- `POST /v1/incidents/{id}/resolve` - Resolve incident
- `POST /v1/devices` - Register mobile device for push notifications

See [docs/ONCALL.md](docs/ONCALL.md) for detailed on-call documentation.

## Development

### Demo Data & Screenshots

To seed realistic demo data for taking screenshots and demos:

```bash
# Start services first
docker-compose up -d

# Seed demo data
./scripts/seed-demo-data.sh
```

This creates:
- Demo user: `demo@moneat.dev` / `demo123`
- Organization: "Acme Mobile Inc"
- 3 projects (Android, iOS, React Native)
- ~10 realistic issues with varied error types
- Hundreds of events with realistic device info
- Multiple releases

#### Automated Screenshot Generation

To generate screenshots for the homepage/marketing site:

```bash
# Make sure dashboard is running
cd dashboard && npm run dev

# In another terminal, run screenshot automation
./scripts/take-screenshots.sh

# Or run in debug mode to watch the browser
./scripts/take-screenshots.sh --debug
```

The script uses Playwright to automatically log in, navigate pages, and capture screenshots to `dashboard/public/screenshots/`. See [scripts/README-screenshots.md](scripts/README-screenshots.md) for details.

### E2E Testing

Moneat includes end-to-end testing apps for Android and Kotlin Multiplatform:

```bash
# Set up E2E environment
cd e2e
./setup.sh

# Start Moneat services (from project root)
cd ..
docker-compose up -d

# Seed test data (creates projects, users, and DSNs)
cd e2e
./seed-data.sh

# Configure DSNs in local.properties files (copy from seed output)

# Run Android E2E app
./run-android.sh

# Or run KMP E2E app
./run-kmp.sh
```

See [e2e/README.md](e2e/README.md) for detailed instructions.

### Email Templates

Email templates are built with [Maizzle](https://maizzle.com):

```bash
cd emails
npm install
npm run dev                  # Preview with live reload
npm run build:production     # Build for production
```

Built templates are in `emails/build/templates/email/` with `{{ variable }}` placeholders.

### Backend

```bash
cd backend
./gradlew run  # Run server
./gradlew test # Run tests
```

### Infrastructure Management

```bash
# Start all services
docker-compose up -d

# View logs
docker-compose logs -f

# Stop services
docker-compose down

# Reset data
docker-compose down -v
docker-compose up -d
```

## Configuration

Environment variables:

```bash
# Database
DATABASE_URL=jdbc:postgresql://localhost:5499/moneat
DATABASE_USER=moneat
DATABASE_PASSWORD=moneat_dev_password

# ClickHouse
CLICKHOUSE_URL=http://localhost:8123
CLICKHOUSE_USER=moneat
CLICKHOUSE_PASSWORD=moneat_dev_password

# Redis
REDIS_URL=redis://localhost:6379

# JWT
JWT_SECRET=your-secret-key

# Server
PORT=8080

# Sentry (optional - for self-monitoring)
SENTRY_DSN=  # Use http://PUBLIC_KEY@localhost:8080/api/PROJECT_ID or see docs/SENTRY_SETUP.md
SENTRY_ENVIRONMENT=production
```

For self-monitoring setup (using Moneat to monitor itself!), see [docs/SENTRY_SETUP.md](docs/SENTRY_SETUP.md).

## Roadmap

- [x] Phase 1: Foundation
  - [x] Ktor backend with Sentry ingestion
  - [x] PostgreSQL + ClickHouse setup
  - [x] User authentication
  - [x] Basic API endpoints
  
- [ ] Phase 2: Dashboard
  - [ ] React dashboard
  - [ ] Issues list and detail views
  - [ ] Project management UI
  
- [ ] Phase 3: Production Features
  - [ ] Source map upload and symbolication
  - [ ] Email alerts
  - [ ] Release tracking
  - [ ] Stripe billing

- [ ] Phase 4: KMP SDK
  - [ ] Core commonMain module
  - [ ] Android implementation
  - [ ] iOS implementation

## License

MIT

## Contributing

Contributions welcome! Please open an issue or PR.
