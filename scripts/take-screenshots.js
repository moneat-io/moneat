#!/usr/bin/env node

/**
 * Automated screenshot generator for Moneat
 *
 * Captures screenshots of every navbar page and every tab within tabbed pages.
 * Screenshot filenames match the page/tab names (e.g., overview, issues, on-call-schedules).
 *
 * Prerequisites:
 * - Run `npm install --save-dev playwright` in the scripts directory
 * - Run `npx playwright install chromium`
 * - Ensure dashboard is running: `cd dashboard && npm run dev`
 * - Ensure backend is running with demo data available
 *
 * Runs screenshots in parallel (default: 8 concurrent pages). Override with
 * SCREENSHOT_CONCURRENCY=4 npm run screenshots
 */

const { chromium } = require('playwright');
const path = require('path');
const fs = require('fs');
const pixelmatchModule = require('pixelmatch');
const { PNG } = require('pngjs');
const pixelmatch = pixelmatchModule.default || pixelmatchModule;

const BASE_URL = process.env.BASE_URL || 'http://localhost:3000';
const SCREENSHOTS_DIR = path.join(__dirname, '../dashboard/public/screenshots');
const CONCURRENCY = parseInt(process.env.SCREENSHOT_CONCURRENCY || '8', 10);
const SCREENSHOT_THEME = 'light';
const THEME_CLASSES = [
  'dark',
  'theme-midnight',
  'theme-forest',
  'theme-sunset',
  'theme-gamer',
  'theme-retro',
  'theme-retro-dark',
  'theme-terminal',
];
const SCREENSHOT_LOCAL_STORAGE = {
  theme: SCREENSHOT_THEME,
  'screenshot-mode': 'true',
  'beta-banner-dismissed-apm-traces': 'true',
  'beta-banner-dismissed-profiles': 'true',
  'beta-banner-dismissed-security': 'true',
};
const SCREENSHOT_SESSION_STORAGE = {
  authenticated: 'true',
};

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

const VIEWPORT = { width: 1920, height: 1080 };
const requestedScreenshotNames = new Set(
  (process.env.SCREENSHOT_NAMES || '')
    .split(',')
    .map((name) => name.trim())
    .filter(Boolean)
);

// Screenshot configurations: every navbar page + every tab
// Names match page/tab labels for consistency
const SCREENSHOTS = [
  // ─── Navbar pages ─────────────────────────────────────────────────────
  { name: 'dashboard', description: 'Dashboard overview', path: '/', waitFor: 'text=Recent Issues', viewport: VIEWPORT },
  { name: 'issues', description: 'Issues', path: '/issues', waitFor: 'text=Issues', viewport: VIEWPORT },
  { name: 'performance', description: 'Performance', path: '/performance', waitFor: 'text=Performance', viewport: VIEWPORT },
  { name: 'logs', description: 'Logs', path: '/logs', waitFor: 'text=Logs', viewport: VIEWPORT },
  {
    name: 'profiles',
    description: 'Continuous profiling flamegraph',
    navigate: async (page) => {
      await page.goto(`${BASE_URL}/profiles`, { waitUntil: 'networkidle' });
      await page.waitForTimeout(1500);
      const link = page.locator('a[href^="/profiles/"]').first();
      if ((await link.count()) > 0) {
        await link.click();
        await page.waitForLoadState('networkidle');
        await page.waitForTimeout(2000);
      }
    },
    waitFor: 'text=Flamegraph',
    viewport: VIEWPORT,
  },
  { name: 'uptime', description: 'Uptime', path: '/uptime', waitFor: 'text=Uptime', viewport: VIEWPORT },
  { name: 'status-pages', description: 'Status Pages', path: '/status-pages', waitFor: 'text=Status', viewport: VIEWPORT },
  { name: 'status-page-public', description: 'Public status page', path: '/s/acme-status', waitFor: 'text=Status', viewport: VIEWPORT },
  { name: 'dashboards', description: 'Dashboards', path: '/dashboards', waitFor: 'text=Dashboards', viewport: VIEWPORT },
  { name: 'replays', description: 'Replays', path: '/replays', waitFor: 'text=Replays', viewport: VIEWPORT },
  { name: 'session-replay', description: 'Session replay (alias for replays)', path: '/replays', waitFor: 'text=Replays', viewport: VIEWPORT },
  { name: 'feedback', description: 'Feedback', path: '/feedback', waitFor: 'text=Feedback', viewport: VIEWPORT },
  { name: 'releases', description: 'Releases', path: '/releases', waitFor: 'text=Releases', viewport: VIEWPORT },
  { name: 'ai', description: 'AI', path: '/ai', waitFor: 'text=AI', viewport: VIEWPORT },
  { name: 'security', description: 'Security', path: '/security', waitFor: 'text=Security', viewport: VIEWPORT },
  { name: 'synthetics', description: 'Synthetics', path: '/synthetics', waitFor: 'text=Synthetics', viewport: VIEWPORT },
  { name: 'analytics', description: 'Analytics', path: '/analytics', waitFor: 'text=Analytics', viewport: VIEWPORT },

  // ─── On-Call tabs ─────────────────────────────────────────────────────
  { name: 'on-call-overview', description: 'On-Call Overview', path: '/on-call', waitFor: 'text=On-Call', viewport: VIEWPORT },
  { name: 'on-call-schedules', description: 'On-Call Schedules', path: '/on-call/schedules', waitFor: 'text=Schedules', viewport: VIEWPORT },
  { name: 'on-call-escalation-policies', description: 'On-Call Escalation Policies', path: '/on-call/escalation-policies', waitFor: 'text=Escalation Policies', viewport: VIEWPORT },
  { name: 'on-call-alerts', description: 'On-Call Alerts', path: '/on-call/incidents', waitFor: 'text=Alerts', viewport: VIEWPORT },
  { name: 'on-call-incidents', description: 'On-Call Incidents', path: '/on-call/declared-incidents', waitFor: 'text=Incidents', viewport: VIEWPORT },

  // ─── Monitoring ───────────────────────────────────────────────────────
  { name: 'monitoring-hosts', description: 'Monitoring Hosts', path: '/monitoring', waitFor: 'text=Hosts', viewport: VIEWPORT },

  // ─── Legacy names for landing page compatibility ──────────────────────
  { name: 'error-tracking', description: 'Error tracking (alias for issues)', path: '/issues', waitFor: 'text=Issues', viewport: VIEWPORT },
  { name: 'log-management', description: 'Log management (alias for logs)', path: '/logs', waitFor: 'text=Logs', viewport: VIEWPORT },
  { name: 'containers', description: 'Containers (alias)', path: '/monitoring/containers', waitFor: 'text=Containers', viewport: VIEWPORT },
  { name: 'escalation-policies', description: 'Escalation policies (alias)', path: '/on-call/escalation-policies', waitFor: 'text=Escalation Policies', viewport: VIEWPORT },
  {
    name: 'apm-traces',
    description: 'APM traces list',
    path: '/performance/traces',
    waitFor: 'text=Total Traces',
    viewport: VIEWPORT,
  },
];

const SELECTED_SCREENSHOTS = requestedScreenshotNames.size === 0
  ? SCREENSHOTS
  : SCREENSHOTS.filter((screenshot) => requestedScreenshotNames.has(screenshot.name));

const missingScreenshotNames = [...requestedScreenshotNames].filter(
  (name) => !SCREENSHOTS.some((screenshot) => screenshot.name === name)
);

if (missingScreenshotNames.length > 0) {
  console.error(`❌ Unknown screenshot name(s): ${missingScreenshotNames.join(', ')}`);
  process.exit(1);
}

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
      window.__moneatApplyScreenshotPreferences();
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

function createPagePool(pages) {
  const available = [...pages];
  const waitQueue = [];

  const acquire = () =>
    new Promise((resolve) => {
      if (available.length > 0) {
        resolve(available.pop());
      } else {
        waitQueue.push(resolve);
      }
    });

  const release = (page) => {
    if (waitQueue.length > 0) {
      waitQueue.shift()(page);
    } else {
      available.push(page);
    }
  };

  return { acquire, release };
}

async function suppressBanners(page) {
  try {
    await page.evaluate(() => {
      window.__moneatApplyScreenshotPreferences();
    });
  } catch {
    // Ignore if not on app origin yet
  }
}

async function takeScreenshot(page, config, pool) {
  const release = pool ? () => pool.release(page) : () => {};

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

    await suppressBanners(page);

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
    
    // Measure sidebar and header to crop to content area only
    let clip = await page.evaluate(() => {
      const sidebar = document.querySelector('.sidebar');
      if (!sidebar) {
        return { x: 0, y: 0, width: window.innerWidth, height: window.innerHeight };
      }
      const rect = sidebar.getBoundingClientRect();
      const sidebarWidth = rect.width;
      const headerHeight = rect.top;
      return {
        x: sidebarWidth,
        y: headerHeight,
        width: window.innerWidth - sidebarWidth,
        height: window.innerHeight - headerHeight,
      };
    });
    if (clip.width < 100 || clip.height < 100) {
      clip = { x: 0, y: 0, width: config.viewport.width, height: config.viewport.height };
    }
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
      console.log(`   ✅ Saved (changed): ${config.name}`);
    }

    return true;
  } catch (error) {
    console.error(`❌ Failed to capture ${config.name}:`, error.message);

    // Take a debug screenshot anyway
    try {
      const debugPath = path.join(SCREENSHOTS_DIR, `debug-${config.name}.png`);
      await page.screenshot({ path: debugPath });
      console.log(`📸 Debug screenshot: ${debugPath}`);
    } catch {
      // Ignore debug screenshot errors
    }

    return false;
  } finally {
    release();
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
    colorScheme: SCREENSHOT_THEME,
  });

  await context.addInitScript(({ localStorageEntries, sessionStorageEntries, themeClasses }) => {
    window.__moneatApplyScreenshotPreferences = () => {
      for (const [key, value] of Object.entries(localStorageEntries)) {
        localStorage.setItem(key, value);
      }
      for (const [key, value] of Object.entries(sessionStorageEntries)) {
        sessionStorage.setItem(key, value);
      }
      document.documentElement.classList.remove(...themeClasses);
    };
    window.__moneatApplyScreenshotPreferences();
  }, {
    localStorageEntries: SCREENSHOT_LOCAL_STORAGE,
    sessionStorageEntries: SCREENSHOT_SESSION_STORAGE,
    themeClasses: THEME_CLASSES,
  });
  
  const loginPage = await context.newPage();

  try {
    // Login (single page - cookies are shared across context)
    await login(loginPage);

    // Verify we're logged in
    console.log('🔍 Verifying authentication...');
    const currentUrl = loginPage.url();
    if (currentUrl.includes('/login')) {
      throw new Error('Still on login page - authentication may have failed');
    }
    console.log('✅ Authentication verified\n');

    // Create page pool for parallel capture
    const poolSize = Math.min(CONCURRENCY, SELECTED_SCREENSHOTS.length);
    console.log(`📸 Starting parallel screenshot capture (${poolSize} concurrent pages)...\n`);

    const poolPages = await Promise.all(
      Array.from({ length: poolSize }, () => context.newPage())
    );
    const pool = createPagePool(poolPages);
    await loginPage.close();

    // sessionStorage is per-tab; cookies are shared. Initialize each page with
    // sessionStorage so api.isAuthenticated() returns true (avoids login redirect).
    await Promise.all(
      poolPages.map(async (page) => {
        await page.goto(BASE_URL, { waitUntil: 'domcontentloaded' });
        await page.evaluate(() => {
          sessionStorage.setItem('authenticated', 'true');
          window.__moneatApplyScreenshotPreferences();
        });
      })
    );

    // Run all screenshots in parallel (pool limits concurrency)
    const startTime = Date.now();
    const results = await Promise.all(
      SELECTED_SCREENSHOTS.map((config) =>
        pool.acquire().then((page) => takeScreenshot(page, config, pool))
      )
    );
    const elapsed = ((Date.now() - startTime) / 1000).toFixed(1);
    const successCount = results.filter(Boolean).length;

    console.log(
      `\n✅ Screenshot capture complete! ${successCount}/${SELECTED_SCREENSHOTS.length} successful (${elapsed}s)`
    );
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
