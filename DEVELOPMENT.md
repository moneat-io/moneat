# Development Notes

## Phase 1 Implementation Status ✅

### Completed Features

1. **Backend (Kotlin/Ktor)**
   - ✅ Sentry-compatible envelope ingestion (`/api/{projectId}/envelope/`)
   - ✅ Legacy store endpoint (`/api/{projectId}/store/`)
   - ✅ DSN authentication with project keys
   - ✅ PostgreSQL integration for operational data
   - ✅ ClickHouse integration for event storage
   - ✅ JWT-based authentication
   - ✅ Dashboard REST API endpoints
   - ✅ Event grouping with fingerprinting
   - ✅ Issue tracking with materialized views

2. **Database Schema**
   - ✅ PostgreSQL: users, organizations, projects, project_keys, subscriptions
   - ✅ ClickHouse: events, issues (with materialized view), sessions

3. **Frontend (React)**
   - ✅ Vite + React 18 + TypeScript
   - ✅ Tailwind CSS styling
   - ✅ TanStack Router for routing
   - ✅ TanStack Query for data fetching
   - ✅ Login/signup pages
   - ✅ Issues list view
   - ✅ API client with JWT auth

4. **Infrastructure**
   - ✅ Docker Compose setup (PostgreSQL, ClickHouse, Redis)
   - ✅ Automated setup script
   - ✅ Development environment configuration

### Known Limitations (MVP)

1. **Authentication**
   - No password reset
   - No email verification
   - No session management/refresh tokens

2. **Issues**
   - No issue detail page yet
   - No event detail view
   - No stack trace viewing
   - No search/filtering

3. **Projects**
   - No project creation UI
   - No project settings
   - No team management

4. **Ingestion**
   - Basic fingerprinting only
   - No source map support
   - No native crash symbolication
   - No attachment handling

5. **Performance**
   - No rate limiting implemented
   - No caching layer
   - No query optimization

### API Endpoints

#### Public (No Auth)
- `POST /auth/signup` - Create account
- `POST /auth/login` - Login
- `POST /api/{projectId}/envelope/` - Ingest Sentry envelope
- `POST /api/{projectId}/store/` - Ingest Sentry event
- `GET /api/{projectId}/security/` - CORS preflight

#### Authenticated (JWT Required)
- `GET /api/v1/projects` - List user's projects
- `GET /api/v1/projects/{id}` - Get project details
- `GET /api/v1/projects/{id}/issues` - List project issues
- `GET /api/v1/issues/{id}` - Get issue detail
- `GET /api/v1/issues/{id}/events` - Get issue events
- `GET /api/v1/projects/{id}/stats` - Get project stats

### Testing the API

#### 1. Create User
```bash
curl -X POST http://localhost:8080/auth/signup \
  -H "Content-Type: application/json" \
  -d '{"email":"dev@example.com","password":"password123","name":"Developer"}'
```

#### 2. Login
```bash
curl -X POST http://localhost:8080/auth/login \
  -H "Content-Type: application/json" \
  -d '{"email":"dev@example.com","password":"password123"}'
```

#### 3. Send Error (using test DSN)
```bash
curl -X POST http://localhost:8080/api/1/store/ \
  -H "X-Sentry-Auth: Sentry sentry_key=abc123def456, sentry_version=7" \
  -H "Content-Type: application/json" \
  -d '{
    "event_id": "'$(uuidgen)'",
    "timestamp": '$(date +%s)',
    "level": "error",
    "platform": "javascript",
    "message": "Uncaught TypeError: Cannot read property x of undefined",
    "exception": {
      "values": [{
        "type": "TypeError",
        "value": "Cannot read property x of undefined",
        "stacktrace": {
          "frames": [
            {"filename": "app.js", "function": "onClick", "lineno": 42},
            {"filename": "react.js", "function": "handleEvent", "lineno": 123}
          ]
        }
      }]
    },
    "tags": {"environment": "production", "version": "1.0.0"},
    "user": {"id": "user123", "email": "user@example.com"}
  }'
```

#### 4. Query Issues
```bash
# Get auth token first from login response, then:
curl http://localhost:8080/api/v1/projects/1/issues \
  -H "Authorization: Bearer YOUR_JWT_TOKEN"
```

### ClickHouse Queries

Connect to ClickHouse:
```bash
docker exec -it moneat-clickhouse clickhouse-client
```

Useful queries:
```sql
-- View all events
SELECT * FROM moneat.events ORDER BY timestamp DESC LIMIT 10;

-- View all issues
SELECT * FROM moneat.issues ORDER BY last_seen DESC;

-- Count events by project
SELECT project_id, count() FROM moneat.events GROUP BY project_id;

-- Count events by level
SELECT level, count() FROM moneat.events GROUP BY level;
```

### PostgreSQL Queries

Connect to PostgreSQL:
```bash
docker exec -it moneat-postgres psql -U moneat
```

Useful queries:
```sql
-- View users
SELECT * FROM users;

-- View projects with org names
SELECT p.*, o.name as org_name 
FROM projects p 
JOIN organizations o ON p.organization_id = o.id;

-- View project keys
SELECT pk.*, p.name as project_name 
FROM project_keys pk 
JOIN projects p ON pk.project_id = p.id;
```

## Next Steps (Phase 2)

### High Priority
1. Issue detail page with full event viewer
2. Stack trace visualization
3. Project creation UI
4. Source map upload endpoint
5. Basic alerting (email on new issues)

### Medium Priority
6. Search and filtering on issues
7. Issue status management (resolve, ignore)
8. Release tracking
9. Performance monitoring basics
10. Better error grouping algorithm

### Low Priority
11. Team management
12. Slack/Discord integrations
13. Custom dashboards
14. Saved searches
15. API rate limiting UI

## Architecture Decisions

### Why ClickHouse?
- Excellent compression (10x-100x vs PostgreSQL)
- Fast aggregations on time-series data
- Cheap to run (can handle billions of events on single node)
- Materialized views for real-time issue aggregation

### Why Not MongoDB/Elasticsearch?
- ClickHouse is cheaper to run and faster for our use case
- Don't need full-text search initially
- Simpler ops (fewer services to manage)

### Why Kotlin/Ktor?
- Kotlin expertise available
- Lighter than Spring Boot
- Good coroutine support for async operations
- Shared with KMP SDK (future phase)

### Why React + TanStack?
- Modern, type-safe routing (TanStack Router)
- Excellent data fetching (TanStack Query)
- Good ecosystem, easy to hire for
- shadcn/ui gives professional UI quickly

## Performance Benchmarks (TBD)

TODO: Add benchmarks after basic load testing

## Deployment Checklist (Phase 2)

- [ ] Set up VPS (Hetzner CX31)
- [ ] Configure Nginx reverse proxy
- [ ] Set up TLS with Let's Encrypt
- [ ] Configure firewall (ufw)
- [ ] Set up monitoring (Prometheus + Grafana)
- [ ] Configure log rotation
- [ ] Set up automated backups (PostgreSQL, ClickHouse)
- [ ] Configure email (Postmark/SendGrid)
- [ ] Set up CI/CD (GitHub Actions)
- [ ] Load testing
- [ ] Security audit

## Contributing

### Code Style
- Kotlin: Follow Kotlin coding conventions
- TypeScript: Use TypeScript strict mode
- Format with Prettier (frontend)
- Use ESLint (frontend)

### Git Workflow
1. Create feature branch from main
2. Make changes
3. Test locally
4. Create PR
5. Merge to main

### Testing
- Backend: Add unit tests for services
- Frontend: Add tests for critical flows
- Integration: Test API endpoints
