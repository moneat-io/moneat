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
