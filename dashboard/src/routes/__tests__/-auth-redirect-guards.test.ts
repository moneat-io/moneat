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

import {readdirSync, readFileSync, statSync} from 'node:fs'
import {dirname, join, relative, resolve} from 'node:path'
import {fileURLToPath} from 'node:url'
import {describe, expect, it} from 'vitest'

const routesDir = resolve(dirname(fileURLToPath(import.meta.url)), '..')
const loginRedirectPattern = /throw\s+redirect\(\{\s*to:\s*['"]\/login['"]/g
const allowedLoginRedirectFiles = new Set(['__root.tsx', 'demo.tsx'])

function routeFiles(dir: string): string[] {
  return readdirSync(dir).flatMap((entry) => {
    const path = join(dir, entry)
    const stats = statSync(path)
    if (stats.isDirectory()) {
      return entry === '__tests__' ? [] : routeFiles(path)
    }
    return /\.(tsx?|jsx?)$/.test(entry) ? [path] : []
  })
}

describe('route auth redirects', () => {
  it('keeps login redirects centralized in the root route guard', () => {
    const offenders = routeFiles(routesDir)
      .filter((file) => file.endsWith('.tsx') || file.endsWith('.ts'))
      .flatMap((file) => {
        const relativeFile = relative(routesDir, file)
        if (allowedLoginRedirectFiles.has(relativeFile)) return []

        const source = readFileSync(file, 'utf8')
        return Array.from(source.matchAll(loginRedirectPattern)).map(() => relativeFile)
      })

    expect(offenders).toEqual([])
  })
})
