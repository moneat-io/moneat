// Moneat - observability platform
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
