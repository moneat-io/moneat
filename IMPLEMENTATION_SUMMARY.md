# Moneat - Implementation Complete 🎉

## Project Summary

Moneat is a **Sentry-compatible error monitoring platform** built from scratch with a focus on:
- Mobile-first monitoring
- Cost-effective VPS deployment
- Modern tech stack (Kotlin, React, ClickHouse)
- Full Sentry SDK compatibility

## What's Been Implemented

### ✅ Phase 1: MVP Foundation (COMPLETE)

#### Backend (Kotlin + Ktor)
- **Sentry-Compatible Ingestion**
  - Envelope API endpoint (`/api/{projectId}/envelope/`)
  - Legacy store endpoint (`/api/{projectId}/store/`)
  - DSN authentication with project keys
  - Event parsing and validation
  
- **Event Processing**
  - Automatic issue grouping via fingerprinting
  - ClickHouse storage for high-performance queries
  - Real-time issue aggregation with materialized views
  - Support for errors, exceptions, and stack traces
  
- **Dashboard REST API**
  - JWT authentication
  - Project management endpoints
  - Issues list with pagination
  - Issue detail with events
  - Basic statistics
  
- **Database Schema**
  - PostgreSQL: users, organizations, projects, keys, subscriptions
  - ClickHouse: events, issues, sessions (with TTL)
  - Proper indexing and partitioning

#### Frontend (React + Vite)
- **Modern Stack**
  - React 18 with TypeScript
  - TanStack Router (type-safe routing)
  - TanStack Query (data fetching)
  - Tailwind CSS (styling)
  - Prepared for shadcn/ui components
  
- **Core Pages**
  - Login/Signup flows
  - Issues list with filtering
  - Project selector
  - JWT token management
  - Responsive design

#### Infrastructure
- **Docker Compose Setup**
  - PostgreSQL 16
  - ClickHouse 24
  - Redis 7
  - Auto-initialization with SQL scripts
  - Health checks
  
- **Developer Experience**
  - One-command setup (`./setup.sh`)
  - Automated test data creation
  - API testing script (`./test-api.sh`)
  - Comprehensive documentation

## Project Structure

```
moneat/
├── backend/                      # Kotlin/Ktor API
│   ├── src/main/
│   │   ├── kotlin/com/moneat/
│   │   │   ├── plugins/         # Ktor configuration
│   │   │   ├── routes/          # API endpoints
│   │   │   ├── models/          # Data models
│   │   │   └── services/        # Business logic
│   │   └── resources/
│   │       ├── db/              # SQL init scripts
│   │       └── application.conf # Configuration
│   ├── build.gradle.kts
│   └── gradlew
├── dashboard/                    # React SPA
│   ├── src/
│   │   ├── routes/              # Pages
│   │   ├── lib/                 # API client, utils
│   │   └── components/          # React components
│   ├── package.json
│   └── vite.config.ts
├── docker-compose.yml           # Local dev infrastructure
├── setup.sh                     # Automated setup
├── test-api.sh                  # API testing
├── README.md                    # Main documentation
├── QUICKSTART.md               # Getting started guide
└── DEVELOPMENT.md              # Developer notes
```

## Tech Stack

| Component | Technology | Why? |
|-----------|-----------|------|
| Backend | Kotlin + Ktor | Lightweight, coroutines, KMP alignment |
| Frontend | React + TypeScript | Modern, type-safe, good ecosystem |
| Routing | TanStack Router | Type-safe, file-based routing |
| Data Fetching | TanStack Query | Excellent caching, dev tools |
| Styling | Tailwind CSS | Rapid UI development |
| Components | shadcn/ui (ready) | Professional, customizable |
| Auth | JWT | Stateless, simple |
| Operational DB | PostgreSQL | Reliable, ACID compliant |
| Analytics DB | ClickHouse | 10-100x compression, fast aggregations |
| Cache/Queue | Redis | Fast, simple |
| Containers | Docker Compose | Easy local dev |

## Getting Started

### Quick Start (3 commands)
```bash
./setup.sh                # Start infrastructure & create test data
cd backend && ./gradlew run    # Start API
cd dashboard && npm install && npm run dev  # Start dashboard
```

### Test It
```bash
./test-api.sh            # Run automated tests
# Then open http://localhost:3000
```

## API Endpoints

### Public
- `POST /auth/signup` - Create account
- `POST /auth/login` - Get JWT token
- `POST /api/{projectId}/envelope/` - Ingest Sentry envelope
- `POST /api/{projectId}/store/` - Ingest Sentry event (legacy)

### Authenticated
- `GET /api/v1/projects` - List projects
- `GET /api/v1/projects/{id}/issues` - List issues
- `GET /api/v1/issues/{id}` - Get issue detail
- `GET /api/v1/issues/{id}/events` - Get issue events

## Example: Send an Error

```bash
curl -X POST http://localhost:8080/api/1/store/ \
  -H "X-Sentry-Auth: Sentry sentry_key=abc123def456, sentry_version=7" \
  -H "Content-Type: application/json" \
  -d '{
    "event_id": "unique-id",
    "timestamp": 1707000000,
    "level": "error",
    "platform": "javascript",
    "message": "Something went wrong",
    "exception": {
      "values": [{
        "type": "TypeError",
        "value": "Cannot read property x of undefined"
      }]
    }
  }'
```

## What's Next

### Phase 2: Production Features
- [ ] Issue detail page with full event viewer
- [ ] Stack trace visualization
- [ ] Project creation UI
- [ ] Source map upload & symbolication
- [ ] Email alerts
- [ ] Search and filtering
- [ ] Issue management (resolve, ignore)
- [ ] Release tracking

### Phase 3: Billing & Growth
- [ ] Stripe integration
- [ ] Usage metering
- [ ] Subscription tiers
- [ ] Team management
- [ ] Slack/Discord integrations

### Phase 4: KMP SDK
- [ ] Core commonMain module
- [ ] Android implementation
- [ ] iOS implementation
- [ ] SDK documentation

### Phase 5: Deployment
- [ ] VPS setup (Hetzner)
- [ ] Nginx + TLS
- [ ] Monitoring (Prometheus/Grafana)
- [ ] CI/CD (GitHub Actions)
- [ ] Backups

## Key Design Decisions

### ClickHouse for Events
**Why?** Handles billions of events on a single node with 10-100x better compression than PostgreSQL. Perfect for time-series event data.

### Sentry Compatibility
**Why?** Leverage existing SDKs (90+ platforms) instead of building from scratch. Focus on differentiation (better mobile support, lower cost).

### Kotlin/Ktor
**Why?** Lightweight (vs Spring Boot), excellent async support, aligns with future KMP SDK.

### React + TanStack
**Why?** Modern, type-safe, excellent DX. TanStack Router/Query provide solid foundation.

## Performance Targets

| Metric | Target | Status |
|--------|--------|--------|
| Event ingestion | 10k/sec | TBD |
| Query latency (p95) | <100ms | TBD |
| Dashboard load | <1s | ✅ |
| Infrastructure cost | <$50/mo | ✅ (dev) |

## Cost Breakdown (Production Target)

| Service | Monthly | Annual |
|---------|---------|--------|
| VPS (Hetzner CX31) | $16 | $192 |
| Object Storage (B2) | $5 | $60 |
| Email (Postmark) | $10 | $120 |
| Domain + DNS | $2 | $24 |
| **Total** | **$33** | **$396** |

**Breakeven:** 4 customers at $10/mo covers infrastructure.

## Documentation

- **[README.md](README.md)** - Project overview, features, architecture
- **[QUICKSTART.md](QUICKSTART.md)** - Getting started, troubleshooting
- **[DEVELOPMENT.md](DEVELOPMENT.md)** - API docs, database queries, internals

## Contributing

1. Fork the repository
2. Create a feature branch
3. Make your changes
4. Run tests: `./test-api.sh`
5. Submit a pull request

## License

MIT License - see LICENSE file

---

**Built with ❤️ for developers who need affordable, reliable error monitoring.**

Ready to deploy? See [DEPLOYMENT.md](DEPLOYMENT.md) (coming soon)

Questions? Open an issue on GitHub!
