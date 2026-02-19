import { existsSync } from 'node:fs'
import path from 'node:path'
import { fileURLToPath } from 'node:url'
import { syncEnterpriseDashboard } from './sync-enterprise.mjs'

const scriptPath = fileURLToPath(import.meta.url)
const scriptsDir = path.dirname(scriptPath)
const dashboardDir = path.resolve(scriptsDir, '..')
const repoRoot = path.resolve(dashboardDir, '..')

const defaultEnterprisePath = path.resolve(repoRoot, '..', 'moneat-enterprise')
const enterpriseRoot = process.env.ENTERPRISE_PATH
  ? path.resolve(process.env.ENTERPRISE_PATH)
  : defaultEnterprisePath

const requiredPaths = [
  path.join(enterpriseRoot, 'dashboard', 'src', 'routes'),
  path.join(enterpriseRoot, 'dashboard', 'src', 'components', 'analytics'),
  path.join(enterpriseRoot, 'dashboard', 'src', 'components', 'on-call'),
  path.join(enterpriseRoot, 'dashboard', 'src', 'components', 'sso-settings.tsx'),
]

const missing = requiredPaths.filter((requiredPath) => !existsSync(requiredPath))

if (missing.length > 0) {
  console.log(
    `[sync-enterprise-if-present] Skipping enterprise sync; sources not found at ${enterpriseRoot}.`,
  )
  process.exit(0)
}

try {
  const result = await syncEnterpriseDashboard()
  console.log(
    `[sync-enterprise-if-present] Synced ${result.filesTouched} enterprise dashboard file(s).`,
  )
} catch (error) {
  console.error('[sync-enterprise-if-present] Failed to sync enterprise dashboard files.')
  console.error(error instanceof Error ? error.message : String(error))
  process.exit(1)
}
