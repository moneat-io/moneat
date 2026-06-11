import {readFileSync} from 'node:fs'
import {dirname, resolve} from 'node:path'
import {fileURLToPath} from 'node:url'
import {describe, expect, it} from 'vitest'

const routeTreePath = resolve(dirname(fileURLToPath(import.meta.url)), '../../routeTree.gen.ts')

describe('retired project setup route', () => {
  it('does not expose the former project setup page', () => {
    const routeTree = readFileSync(routeTreePath, 'utf8')

    expect(routeTree).not.toContain("routes/projects.$projectId'")
    expect(routeTree).not.toContain("'/projects/$projectId':")
    expect(routeTree).not.toContain("'/projects/$projectId': typeof")
  })

  it('uses the setup route instead of the old configuration route', () => {
    const routeTree = readFileSync(routeTreePath, 'utf8')

    expect(routeTree).toContain("routes/setup'")
    expect(routeTree).toContain("'/setup':")
    expect(routeTree).not.toContain("routes/configuration'")
    expect(routeTree).not.toContain("'/configuration':")
  })
})
