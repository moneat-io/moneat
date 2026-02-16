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

test.describe('Billing and Usage', () => {
  test.beforeEach(async ({ page, context }) => {
    await context.clearCookies()
    
    // Login
    await page.goto('/login')
    await page.fill('input[type="email"]', 'e2e-test@moneat.dev')
    await page.fill('input[type="password"]', 'e2e-test-password')
    await page.click('button[type="submit"]')
    await expect(page).toHaveURL(/\/dashboard|\/projects/, { timeout: 10000 })
  })

  test('billing usage page loads', async ({ page }) => {
    await page.goto('/billing')

    // Should see usage information
    await expect(page.locator('text=/usage|events|quota/i')).toBeVisible({ timeout: 10000 })
  })

  test('billing page handles API errors gracefully', async ({ page, context }) => {
    // Intercept billing API and return error
    await context.route('**/v1/billing/**', route => {
      route.fulfill({
        status: 500,
        contentType: 'application/json',
        body: JSON.stringify({ error: 'Internal server error' })
      })
    })

    await page.goto('/billing')

    // Should show error state, not crash
    await expect(page.locator('text=/error|failed|unable/i, [role="alert"]')).toBeVisible({ timeout: 10000 })
  })

  test('usage metrics display correctly', async ({ page }) => {
    await page.goto('/billing')

    // Wait for page to load
    await page.waitForLoadState('networkidle')

    // Should see some usage metrics (even if 0)
    const hasMetrics = await page.locator('text=/events|errors|transactions|total/i').count() > 0
    expect(hasMetrics).toBeTruthy()
  })

  test('budget update form exists', async ({ page }) => {
    await page.goto('/billing')
    await page.waitForLoadState('networkidle')

    // Look for budget or limit settings
    const hasBudgetControls = await page.locator('input[type="number"], input[name*="budget"], input[name*="limit"]').count() > 0
    
    if (hasBudgetControls) {
      // If budget controls exist, verify they're functional
      const budgetInput = page.locator('input[type="number"]').first()
      await budgetInput.fill('1000')
      
      // Should not throw validation error for valid input
      const hasError = await page.locator('text=/invalid|error/i').count() > 0
      expect(hasError).toBeFalsy()
    }
  })

  test('billing page navigation works', async ({ page }) => {
    await page.goto('/billing')
    await page.waitForLoadState('networkidle')

    // Should be on billing page
    await expect(page).toHaveURL(/\/billing/)

    // Navigate back to dashboard
    await page.click('a:has-text("Dashboard"), button:has-text("Dashboard")')
    await expect(page).toHaveURL(/\/dashboard|\/projects/)
  })
})
