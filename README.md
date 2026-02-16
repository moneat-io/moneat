# Moneat - observability platform

[![License: AGPL v3](https://img.shields.io/badge/License-AGPL%20v3-blue.svg)](https://www.gnu.org/licenses/agpl-3.0)

A Sentry-compatible, open-source error monitoring platform built with Kotlin/Ktor. Self-hostable with native on-call escalation and cost-effective VPS deployment.

## Features

### Error Monitoring
- Sentry-compatible envelope ingestion API
- Real-time error tracking and grouping with fingerprinting
- Issue management with resolve/unresolve workflows
- ClickHouse-powered high-performance event analytics
- Session replays, performance transactions, and user feedback
- Source map upload and release tracking

### On-Call & Incident Management
- Native escalation engine (PagerDuty/Opsgenie-style)
- On-call schedules with rotation support
- Priority-based escalation (P0-P5) with business hours
- Push notifications and Slack DM integration
- Incident timeline and audit trail

### Dashboard
- React dashboard with TanStack Router
- Project management, issue detail views, analytics
- Visual escalation policy and schedule editors
- Infrastructure monitoring with alerts

## Self-Hosting

Moneat is designed for self-hosting. The recommended deployment uses Docker Compose on a VPS.

See [moneat-deploy](https://github.com/AElbadworthy/moneat-deploy) for the complete production setup guide, including:
- Docker Compose configuration for production
- Nginx reverse proxy setup
- SSL/TLS with Let's Encrypt
- Blue/green deployment strategy
- Database volume management

## Quick Start (Development)

### Prerequisites

- Docker and Docker Compose
- Java 17+ (for backend development)
- Node.js 18+ (for dashboard development)

### Setup

1. **Start the infrastructure:**

```bash
docker-compose up -d
```

This starts PostgreSQL (port 5499), ClickHouse (ports 8123, 9000), and Redis (port 6379).

2. **Run the backend:**

```bash
cd backend
./gradlew run
```

The API will be available at `http://localhost:8080`

3. **Run the dashboard:**

```bash
cd dashboard
npm install
npm run dev
```

The dashboard will be available at `http://localhost:5173`

### Testing the Ingestion API

```bash
# 1. Sign up a user
curl -X POST http://localhost:8080/auth/signup \
  -H "Content-Type: application/json" \
  -d '{"email": "test@example.com", "password": "password123", "name": "Test User", "acceptTerms": true, "acceptPrivacy": true, "termsVersion": "2026-02-08", "privacyVersion": "2026-02-08"}'

# 2. Create a project via the dashboard, then send a test error using the DSN
```

## Architecture

```
moneat/
├── backend/                 # Kotlin/Ktor backend
├── dashboard/               # React frontend (TanStack Router + shadcn/ui)
├── emails/                  # Maizzle email templates
├── e2e/                     # End-to-end test apps
└── docker-compose.yml       # Local development infrastructure
```

Production deployment configuration is in the separate [moneat-deploy](https://github.com/AElbadworthy/moneat-deploy) repository.

### Tech Stack

| Component | Technology |
|-----------|-----------|
| Backend | Kotlin 1.9.22, Ktor 2.3.7, Exposed ORM |
| Operational DB | PostgreSQL |
| Analytics DB | ClickHouse |
| Cache/Queue | Redis |
| Frontend | React, TanStack Router/Query, shadcn/ui |
| Email | Maizzle templates |

## API Endpoints

### Authentication
- `POST /auth/signup` - Create new user
- `POST /auth/login` - Login

### Ingestion (Sentry-compatible)
- `POST /api/{projectId}/envelope/` - Envelope format (primary)
- `POST /api/{projectId}/store/` - Legacy JSON format

### Dashboard API (requires JWT auth)
- `GET /v1/projects` - List projects
- `GET /v1/projects/{id}/issues` - List issues
- `GET /v1/issues/{id}` - Get issue detail
- `GET /v1/issues/{id}/events` - Get issue events

### On-Call API (requires JWT auth)
- `GET /v1/escalation-policies` - List/manage escalation policies
- `GET /v1/on-call/schedules` - List/manage on-call schedules
- `GET /v1/incidents` - List/manage incidents

See [docs/](docs/README.md) for full documentation.

## Development

```bash
# Backend
cd backend
./gradlew run    # Run server
./gradlew test   # Run tests

# Dashboard
cd dashboard
npm run dev      # Dev server
npm run lint     # ESLint
npm test         # Run tests

# Infrastructure
docker-compose up -d      # Start services
docker-compose down -v    # Reset data
```

See [docs/DEVELOPMENT.md](docs/DEVELOPMENT.md) for detailed setup.

## Contributing

We welcome contributions! Please read [CONTRIBUTING.md](CONTRIBUTING.md) for guidelines.

All contributions require signing off with the Developer Certificate of Origin (DCO).

## License

Copyright (C) 2026 Moneat

This program is free software: you can redistribute it and/or modify it under the terms of the [GNU Affero General Public License v3.0](LICENSE) as published by the Free Software Foundation.

This means you can self-host, modify, and redistribute Moneat, but any modifications to the source code must be made available under the same license when used to provide a network service.
