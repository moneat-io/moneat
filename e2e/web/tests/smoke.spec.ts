import { test, expect } from '@playwright/test'

test.describe('Smoke Tests @smoke', () => {
  test('homepage loads successfully', async ({ page }) => {
    await page.goto('/')
    
    // Should see login or dashboard
    const hasLogin = await page.locator('text=Login').count() > 0
    const hasDashboard = await page.locator('text=Dashboard').count() > 0
    
    expect(hasLogin || hasDashboard).toBeTruthy()
  })

  test('login page is accessible', async ({ page }) => {
    await page.goto('/login')
    
    await expect(page.locator('input[type="email"]')).toBeVisible()
    await expect(page.locator('input[type="password"]')).toBeVisible()
  })
})
