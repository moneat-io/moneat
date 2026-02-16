// Moneat - Mobile-First Error Monitoring Platform
// Copyright (C) 2026 Moneat
//
// This program is free software: you can redistribute it and/or modify
// it under the terms of the GNU Affero General Public License as published by
// the Free Software Foundation, either version 3 of the License, or
// (at your option) any later version.
//
// This program is distributed in the hope that it will be useful,
// but WITHOUT ANY WARRANTY; without even the implied warranty of
// MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the
// GNU Affero General Public License for more details.
//
// You should have received a copy of the GNU Affero General Public License
// along with this program. If not, see <https://www.gnu.org/licenses/>.

import { test, expect } from '@playwright/test'

test.describe('Authentication @smoke', () => {
  test.beforeEach(async ({ context }) => {
    await context.clearCookies()
  })

  test('signup flow with email verification', async ({ page }) => {
    await page.goto('/signup')

    const timestamp = Date.now()
    const email = `e2e-test-${timestamp}@moneat.dev`
    const password = 'SecurePassword123!'

    // Fill signup form
    await page.fill('input[type="email"]', email)
    await page.fill('input[type="password"]', password)
    await page.fill('input[name="organizationName"]', `E2E Test Org ${timestamp}`)
    
    // Accept legal consents
    await page.check('input[name="acceptedTerms"]')
    await page.check('input[name="acceptedPrivacy"]')

    // Submit
    await page.click('button[type="submit"]')

    // Should see verification prompt
    await expect(page.locator('text=/verify.*email/i')).toBeVisible({ timeout: 10000 })
  })

  test('login and session persistence', async ({ page, context }) => {
    await page.goto('/login')

    // Use pre-seeded test user
    await page.fill('input[type="email"]', 'e2e-test@moneat.dev')
    await page.fill('input[type="password"]', 'e2e-test-password')
    await page.click('button[type="submit"]')

    // Should redirect to dashboard
    await expect(page).toHaveURL(/\/dashboard|\/projects/, { timeout: 10000 })

    // Verify token is stored
    const cookies = await context.cookies()
    const localStorage = await page.evaluate(() => JSON.stringify(window.localStorage))
    
    const hasAuthToken = cookies.some(c => c.name.includes('auth') || c.name.includes('token')) ||
                         localStorage.includes('token') || 
                         localStorage.includes('auth')
    
    expect(hasAuthToken).toBeTruthy()

    // Reload and verify still logged in
    await page.reload()
    await expect(page).toHaveURL(/\/dashboard|\/projects/)
  })

  test('unauthorized access redirects to login', async ({ page }) => {
    // Try to access protected route without auth
    await page.goto('/dashboard')

    // Should redirect to login
    await expect(page).toHaveURL(/\/login/, { timeout: 5000 })
  })

  test('logout clears session', async ({ page, context }) => {
    // Login first
    await page.goto('/login')
    await page.fill('input[type="email"]', 'e2e-test@moneat.dev')
    await page.fill('input[type="password"]', 'e2e-test-password')
    await page.click('button[type="submit"]')
    await expect(page).toHaveURL(/\/dashboard|\/projects/, { timeout: 10000 })

    // Find and click logout
    await page.click('button[aria-label="User menu"], button:has-text("Logout"), a:has-text("Logout")')
    
    // Should redirect to login
    await expect(page).toHaveURL(/\/login|\//, { timeout: 5000 })

    // Try to access protected route
    await page.goto('/dashboard')
    await expect(page).toHaveURL(/\/login/)
  })

  test('password reset flow starts correctly', async ({ page }) => {
    await page.goto('/forgot-password')

    await page.fill('input[type="email"]', 'e2e-test@moneat.dev')
    await page.click('button[type="submit"]')

    // Should show confirmation message
    await expect(page.locator('text=/check.*email|sent.*link/i')).toBeVisible({ timeout: 5000 })
  })
})
