import {cp, mkdir, readdir, stat} from 'node:fs/promises'
import path from 'node:path'
import {fileURLToPath} from 'node:url'

const scriptPath = fileURLToPath(import.meta.url)
const scriptsDir = path.dirname(scriptPath)
const dashboardDir = path.resolve(scriptsDir, '..')
const repoRoot = path.resolve(dashboardDir, '..')

const enterpriseRoutesDir = path.join(repoRoot, 'enterprise', 'dashboard', 'src', 'routes')
const enterpriseOnCallComponentsDir = path.join(repoRoot, 'enterprise', 'dashboard', 'src', 'components', 'on-call')
const enterpriseSsoComponentFile = path.join(repoRoot, 'enterprise', 'dashboard', 'src', 'components', 'sso-settings.tsx')

const dashboardRoutesDir = path.join(dashboardDir, 'src', 'routes')
const dashboardOnCallComponentsDir = path.join(dashboardDir, 'src', 'components', 'on-call')
const dashboardSsoComponentFile = path.join(dashboardDir, 'src', 'components', 'sso-settings.tsx')

const isMain = process.argv[1] && path.resolve(process.argv[1]) === scriptPath

async function exists(filePath) {
  try {
    await stat(filePath)
    return true
  } catch {
    return false
  }
}

async function countFiles(dirPath) {
  const entries = await readdir(dirPath, {withFileTypes: true})
  let total = 0

  for (const entry of entries) {
    const childPath = path.join(dirPath, entry.name)
    if (entry.isDirectory()) {
      total += await countFiles(childPath)
      continue
    }

    if (entry.isFile()) {
      total += 1
    }
  }

  return total
}

async function validateEnterpriseSources() {
  const requiredPaths = [
    enterpriseRoutesDir,
    enterpriseOnCallComponentsDir,
    enterpriseSsoComponentFile,
  ]

  for (const requiredPath of requiredPaths) {
    if (!(await exists(requiredPath))) {
      throw new Error(`Enterprise source path not found: ${requiredPath}`)
    }
  }
}

export async function syncEnterpriseDashboard({dryRun = false} = {}) {
  await validateEnterpriseSources()

  const routesCount = await countFiles(enterpriseRoutesDir)
  const onCallComponentsCount = await countFiles(enterpriseOnCallComponentsDir)

  if (!dryRun) {
    await cp(enterpriseRoutesDir, dashboardRoutesDir, {recursive: true, force: true})
    await cp(enterpriseOnCallComponentsDir, dashboardOnCallComponentsDir, {recursive: true, force: true})
    await mkdir(path.dirname(dashboardSsoComponentFile), {recursive: true})
    await cp(enterpriseSsoComponentFile, dashboardSsoComponentFile, {force: true})
  }

  return {
    dryRun,
    routesCount,
    onCallComponentsCount,
    filesTouched: routesCount + onCallComponentsCount + 1,
  }
}

if (isMain) {
  const dryRun = process.argv.includes('--dry-run')

  try {
    const result = await syncEnterpriseDashboard({dryRun})
    const mode = result.dryRun ? 'Dry run' : 'Synced'
    console.log(
      `${mode} enterprise dashboard files: ${result.routesCount} route files, ` +
      `${result.onCallComponentsCount + 1} component files (${result.filesTouched} total).`
    )
  } catch (error) {
    console.error('Failed to sync enterprise dashboard files.')
    console.error(error instanceof Error ? error.message : String(error))
    process.exit(1)
  }
}
