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

test.describe('Project Management', () => {
  test.beforeEach(async ({ page, context }) => {
    await context.clearCookies()
    
    // Login
    await page.goto('/login')
    await page.fill('input[type="email"]', 'e2e-test@moneat.dev')
    await page.fill('input[type="password"]', 'e2e-test-password')
    await page.click('button[type="submit"]')
    await expect(page).toHaveURL(/\/dashboard|\/projects/, { timeout: 10000 })
  })

  test('create new project @smoke', async ({ page }) => {
    await page.goto('/projects')

    // Click create project button
    const createButton = page.locator('button:has-text("Create Project"), button:has-text("New Project"), a:has-text("Create Project")')
    await createButton.first().click()

    const timestamp = Date.now()
    const projectName = `E2E Test Project ${timestamp}`

    // Fill project form
    await page.fill('input[name="name"]', projectName)
    await page.selectOption('select[name="platform"]', 'javascript')
    await page.click('button[type="submit"]')

    // Should see project in list or redirect to project page
    await expect(page.locator(`text="${projectName}"`)).toBeVisible({ timeout: 10000 })
  })

  test('open existing project', async ({ page }) => {
    await page.goto('/projects')

    // Should see "Android E2E App" from seed data
    const androidProject = page.locator('text="Android E2E App"')
    await expect(androidProject).toBeVisible({ timeout: 5000 })

    // Click to open
    await androidProject.click()

    // Should navigate to project issues page
    await expect(page).toHaveURL(/\/projects\/[^/]+/, { timeout: 5000 })
  })

  test('view project settings', async ({ page }) => {
    await page.goto('/projects')
    
    // Open first project
    const firstProject = page.locator('[data-testid="project-card"], a[href*="/projects/"]').first()
    await firstProject.click()
    await page.waitForLoadState('networkidle')

    // Navigate to settings
    const settingsLink = page.locator('a:has-text("Settings"), button:has-text("Settings")')
    if (await settingsLink.count() > 0) {
      await settingsLink.first().click()
      await expect(page).toHaveURL(/\/settings|\/project/, { timeout: 5000 })
    }
  })
})
