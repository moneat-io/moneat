# Screenshot Automation for Moneat Homepage

This directory contains scripts to automatically generate screenshots for the Moneat homepage.

## Overview

The `take-screenshots.js` script uses Playwright to:
1. Log in as the demo user (`demo@moneat.dev` / `demo123`)
2. Navigate to various pages in the dashboard
3. Capture high-quality screenshots
4. Save them to `dashboard/public/screenshots/`

## Prerequisites

1. **Demo data seeded** - Run the demo data seeder first:
   ```bash
   ./scripts/seed-demo-data.sh
   ```

2. **Dashboard running** - Start the dashboard in development mode:
   ```bash
   cd dashboard && npm run dev
   ```

3. **Dependencies installed** - The script will auto-install Playwright on first run

## Usage

### Quick Start

From the project root or scripts directory:

```bash
./scripts/take-screenshots.sh
```

### Debug Mode

To see the browser while screenshots are being taken:

```bash
./scripts/take-screenshots.sh --debug
```

### Manual Run

If you prefer to run directly with Node:

```bash
cd scripts
npm install                          # First time only
npx playwright install chromium      # First time only
npm run screenshots
```

## What Screenshots Are Generated

The script captures these pages (matching the landing page features):

**Hero Section:**
1. **dashboard.png** - Main dashboard overview

**Primary Features:**
2. **error-tracking.png** - Issues/error tracking list
3. **log-management.png** - Log management page
4. **session-replay.png** - Session replay list

**Secondary Features:**
5. **performance.png** - Performance monitoring
6. **uptime.png** - Uptime monitoring
7. **status-pages.png** - Public status pages
8. **slack-integration.png** - Slack integration tile (cropped)
9. **incident-io-integration.png** - Incident.io integration tile (cropped)

## Configuration

Edit `take-screenshots.js` to:
- Add/remove screenshots
- Change viewport sizes (default: 1920x1080)
- Adjust wait conditions
- Modify paths or selectors

## Using Screenshots in Homepage

After running the script, you can automatically update the landing page:

```bash
cd scripts
npm run update-landing
```

Or manually update `dashboard/src/components/landing/variant-a.tsx`:

Replace mock components in `ScreenshotFrame` with real images:

```tsx
<ScreenshotFrame gradient="from-sky-500 to-cyan-400">
  <img 
    src="/screenshots/error-tracking.png" 
    alt="Error tracking" 
    className="w-full h-full object-cover" 
  />
</ScreenshotFrame>
```

## Troubleshooting

### Dashboard not running
```
❌ Dashboard is not running at http://localhost:3000
```
**Solution:** Start the dashboard with `cd dashboard && npm run dev`

### Login fails
```
❌ Failed to login
```
**Solution:** Ensure demo data is seeded with `./scripts/seed-demo-data.sh`

### Screenshot is blank or shows loading state
**Solution:** Adjust wait conditions in the `SCREENSHOTS` array in `take-screenshots.js`

### Playwright not found
```
Cannot find module 'playwright'
```
**Solution:** Run `cd scripts && npm install`

## Environment Variables

- `BASE_URL` - Dashboard URL (default: `http://localhost:3000`)
- `HEADLESS` - Set to `false` to see browser (default: `true`)

Example:
```bash
BASE_URL=http://localhost:5173 HEADLESS=false node take-screenshots.js
```
