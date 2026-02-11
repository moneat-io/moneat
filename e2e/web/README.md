# Moneat Web E2E Tests

Playwright-based E2E tests for the Moneat dashboard.

## Setup

```bash
npm install
npx playwright install
```

## Running Tests

```bash
# Run all tests
npm test

# Run smoke tests only (fast, PR-gated subset)
npm run test:smoke

# Run with UI mode
npm run test:ui

# Run in headed mode (see browser)
npm run test:headed

# View last test report
npm run report
```

## Test Organization

- **Smoke tests** (`@smoke` tag): Critical-path scenarios for PR validation
- **Full suite**: Comprehensive scenarios for nightly CI

## Writing Tests

1. Add new test files to `tests/`
2. Tag smoke tests with `@smoke` in the describe block
3. Use Page Object pattern for complex flows
4. Keep tests independent and idempotent
