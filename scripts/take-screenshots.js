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
            
            // Set custom time range to show demo data (last 15 minutes)
            try {
              // Calculate time range (now and 15 minutes ago)
              const now = new Date();
              const fifteenMinutesAgo = new Date(now.getTime() - 15 * 60 * 1000);
              
              // Format for datetime-local input (YYYY-MM-DDTHH:mm)
              const formatDatetimeLocal = (date) => {
                const year = date.getFullYear();
                const month = String(date.getMonth() + 1).padStart(2, '0');
                const day = String(date.getDate()).padStart(2, '0');
                const hours = String(date.getHours()).padStart(2, '0');
                const minutes = String(date.getMinutes()).padStart(2, '0');
                return `${year}-${month}-${day}T${hours}:${minutes}`;
              };
              
              const fromValue = formatDatetimeLocal(fifteenMinutesAgo);
              const toValue = formatDatetimeLocal(now);
              
              // Click the time range dropdown button (has Clock icon)
              const timeButton = page.locator('button:has(svg.lucide-clock)').first();
              if (await timeButton.count() > 0) {
                await timeButton.click();
                await page.waitForTimeout(500);
                
                // Click "Custom range..." option
                const customRangeOption = page.locator('text=Custom range...').first();
                if (await customRangeOption.count() > 0) {
                  await customRangeOption.click();
                  await page.waitForTimeout(500);
                  
                  // Fill in the datetime inputs
                  const fromInput = page.locator('input[type="datetime-local"]').first();
                  const toInput = page.locator('input[type="datetime-local"]').nth(1);
                  
                  if (await fromInput.count() > 0 && await toInput.count() > 0) {
                    await fromInput.fill(fromValue);
                    await toInput.fill(toValue);
                    await page.waitForTimeout(1000);
                    
                    // Click outside to close dropdown and apply the range
                    await page.keyboard.press('Escape');
                    await page.waitForTimeout(500);
                    
                    // Ensure dropdown is closed by clicking on the main content area
                    await page.mouse.click(100, 100);
                    await page.waitForTimeout(1000);
                    
                    console.log(`   ✅ Set custom time range: ${fromValue} to ${toValue}`);
                  } else {
                    console.log('   ⚠️  Could not find datetime inputs');
                  }
                } else {
                  console.log('   ⚠️  Could not find Custom range option');
                }
              } else {
                console.log('   ⚠️  Could not find time range button');
              }
            } catch (e) {
              console.log('   ⚠️  Could not set custom time range:', e.message);
            }
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
    path: '/s/acme-status',
    waitFor: 'text=Status',
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
    elementSelector: 'text=Connect your workspace to Slack',
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
    
    // If elementSelector is provided, screenshot only that element
    if (config.elementSelector) {
      const element = page.locator(config.elementSelector).first();
      const elementExists = await element.count() > 0;
      
      if (elementExists) {
        // Find the parent container (usually a Card or section)
        const container = element.locator('xpath=ancestor::*[contains(@class, "border") or contains(@class, "card") or contains(@class, "bg-")]').first();
        const containerExists = await container.count() > 0;
        
        if (containerExists) {
          await container.screenshot({
            path: screenshotPath,
            type: 'png',
          });
          console.log(`✅ Saved (element): ${screenshotPath}`);
        } else {
          // Fallback to the element itself
          await element.screenshot({
            path: screenshotPath,
            type: 'png',
          });
          console.log(`✅ Saved (text element): ${screenshotPath}`);
        }
      } else {
        console.log(`⚠️  Element not found: ${config.elementSelector}, taking full page screenshot`);
        await page.screenshot({
          path: screenshotPath,
          fullPage: false,
          type: 'png',
        });
      }
    } else {
      // Regular full viewport screenshot
      await page.screenshot({
        path: screenshotPath,
        fullPage: false,
        type: 'png',
      });
      console.log(`✅ Saved: ${screenshotPath}`);
    }
    
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
