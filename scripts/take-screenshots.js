#!/usr/bin/env node

/**
 * Automated screenshot generator for Moneat landing page features
 * 
 * This script captures screenshots of only the features shown on the landing page:
 * - Error tracking
 * - Log management
 * - Session replay
 * - Performance monitoring
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

// Screenshot configurations - all features shown on landing page
const SCREENSHOTS = [
  // Hero section
  {
    name: 'dashboard',
    description: 'Main dashboard overview',
    path: '/',
    waitFor: 'text=Recent Issues',
    viewport: { width: 1920, height: 1080 },
  },
  // Primary features
  {
    name: 'error-tracking',
    description: 'Error tracking / Issues list',
    path: '/',
    waitFor: 'text=Recent Issues',
    viewport: { width: 1920, height: 1080 },
  },
  {
    name: 'log-management',
    description: 'Log management page',
    navigate: async (page) => {
      // Go to projects page
      await page.goto(`${BASE_URL}/projects`, { waitUntil: 'networkidle' });
      await page.waitForTimeout(1000);
      
      // Click on the first project card/link to go to setup page
      const projectLink = await page.locator('a[href^="/projects/"]:not([href*="/settings"])').first();
      const projectExists = await projectLink.count() > 0;
      if (projectExists) {
        await projectLink.click();
        await page.waitForLoadState('networkidle');
        await page.waitForTimeout(1500);
        
        // Try to find and click logs link/tab
        try {
          const logsLink = await page.locator('a[href*="/logs"]').first();
          const logsExists = await logsLink.count() > 0;
          if (logsExists) {
            await logsLink.click();
            await page.waitForLoadState('networkidle');
            await page.waitForTimeout(1500);
          } else {
            console.log('   ⚠️  Logs tab not found in project navigation');
          }
        } catch (e) {
          console.log('   ⚠️  Could not navigate to logs');
        }
      }
    },
    viewport: { width: 1920, height: 1080 },
  },
  {
    name: 'session-replay',
    description: 'Session replay list',
    path: '/replays',
    waitFor: 'text=Replays',
    viewport: { width: 1920, height: 1080 },
  },
  // Secondary features
  {
    name: 'performance',
    description: 'Performance monitoring',
    path: '/performance',
    waitFor: 'text=Performance',
    viewport: { width: 1920, height: 1080 },
  },
  {
    name: 'uptime',
    description: 'Uptime monitoring',
    path: '/uptime',
    waitFor: 'text=Uptime',
    viewport: { width: 1920, height: 1080 },
  },
  {
    name: 'status-pages',
    description: 'Public status pages',
    navigate: async (page) => {
      // Navigate to uptime page first
      await page.goto(`${BASE_URL}/uptime`, { waitUntil: 'networkidle' });
      await page.waitForTimeout(1500);
      
      // Try to find and click on a monitor to see status page
      try {
        const monitorLink = await page.locator('a[href^="/uptime/"]').first();
        const monitorExists = await monitorLink.count() > 0;
        if (monitorExists) {
          await monitorLink.click();
          await page.waitForLoadState('networkidle');
          await page.waitForTimeout(1500);
          
          // Look for status page tab/link
          const statusLink = await page.locator('a[href*="status"], button:has-text("Status Page"), a:has-text("Status Page")').first();
          const statusExists = await statusLink.count() > 0;
          if (statusExists) {
            await statusLink.click();
            await page.waitForTimeout(1500);
          }
        } else {
          console.log('   ⚠️  No uptime monitors found for status page screenshot');
        }
      } catch (e) {
        console.log('   ⚠️  Could not navigate to status page:', e.message);
      }
    },
    viewport: { width: 1920, height: 1080 },
  },
  {
    name: 'alerting',
    description: 'Alerting & Slack integration',
    navigate: async (page) => {
      // Navigate to settings page
      await page.goto(`${BASE_URL}/settings`, { waitUntil: 'networkidle' });
      await page.waitForTimeout(1500);
      
      // Click on Integrations tab
      try {
        const integrationsTab = await page.locator('button[value="integrations"], [role="tab"]:has-text("Integrations")').first();
        const tabExists = await integrationsTab.count() > 0;
        if (tabExists) {
          await integrationsTab.click();
          await page.waitForTimeout(1500);
        } else {
          console.log('   ⚠️  Integrations tab not found in settings');
        }
      } catch (e) {
        console.log('   ⚠️  Could not navigate to integrations:', e.message);
      }
    },
    viewport: { width: 1920, height: 1080 },
  },
];

async function login(page) {
  console.log('🔐 Logging in as demo user...');
  
  try {
    await page.goto(`${BASE_URL}/login`, { waitUntil: 'networkidle' });
    
    // Wait for login form to be visible
    await page.waitForSelector('#email', { timeout: 10000 });
    
    // Fill in login form using the actual IDs from the form
    await page.fill('#email', DEMO_EMAIL);
    await page.fill('#password', DEMO_PASSWORD);
    
    console.log(`   Email: ${DEMO_EMAIL}`);
    
    // Click the sign in button
    const signInButton = await page.locator('button[type="submit"]:has-text("Sign in")').first();
    await signInButton.click();
    
    // Wait for navigation away from login page (could go to / or /projects)
    await page.waitForURL((url) => !url.pathname.includes('/login'), { timeout: 15000 });
    
    // Wait a bit for the page to fully load
    await page.waitForTimeout(2000);
    
    console.log(`✅ Logged in successfully - now at: ${page.url()}`);
  } catch (error) {
    console.error('❌ Login failed:', error.message);
    
    // Take a debug screenshot
    const debugPath = path.join(SCREENSHOTS_DIR, 'debug-login-error.png');
    await page.screenshot({ path: debugPath });
    console.log(`📸 Debug screenshot saved to: ${debugPath}`);
    
    throw error;
  }
}

async function takeScreenshot(page, config) {
  console.log(`📸 Taking screenshot: ${config.name} - ${config.description}`);
  
  try {
    // Set viewport
    await page.setViewportSize(config.viewport);
    
    // Custom navigation function if provided
    if (config.navigate) {
      await config.navigate(page);
    }
    // Otherwise use simple path navigation
    else if (config.path) {
      await page.goto(`${BASE_URL}${config.path}`, { waitUntil: 'networkidle' });
      await page.waitForTimeout(1000);
    }
    
    // Wait for specific element/text if specified
    if (config.waitFor) {
      try {
        await page.waitForSelector(config.waitFor, { timeout: 3000 });
      } catch (e) {
        console.log(`⚠️  Wait condition not met: ${config.waitFor}, proceeding anyway`);
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
    
    // Take a debug screenshot anyway
    try {
      const debugPath = path.join(SCREENSHOTS_DIR, `debug-${config.name}.png`);
      await page.screenshot({ path: debugPath });
      console.log(`📸 Debug screenshot: ${debugPath}`);
    } catch (e) {
      // Ignore debug screenshot errors
    }
    
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
    
    // Verify we're logged in by checking for common authenticated elements
    console.log('🔍 Verifying authentication...');
    const currentUrl = page.url();
    if (currentUrl.includes('/login')) {
      throw new Error('Still on login page - authentication may have failed');
    }
    console.log('✅ Authentication verified\n');
    
    console.log('📸 Starting screenshot capture...\n');
    
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
