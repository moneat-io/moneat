// Post-build prerender step (runs after `vite build`).
//
// Bundles src/prerender/render.tsx for Node via a Vite SSR build (no headless browser, so it
// works in the node:24-alpine production image), then injects static SEO <head> markup — and,
// for blog posts, prerendered article HTML — into per-route copies of dist/index.html. Also
// writes dist/sitemap.xml. nginx (try_files $uri $uri/ /index.html) serves the per-route files
// to crawlers/scrapers while the SPA still hydrates normally for users.
import {build} from 'vite'
import {fileURLToPath, pathToFileURL} from 'node:url'
import {dirname, join, resolve} from 'node:path'
import {mkdir, readFile, rm, writeFile} from 'node:fs/promises'

const scriptDir = dirname(fileURLToPath(import.meta.url))
const root = resolve(scriptDir, '..')
const distDir = join(root, 'dist')
const ssrDir = join(root, 'dist-ssr')
const ssrEntryOut = join(ssrDir, 'prerender-render.mjs')

function injectHead(template, headTags) {
  if (!template.includes('</head>')) {
    throw new Error('prerender: dist/index.html is missing a </head> tag')
  }
  // Drop the static placeholder <title> so each route gets exactly one (its own).
  const withoutTitle = template.replace(/[ \t]*<title>[\s\S]*?<\/title>\n?/, '')
  return withoutTitle.replace('</head>', `    ${headTags}\n  </head>`)
}

function injectBody(html, bodyHtml) {
  const marker = '<div id="root"></div>'
  if (!html.includes(marker)) {
    throw new Error('prerender: dist/index.html is missing an empty <div id="root"></div>')
  }
  return html.replace(marker, `<div id="root">${bodyHtml}</div>`)
}

function outputPath(routePath) {
  if (routePath === '/') return join(distDir, 'index.html')
  return join(distDir, routePath.replace(/^\/+/, ''), 'index.html')
}

async function main() {
  await build({
    root,
    logLevel: 'warn',
    build: {
      ssr: 'src/prerender/render.tsx',
      outDir: 'dist-ssr',
      emptyOutDir: true,
      rollupOptions: {output: {entryFileNames: 'prerender-render.mjs'}},
    },
  })

  const {getPrerenderRoutes, getSitemapXml} = await import(pathToFileURL(ssrEntryOut).href)

  const template = await readFile(join(distDir, 'index.html'), 'utf8')
  const routes = getPrerenderRoutes()

  for (const route of routes) {
    let html = injectHead(template, route.head)
    if (route.bodyHtml) html = injectBody(html, route.bodyHtml)
    const outPath = outputPath(route.path)
    await mkdir(dirname(outPath), {recursive: true})
    await writeFile(outPath, html, 'utf8')
  }

  await writeFile(join(distDir, 'sitemap.xml'), getSitemapXml(), 'utf8')
  await rm(ssrDir, {recursive: true, force: true})

  console.log(`prerender: wrote ${routes.length} static route(s) + sitemap.xml`)
}

main().catch((error) => {
  console.error('prerender failed:', error)
  process.exitCode = 1
})
