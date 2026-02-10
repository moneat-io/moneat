#!/usr/bin/env node
const { chromium } = require('playwright');

const BASE_URL = 'http://localhost:3000';
const DEMO_EMAIL = 'demo@moneat.dev';
const DEMO_PASSWORD = 'demo123';

async function testLogin() {
  console.log('Testing login...');
  
  const browser = await chromium.launch({ headless: false });
  const page = await browser.newPage();
  
  try {
    // Go to login
    await page.goto(`${BASE_URL}/login`);
    console.log('✓ Navigated to login page');
    
    // Wait for form
    await page.waitForSelector('#email', { timeout: 5000 });
    console.log('✓ Login form loaded');
    
    // Fill form
    await page.fill('#email', DEMO_EMAIL);
    await page.fill('#password', DEMO_PASSWORD);
    console.log('✓ Filled credentials');
    
    // Click submit
    await page.locator('button[type="submit"]:has-text("Sign in")').first().click();
    console.log('✓ Clicked sign in');
    
    // Wait for redirect
    await page.waitForURL((url) => !url.pathname.includes('/login'), { timeout: 10000 });
    console.log(`✓ Redirected to: ${page.url()}`);
    
    await page.waitForTimeout(3000);
    
    console.log('\n✅ Login test successful!');
  } catch (error) {
    console.error('❌ Login test failed:', error.message);
    await page.screenshot({ path: 'debug-login.png' });
  } finally {
    await browser.close();
  }
}

testLogin();
