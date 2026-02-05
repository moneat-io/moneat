# Moneat - Mobile-First Error Monitoring Platform

A Sentry-compatible error monitoring platform built with Kotlin/Ktor, focused on mobile monitoring with cost-effective VPS deployment.

## Features

- ✅ Sentry-compatible envelope ingestion API
- ✅ Real-time error tracking and grouping
- ✅ Issue management with fingerprinting
- ✅ PostgreSQL for operational data
- ✅ ClickHouse for high-performance event analytics
- ✅ JWT-based authentication
- ✅ Multi-project support
- 🚧 React dashboard (coming soon)
- 🚧 Kotlin Multiplatform SDK (coming soon)

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
    "name": "Test User"
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
- `GET /api/v1/projects` - List projects
- `GET /api/v1/projects/{id}` - Get project details
- `GET /api/v1/projects/{id}/issues` - List issues
- `GET /api/v1/issues/{id}` - Get issue detail
- `GET /api/v1/issues/{id}/events` - Get issue events

## Development

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
```

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
