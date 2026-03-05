import {existsSync} from 'node:fs'
import path from 'node:path'
import {spawn} from 'node:child_process'
import {fileURLToPath} from 'node:url'

const scriptPath = fileURLToPath(import.meta.url)
const scriptsDir = path.dirname(scriptPath)
const dashboardDir = path.resolve(scriptsDir, '..')
const viteBinPath = path.join(dashboardDir, 'node_modules', 'vite', 'bin', 'vite.js')

const rawArgs = process.argv.slice(2)
const hasHostArg = rawArgs.some((arg) => arg === '--host' || arg.startsWith('--host='))
if (!hasHostArg) {
  rawArgs.unshift('--host')
}

if (!existsSync(viteBinPath)) {
  console.error('Vite is not installed. Run `npm install` in the dashboard directory first.')
  process.exit(1)
}

const child = spawn(process.execPath, [viteBinPath, ...rawArgs], {
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
