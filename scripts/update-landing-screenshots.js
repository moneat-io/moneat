#!/usr/bin/env node

/**
 * Updates variant-a.tsx to use real screenshots instead of mock components
 * 
 * Usage:
 *   node update-landing-screenshots.js
 * 
 * This script replaces ScreenshotFrame children with actual img tags
 * pointing to the generated screenshots.
 */

const fs = require('fs');
const path = require('path');

const VARIANT_FILE = path.join(__dirname, '../dashboard/src/components/landing/variant-a.tsx');

// Map feature identifiers to screenshot filenames
const SCREENSHOT_MAPPING = {
  'ErrorTrackingMock': {
    screenshot: 'error-tracking.png',
    alt: 'Error tracking dashboard showing issues list with stack traces and context',
  },
  'LogManagementMock': {
    screenshot: 'log-management.png',
    alt: 'Log management interface with real-time log viewer and filtering',
  },
  'SessionReplayMock': {
    screenshot: 'session-replay.png',
    alt: 'Session replay showing user interactions before errors occurred',
  },
  'PerformanceMock': {
    screenshot: 'performance.png',
    alt: 'Performance monitoring dashboard with transaction timings',
  },
  'UptimeMock': {
    screenshot: 'uptime.png',
    alt: 'Uptime monitoring with status checks and availability metrics',
  },
  'StatusPagesMock': {
    screenshot: 'status-page-public.png',
    alt: 'Public status page showing service health and incidents',
  },
  'AlertingMock': {
    screenshots: ['slack-integration.png', 'incident-io-integration.png'],
    alts: ['Slack integration tile showing connection setup', 'Incident.io integration tile showing incident creation setup'],
  },
  'HeroDashboardMock': {
    screenshot: 'dashboard.png',
    alt: 'Main dashboard overview with statistics and error trends',
  },
};

function updateLandingPage() {
  console.log('📝 Updating landing page with real screenshots...\n');
  
  if (!fs.existsSync(VARIANT_FILE)) {
    console.error(`❌ File not found: ${VARIANT_FILE}`);
    process.exit(1);
  }
  
  let content = fs.readFileSync(VARIANT_FILE, 'utf8');
  let changeCount = 0;
  
  // Replace each mock component with an img tag
  for (const [mockName, { screenshot, alt }] of Object.entries(SCREENSHOT_MAPPING)) {
    const regex = new RegExp(`<${mockName}\\s*/>`, 'g');
    const replacement = `<img src="/screenshots/${screenshot}" alt="${alt}" className="w-full h-full object-cover" />`;
    
    const matches = content.match(regex);
    if (matches) {
      content = content.replace(regex, replacement);
      changeCount += matches.length;
      console.log(`✅ Replaced ${matches.length}x <${mockName} /> with ${screenshot}`);
    }
  }
  
  if (changeCount === 0) {
    console.log('ℹ️  No mock components found to replace. Screenshots may already be in use.');
    return;
  }
  
  // Write updated content
  fs.writeFileSync(VARIANT_FILE, content, 'utf8');
  
  console.log(`\n✅ Successfully updated ${changeCount} screenshot references!`);
  console.log(`📁 File updated: ${VARIANT_FILE}`);
  console.log('\n💡 Next steps:');
  console.log('   1. Review the changes in your editor');
  console.log('   2. Verify screenshots look good in the browser');
  console.log('   3. You can still revert if needed with git');
}

// Run if called directly
if (require.main === module) {
  updateLandingPage();
}

module.exports = { updateLandingPage };
