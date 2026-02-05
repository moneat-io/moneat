# Quick Start Guide

## Prerequisites

- Docker & Docker Compose
- Java 17+
- Node.js 18+

## One-Command Setup

```bash
./setup.sh
```

This script will:
1. Start all Docker services (PostgreSQL, ClickHouse, Redis)
2. Wait for services to be healthy
3. Create test organization, project, and DSN key

## Manual Setup

If you prefer manual setup:

### 1. Start Infrastructure

```bash
docker-compose up -d
```

### 2. Start Backend

```bash
cd backend
./gradlew run
```

### 3. Start Dashboard

```bash
cd dashboard
npm install
npm run dev
```

## Testing

### Create a User

```bash
curl -X POST http://localhost:8080/auth/signup \
  -H "Content-Type: application/json" \
  -d '{"email":"test@example.com","password":"password123","name":"Test User"}'
```

### Send a Test Error

```bash
curl -X POST http://localhost:8080/api/1/store/ \
  -H "X-Sentry-Auth: Sentry sentry_key=abc123def456, sentry_version=7" \
  -H "Content-Type: application/json" \
  -d '{
    "event_id": "test123",
    "timestamp": '$(date +%s)',
    "level": "error",
    "platform": "javascript",
    "message": "Test error from curl",
    "exception": {
      "values": [{
        "type": "Error",
        "value": "This is a test error"
      }]
    }
  }'
```

## Services

- **Backend API**: http://localhost:8080
- **Dashboard**: http://localhost:3000
- **PostgreSQL**: localhost:5432 (user: moneat, password: moneat_dev_password)
- **ClickHouse**: localhost:8123 (HTTP), localhost:9000 (Native)
- **Redis**: localhost:6379

## Default Test Data

- **Organization**: Test Organization (ID: 1)
- **Project**: My Test App (ID: 1)
- **DSN**: `http://abc123def456@localhost:8080/1`

## Troubleshooting

### Services not starting

```bash
docker-compose logs -f
```

### Reset all data

```bash
docker-compose down -v
docker-compose up -d
./setup.sh
```

### Backend won't start

Check Java version:
```bash
java -version  # Should be 17+
```

### Dashboard won't start

Check Node version:
```bash
node --version  # Should be 18+
```

Reinstall dependencies:
```bash
cd dashboard
rm -rf node_modules package-lock.json
npm install
```

## Development Workflow

1. Make changes to backend code in `backend/src/`
2. Backend will auto-reload with Gradle continuous build:
   ```bash
   cd backend
   ./gradlew run --continuous
   ```

3. Make changes to dashboard code in `dashboard/src/`
4. Vite will hot-reload automatically

## Project Structure

```
moneat/
├── backend/              # Kotlin/Ktor backend
│   ├── src/main/
│   │   ├── kotlin/       # Kotlin source code
│   │   └── resources/    # Configuration and SQL scripts
│   └── build.gradle.kts
├── dashboard/            # React frontend
│   ├── src/
│   │   ├── routes/       # TanStack Router pages
│   │   ├── lib/          # Utilities and API client
│   │   └── components/   # React components
│   └── package.json
├── docker-compose.yml    # Infrastructure services
└── setup.sh             # Automated setup script
```

## Next Steps

See the main [README.md](README.md) for:
- API documentation
- Architecture details
- Deployment guide
- Roadmap
