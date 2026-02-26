import {existsSync} from 'node:fs'
import path from 'node:path'
import {spawn, spawnSync} from 'node:child_process'
import {fileURLToPath} from 'node:url'

const scriptPath = fileURLToPath(import.meta.url)
const scriptsDir = path.dirname(scriptPath)
const dashboardDir = path.resolve(scriptsDir, '..')
const rootDir = path.resolve(dashboardDir, '..')
const docsDir = path.join(rootDir, 'docs')
const docsOutDir = path.join(dashboardDir, 'public', 'docs')
const viteBinPath = path.join(dashboardDir, 'node_modules', 'vite', 'bin', 'vite.js')
const docusaurusBinPath = path.join(docsDir, 'node_modules', '@docusaurus', 'core', 'bin', 'docusaurus.mjs')

const rawArgs = process.argv.slice(2)
const hasHostArg = rawArgs.some((arg) => arg === '--host' || arg.startsWith('--host='))
if (!hasHostArg) {
  rawArgs.unshift('--host')
}

if (!existsSync(viteBinPath)) {
  console.error('Vite is not installed. Run `npm install` in the dashboard directory first.')
  process.exit(1)
}

if (!existsSync(docusaurusBinPath)) {
  console.error('Docusaurus is not installed. Run `npm install` in the docs directory first.')
  process.exit(1)
}

console.log('Building docs...')
const docsBuild = spawnSync(
  process.execPath,
  [docusaurusBinPath, 'build', '--out-dir', docsOutDir],
  {cwd: docsDir, env: process.env, stdio: 'inherit'},
)
if (docsBuild.status !== 0) {
  console.error('Docs build failed.')
  process.exit(docsBuild.status ?? 1)
}
console.log('Docs built successfully.')

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
