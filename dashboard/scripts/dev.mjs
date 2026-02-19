import {existsSync} from 'node:fs'
import path from 'node:path'
import {spawn} from 'node:child_process'
import {fileURLToPath} from 'node:url'
import {syncEnterpriseDashboard} from './sync-enterprise.mjs'

const scriptPath = fileURLToPath(import.meta.url)
const scriptsDir = path.dirname(scriptPath)
const dashboardDir = path.resolve(scriptsDir, '..')
const viteBinPath = path.join(dashboardDir, 'node_modules', 'vite', 'bin', 'vite.js')

function isTruthy(value) {
  if (!value) return false
  const normalized = String(value).toLowerCase()
  return normalized === '1' || normalized === 'true' || normalized === 'yes'
}

const rawArgs = process.argv.slice(2)
const enterpriseFromArg = rawArgs.includes('--enterprise')
const enterpriseFromNpmConfig = isTruthy(process.env.npm_config_enterprise)
const enterpriseEnabled = enterpriseFromArg || enterpriseFromNpmConfig

const viteArgs = rawArgs.filter((arg) => arg !== '--enterprise')
const hasHostArg = viteArgs.some((arg) => arg === '--host' || arg.startsWith('--host='))
if (!hasHostArg) {
  viteArgs.unshift('--host')
}

if (enterpriseEnabled) {
  const result = await syncEnterpriseDashboard()
  console.log(
    `Enterprise mode enabled. Synced ${result.filesTouched} enterprise dashboard file(s) before startup.`
  )
}

if (!existsSync(viteBinPath)) {
  console.error('Vite is not installed. Run `npm install` in the dashboard directory first.')
  process.exit(1)
}

const child = spawn(process.execPath, [viteBinPath, ...viteArgs], {
  cwd: dashboardDir,
  env: process.env,
  stdio: 'inherit',
})

child.on('error', (error) => {
  console.error(error instanceof Error ? error.message : String(error))
  process.exit(1)
})

child.on('exit', (code, signal) => {
  if (signal) {
    process.kill(process.pid, signal)
    return
  }

  process.exit(code ?? 1)
})
