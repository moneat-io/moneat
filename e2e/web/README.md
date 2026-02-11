# Moneat Web E2E Tests

Playwright-based end-to-end tests for the Moneat web dashboard.

## Overview

These tests validate critical user flows:
- **Authentication**: Signup, login, logout, session persistence, password reset
- **Project Management**: Create/open projects, settings
- **Event Ingestion**: API-level event submission and dashboard visibility
- **Billing**: Usage display, error handling, budget controls

## Prerequisites

1. **Backend services running**:
   ```bash
   cd ../..
   docker-compose up -d
   ```

2. **Seed E2E data**:
   ```bash
   cd ..
   ./seed-data.sh
   ```

3. **Install dependencies**:
   ```bash
   npm install
   npx playwright install
   ```

## Running Tests

### All tests
```bash
npm test
```

### Smoke tests only (PR-gated subset)
```bash
npm run test:smoke
```

### Interactive UI mode
```bash
npm run test:ui
```

### Headed mode (see browser)
```bash
npm run test:headed
```

### View test report
```bash
npm run report
```

## Test Structure

```
tests/
├── smoke.spec.ts        # Basic health checks
├── auth.spec.ts         # Authentication flows (@smoke tagged)
├── project.spec.ts      # Project CRUD operations (@smoke tagged)
├── ingestion.spec.ts    # Event ingestion + dashboard E2E (@smoke tagged)
├── billing.spec.ts      # Billing and usage display
└── helpers/
    └── api.ts           # API helper utilities
```

## Environment Configuration

- **BASE_URL**: Dashboard URL (default: `http://localhost:5173`)
- Set via environment: `BASE_URL=http://localhost:3000 npm test`

## CI/CD Integration

### PR-Required (Fast)
```bash
npm run test:smoke
```
Expected runtime: ~2-5 minutes

### Nightly (Full)
```bash
npm test
```
Expected runtime: ~10-15 minutes

## Debugging

### Screenshots on failure
Automatically saved to `test-results/` directory.

### Trace viewer
On test failure, traces are captured. View with:
```bash
npx playwright show-trace test-results/.../trace.zip
```

### Run single test
```bash
npx playwright test auth.spec.ts
```

### Run specific test by name
```bash
npx playwright test -g "login and session persistence"
```

## Writing New Tests

### Tag smoke tests
```typescript
test('critical flow @smoke', async ({ page }) => {
  // test code
})
```

### Use API helpers
```typescript
import { MoneatAPI } from './helpers/api'

test('my test', async ({ request }) => {
  const api = new MoneatAPI(request)
  const { token } = await api.login('user@example.com', 'password')
  // ...
})
```

### Wait for ingested events
```typescript
import { waitForIssueToAppear } from './helpers/api'

const appeared = await waitForIssueToAppear(request, token, projectId, 'Error message')
expect(appeared).toBeTruthy()
```

## Stability Guidelines

- Use `@smoke` tag for <5min subset
- Avoid hard-coded waits; use `expect().toBeVisible({ timeout })`
- Clear cookies/storage in `beforeEach` for auth tests
- Use API helpers for setup over UI clicks
- Mock external services (Stripe, email) if needed

## Known Limitations

- Tests require seeded data (`e2e-test@moneat.dev` user)
- Backend must be running on `localhost:8080`
- ClickHouse ingestion has ~1-2s latency; tests account for this

## Troubleshooting

**Tests fail with "Network error"**
- Ensure backend is running: `docker-compose ps`
- Check backend logs: `docker-compose logs backend`

**Seeded user not found**
- Run: `cd .. && ./seed-data.sh`

**Timeout waiting for issue to appear**
- Check ClickHouse is running: `docker-compose ps clickhouse`
- Verify ingestion queue: `docker-compose logs backend | grep ingest`

**Dashboard not loading**
- Ensure dashboard dev server is running (Playwright auto-starts it)
- Or manually: `cd ../../dashboard && npm run dev`
