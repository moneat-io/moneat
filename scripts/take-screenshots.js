#!/usr/bin/env node

/**
 * Automated screenshot generator for Moneat homepage
 * 
 * This script:
 * 1. Logs in as the demo user (demo@moneat.dev / demo123)
 * 2. Navigates to various pages
 * 3. Takes high-quality screenshots
 * 4. Saves them to dashboard/public/screenshots/
 * 
 * Prerequisites:
 * - Run `npm install --save-dev playwright` in the scripts directory
 * - Run `npx playwright install chromium`
 * - Ensure demo data is seeded: `./scripts/seed-demo-data.sh`
 * - Ensure dashboard is running: `cd dashboard && npm run dev`
 */

const { chromium } = require('playwright');
const path = require('path');
const fs = require('fs');

const BASE_URL = process.env.BASE_URL || 'http://localhost:3000';
const DEMO_EMAIL = 'demo@moneat.dev';
const DEMO_PASSWORD = 'demo123';
const SCREENSHOTS_DIR = path.join(__dirname, '../dashboard/public/screenshots');

// Screenshot configurations
const SCREENSHOTS = [
  {
    name: 'dashboard',
    description: 'Main dashboard overview',
    path: '/',
    waitFor: 'text=Projects', // Wait for projects to load
    viewport: { width: 1920, height: 1080 },
  },
  {
    name: 'projects',
    description: 'Projects overview',
    path: '/projects',
    waitFor: 'text=Projects',
    viewport: { width: 1920, height: 1080 },
  },
  {
    name: 'error-tracking',
    description: 'Error tracking / Issues list',
    path: '/projects',
    clickSelector: 'a[href*="/projects/"]', // Click first project
    waitFor: 'text=Issues, .issue, [data-testid="issue"]',
    viewport: { width: 1920, height: 1080 },
  },
  {
    name: 'issue-detail',
    description: 'Individual issue detail page',
    path: '/projects',
    clickSelector: 'a[href*="/projects/"]', // Will navigate to project then find issue
    waitFor: 'a[href*="/issues/"]',
    additionalClick: 'a[href*="/issues/"]:first-of-type', // Then click first issue
    viewport: { width: 1920, height: 1080 },
  },
  {
    name: 'log-management',
    description: 'Log management page',
    path: '/projects',
    clickSelector: 'a[href*="/projects/"]',
    waitFor: 'text=Logs, a[href*="/logs"]',
    additionalClick: 'a[href*="/logs"]',
    viewport: { width: 1920, height: 1080 },
  },
  {
    name: 'session-replay',
    description: 'Session replay list',
    path: '/replays',
    waitFor: 'text=Replays, text=Session',
    viewport: { width: 1920, height: 1080 },
  },
  {
    name: 'performance',
    description: 'Performance monitoring',
    path: '/performance',
    waitFor: 'text=Performance, text=Transactions',
    viewport: { width: 1920, height: 1080 },
  },
  {
    name: 'releases',
    description: 'Releases page',
    path: '/releases',
    waitFor: 'text=Releases, text=Version',
    viewport: { width: 1920, height: 1080 },
  },
];

async function login(page) {
  console.log('🔐 Logging in as demo user...');
  
  await page.goto(`${BASE_URL}/login`);
  
  // Fill in login form
  await page.fill('input[type="email"], input[name="email"]', DEMO_EMAIL);
  await page.fill('input[type="password"], input[name="password"]', DEMO_PASSWORD);
  
  // Click login button
  await page.click('button[type="submit"], button:has-text("Sign in"), button:has-text("Log in")');
  
  // Wait for navigation to complete
  await page.waitForURL(/\/(dashboard|projects|issues)?/, { timeout: 10000 });
  
  console.log('✅ Logged in successfully');
}

async function takeScreenshot(page, config) {
  console.log(`📸 Taking screenshot: ${config.name} - ${config.description}`);
  
  try {
    // Set viewport
    await page.setViewportSize(config.viewport);
    
    // Navigate to page
    if (config.path) {
      await page.goto(`${BASE_URL}${config.path}`, { waitUntil: 'networkidle' });
    }
    
    // Click element if specified
    if (config.clickSelector) {
      const element = await page.$(config.clickSelector);
      if (element) {
        await element.click();
        await page.waitForLoadState('networkidle');
        await page.waitForTimeout(1000);
      } else {
        console.log(`⚠️  Element not found: ${config.clickSelector}, skipping click`);
      }
    }
    
    // Wait for specific element/text if specified
    if (config.waitFor) {
      try {
        await page.waitForSelector(config.waitFor, { timeout: 5000 });
      } catch (e) {
        console.log(`⚠️  Wait condition not met: ${config.waitFor}, proceeding anyway`);
      }
    }

    // Additional click if specified (for nested navigation)
    if (config.additionalClick) {
      try {
        const element = await page.$(config.additionalClick);
        if (element) {
          await element.click();
          await page.waitForLoadState('networkidle');
          await page.waitForTimeout(1000);
        } else {
          console.log(`⚠️  Additional click element not found: ${config.additionalClick}`);
        }
      } catch (e) {
        console.log(`⚠️  Additional click failed: ${e.message}`);
      }
    }
    
    // Additional wait for any animations to complete
    await page.waitForTimeout(1000);
    
    // Take screenshot
    const screenshotPath = path.join(SCREENSHOTS_DIR, `${config.name}.png`);
    await page.screenshot({
      path: screenshotPath,
      fullPage: false,
      type: 'png',
    });
    
    console.log(`✅ Saved: ${screenshotPath}`);
    return true;
  } catch (error) {
    console.error(`❌ Failed to capture ${config.name}:`, error.message);
    return false;
  }
}

async function main() {
  console.log('🎬 Starting screenshot automation...\n');
  
  // Ensure screenshots directory exists
  if (!fs.existsSync(SCREENSHOTS_DIR)) {
    fs.mkdirSync(SCREENSHOTS_DIR, { recursive: true });
    console.log(`📁 Created directory: ${SCREENSHOTS_DIR}\n`);
  }
  
  // Launch browser
  console.log('🌐 Launching browser...');
  const browser = await chromium.launch({
    headless: process.env.HEADLESS !== 'false', // Set HEADLESS=false to see browser
  });
  
  const context = await browser.newContext({
    userAgent: 'Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/537.36',
  });
  
  const page = await context.newPage();
  
  try {
    // Login
    await login(page);
    
    console.log('\n📸 Starting screenshot capture...\n');
    
    // Take screenshots
    let successCount = 0;
    for (const config of SCREENSHOTS) {
      const success = await takeScreenshot(page, config);
      if (success) successCount++;
      
      // Small delay between screenshots
      await page.waitForTimeout(500);
    }
    
    console.log(`\n✅ Screenshot capture complete! ${successCount}/${SCREENSHOTS.length} successful`);
    console.log(`\n📁 Screenshots saved to: ${SCREENSHOTS_DIR}`);
    console.log('\n💡 Next steps:');
    console.log('   1. Review the screenshots in dashboard/public/screenshots/');
    console.log('   2. Update variant-a.tsx to use real images:');
    console.log('      Replace mock components with:');
    console.log('      <img src="/screenshots/[name].png" alt="..." className="w-full h-full object-cover" />');
    
  } catch (error) {
    console.error('❌ Error during screenshot automation:', error);
    process.exit(1);
  } finally {
    await browser.close();
  }
}

// Run if called directly
if (require.main === module) {
  main().catch(console.error);
}

module.exports = { main };
