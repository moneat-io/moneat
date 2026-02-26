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
 * - Ensure dashboard is running: `cd dashboard && npm run dev`
 * - Ensure backend is running with demo data available
 */

const { chromium } = require('playwright');
const path = require('path');
const fs = require('fs');
const pixelmatch = require('pixelmatch');
const { PNG } = require('pngjs');

const BASE_URL = process.env.BASE_URL || 'http://localhost:3000';
const SCREENSHOTS_DIR = path.join(__dirname, '../dashboard/public/screenshots');

// Diff threshold: 0.1 = 10% of pixels can differ
const DIFF_THRESHOLD = 0.001; // 0.1% threshold for considering images identical

/**
 * Compare two PNG images and return true if they're identical (within threshold)
 */
function areImagesIdentical(existingPath, newBuffer) {
  if (!fs.existsSync(existingPath)) {
    return false; // No existing image, always save
  }
  
  try {
    const existingBuffer = fs.readFileSync(existingPath);
    const img1 = PNG.sync.read(existingBuffer);
    const img2 = PNG.sync.read(newBuffer);
    
    // Images must be same dimensions
    if (img1.width !== img2.width || img1.height !== img2.height) {
      return false;
    }
    
    const { width, height } = img1;
    const diff = new PNG({ width, height });
    
    const numDiffPixels = pixelmatch(
      img1.data,
      img2.data,
      diff.data,
      width,
      height,
      { threshold: 0.1 } // Per-pixel difference threshold
    );
    
    const totalPixels = width * height;
    const diffPercentage = numDiffPixels / totalPixels;
    
    return diffPercentage < DIFF_THRESHOLD;
  } catch (error) {
    console.warn(`  ⚠️  Failed to compare images: ${error.message}`);
    return false; // On error, save the new screenshot
  }
}

// Screenshot configurations - all features shown on landing page
const SCREENSHOTS = [
  // Hero section
  {
    name: 'dashboard',
    description: 'Main dashboard overview',
    path: '/',
    waitFor: 'text=Recent Issues',
    viewport: { width: 1200, height: 750 },
  },
  // Primary features
  {
    name: 'error-tracking',
    description: 'Error tracking / Issues list',
    path: '/issues',
    waitFor: 'text=Issues',
    viewport: { width: 1200, height: 750 },
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
    viewport: { width: 1200, height: 750 },
  },
  {
    name: 'session-replay',
    description: 'Session replay list',
    path: '/replays',
    waitFor: 'text=Replays',
    viewport: { width: 1200, height: 750 },
  },
  // Secondary features
  {
    name: 'performance',
    description: 'Performance monitoring',
    path: '/performance',
    waitFor: 'text=Performance',
    viewport: { width: 1200, height: 750 },
  },
  {
    name: 'uptime',
    description: 'Uptime monitoring',
    path: '/uptime',
    waitFor: 'text=Uptime',
    viewport: { width: 1200, height: 750 },
  },
  {
    name: 'status-pages',
    description: 'Public status pages',
    path: '/s/acme-status',
    waitFor: 'text=Status',
    viewport: { width: 1200, height: 750 },
  },
  {
    name: 'containers',
    description: 'Container monitoring with metrics',
    navigate: async (page) => {
      // Go to monitoring page
      await page.goto(`${BASE_URL}/monitoring`, { waitUntil: 'networkidle' });
      await page.waitForTimeout(1500);
      
      // Click on first system card/link
      try {
        const firstSystem = page.locator('a[href^="/monitoring/"]:not([href*="/settings"])').first();
        const systemExists = await firstSystem.count() > 0;
        if (systemExists) {
          await firstSystem.click();
          await page.waitForLoadState('networkidle');
          await page.waitForTimeout(1500);
          
          // Click on containers tab (try multiple selectors)
          let containersTab = page.locator('button[value="containers"]').first();
          let tabExists = await containersTab.count() > 0;
          
          if (!tabExists) {
            // Try alternative selector for tab button
            containersTab = page.locator('[role="tab"]:has-text("Containers"), button:has-text("Containers")').first();
            tabExists = await containersTab.count() > 0;
          }
          
          if (tabExists) {
            await containersTab.click();
            await page.waitForTimeout(1500);
            console.log('   ✅ Navigated to containers tab');
          } else {
            console.log('   ⚠️  Containers tab not found');
          }
        } else {
          console.log('   ⚠️  No monitoring systems found');
        }
      } catch (e) {
        console.log('   ⚠️  Could not navigate to containers:', e.message);
      }
    },
    viewport: { width: 1200, height: 750 },
  },
  {
    name: 'escalation-policies',
    description: 'On-call escalation policies',
    path: '/on-call/escalation-policies',
    waitFor: 'text=Escalation Policies',
    viewport: { width: 1200, height: 750 },
  },
  // New features
  {
    name: 'ai',
    description: 'AI observability overview',
    path: '/ai',
    waitFor: 'text=AI',
    viewport: { width: 1200, height: 750 },
  },
  {
    name: 'apm-traces',
    description: 'APM trace flamegraph (first trace detail)',
    navigate: async (page) => {
      await page.goto(`${BASE_URL}/apm-traces`, { waitUntil: 'networkidle' });
      await page.waitForTimeout(1500);
      
      // Click the first trace row/link
      try {
        const firstTrace = page.locator('a[href^="/apm-traces/"]').first();
        const traceExists = await firstTrace.count() > 0;
        if (traceExists) {
          await firstTrace.click();
          await page.waitForLoadState('networkidle');
          await page.waitForTimeout(2000);
          console.log('   ✅ Navigated to first APM trace');
        } else {
          console.log('   ⚠️  No APM traces found');
        }
      } catch (e) {
        console.log('   ⚠️  Could not navigate to APM trace:', e.message);
      }
    },
    viewport: { width: 1200, height: 750 },
  },
  {
    name: 'profiles',
    description: 'Continuous profiling flamegraph (first profile detail)',
    navigate: async (page) => {
      await page.goto(`${BASE_URL}/profiles`, { waitUntil: 'networkidle' });
      await page.waitForTimeout(1500);
      
      // Click the first profile row/link
      try {
        const firstProfile = page.locator('a[href^="/profiles/"]').first();
        const profileExists = await firstProfile.count() > 0;
        if (profileExists) {
          await firstProfile.click();
          await page.waitForLoadState('networkidle');
          await page.waitForTimeout(2000);
          console.log('   ✅ Navigated to first profile');
        } else {
          console.log('   ⚠️  No profiles found');
        }
      } catch (e) {
        console.log('   ⚠️  Could not navigate to profile:', e.message);
      }
    },
    viewport: { width: 1200, height: 750 },
  },
  {
    name: 'security',
    description: 'Security and compliance page',
    path: '/security',
    waitFor: 'text=Security',
    viewport: { width: 1200, height: 750 },
  },
];

async function login(page) {
  console.log('🔐 Logging in via demo route...');
  
  try {
    // Navigate to /demo which auto-authenticates and redirects to /projects
    await page.goto(`${BASE_URL}/demo`, { waitUntil: 'networkidle' });
    
    // Wait for redirect away from /demo
    await page.waitForURL((url) => !url.pathname.includes('/demo'), { timeout: 15000 });
    
    // Wait a bit for the page to fully load
    await page.waitForTimeout(2000);
    
    console.log(`✅ Logged in via demo - now at: ${page.url()}`);
    
    // Set localStorage flags to suppress banners in screenshots
    await page.evaluate(() => {
      localStorage.setItem('screenshot-mode', 'true');
      localStorage.setItem('beta-banner-dismissed-apm-traces', 'true');
      localStorage.setItem('beta-banner-dismissed-profiles', 'true');
      localStorage.setItem('beta-banner-dismissed-security', 'true');
    });
    console.log('🚫 Banners suppressed via localStorage');
  } catch (error) {
    console.error('❌ Demo login failed:', error.message);
    
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
    
    // Measure sidebar and topbar to crop them out
    const clip = await page.evaluate(() => {
      // Sidebar: fixed left column
      const sidebar = document.querySelector('.fixed.left-0.top-0');
      // Topbar: the border-b header bar with backdrop-blur
      const topbar = document.querySelector('.border-b.backdrop-blur');
      const sidebarWidth = sidebar ? sidebar.getBoundingClientRect().width : 0;
      const topbarHeight = topbar ? topbar.getBoundingClientRect().height : 0;
      return {
        x: sidebarWidth,
        y: topbarHeight,
        width: window.innerWidth - sidebarWidth,
        height: window.innerHeight - topbarHeight,
      };
    });
    console.log(`   ✂️  Cropping sidebar=${clip.x}px topbar=${clip.y}px`);
    
    // Prepare screenshot path
    const screenshotPath = path.join(SCREENSHOTS_DIR, `${config.name}.png`);
    
    // Capture screenshot to buffer first (for comparison)
    let screenshotBuffer;
    
    // If elementSelector is provided, screenshot only that element's parent card
    if (config.elementSelector) {
      const element = page.locator(config.elementSelector).first();
      const elementExists = await element.count() > 0;
      
      if (elementExists) {
        // Find the parent Card container (looking for the rounded border card that contains this element)
        // Navigate up to find the card with border-l-4 class (integration cards have colored left borders)
        const cardContainer = page.locator('[class*="border-l-4"]').filter({ has: element }).first();
        const cardExists = await cardContainer.count() > 0;
        
        if (cardExists) {
          screenshotBuffer = await cardContainer.screenshot({ type: 'png' });
        } else {
          // Fallback: try generic card/border container
          const genericContainer = page.locator('[class*="border"][class*="rounded"]').filter({ has: element }).first();
          const genericExists = await genericContainer.count() > 0;
          
          if (genericExists) {
            screenshotBuffer = await genericContainer.screenshot({ type: 'png' });
          } else {
            console.log(`⚠️  Card container not found for ${config.elementSelector}, taking full page`);
            screenshotBuffer = await page.screenshot({ fullPage: false, clip, type: 'png' });
          }
        }
      } else {
        console.log(`⚠️  Element not found: ${config.elementSelector}, taking full page screenshot`);
        screenshotBuffer = await page.screenshot({ fullPage: false, clip, type: 'png' });
      }
    } else {
      // Crop to content area only (no sidebar, no topbar)
      screenshotBuffer = await page.screenshot({ fullPage: false, clip, type: 'png' });
    }
    
    // Compare with existing screenshot (if any)
    if (areImagesIdentical(screenshotPath, screenshotBuffer)) {
      console.log(`   ⏭️  Skipped (unchanged): ${config.name}`);
    } else {
      // Save the new screenshot
      fs.writeFileSync(screenshotPath, screenshotBuffer);
      console.log(`   ✅ Saved (changed): ${screenshotPath}`);
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
    deviceScaleFactor: 2, // retina 2× for sharp, readable screenshots
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
