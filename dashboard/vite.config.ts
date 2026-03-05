import {defineConfig} from 'vite'
import mdx from '@mdx-js/rollup'
import remarkGfm from 'remark-gfm'
import remarkFrontmatter from 'remark-frontmatter'
import remarkMdxFrontmatter from 'remark-mdx-frontmatter'
import type {Root} from 'mdast'
import react from '@vitejs/plugin-react-swc'

// Remark plugin: counts words in the document and injects readingTime into the YAML
// frontmatter AST node before remark-mdx-frontmatter exports it.
function remarkReadingTime() {
  return (tree: Root) => {
    let wordCount = 0
    function walk(node: {type: string; value?: string; children?: unknown[]}) {
      if (node.type === 'text' && node.value) {
        wordCount += node.value.trim().split(/\s+/).filter(Boolean).length
      }
      node.children?.forEach((child) => { walk(child as typeof node) })
    }
    walk(tree as unknown as {type: string; children: unknown[]})
    const minutes = Math.ceil(wordCount / 200) || 1
    // Mutate the YAML node so remark-mdx-frontmatter picks up readingTime when it runs next.
    const rootChildren = (tree as unknown as {children: Array<{type: string; value?: string}>}).children
    const yamlNode = rootChildren.find((n) => n.type === 'yaml')
    if (yamlNode) {
      yamlNode.value = (yamlNode.value ?? '') + `\nreadingTime: "${minutes} min read"`
    }
  }
}
import tailwindcss from '@tailwindcss/vite'
import {TanStackRouterVite} from '@tanstack/router-vite-plugin'
import path from 'path'
import {fileURLToPath} from 'url'
import sirv from 'sirv'
import type { ProxyOptions } from 'vite'

const __dirname = path.dirname(fileURLToPath(import.meta.url))
const docsDir = path.join(__dirname, 'public', 'docs')

export default defineConfig({
  plugins: [
    {
      enforce: 'pre',
      ...mdx({
        remarkPlugins: [
          remarkGfm,
          remarkFrontmatter,
          remarkReadingTime,
          [remarkMdxFrontmatter, {name: 'frontmatter'}],
        ],
      }),
    },
    tailwindcss(),
    react(),
    TanStackRouterVite(),
    {
      name: 'serve-docs',
      configureServer(server) {
        const serveDocs = sirv(docsDir, { dev: true })
        // Run before SPA fallback so /docs/* serves static files, not dashboard index.html
        server.middlewares.stack.unshift({
          route: '',
          handle: (req: import('http').IncomingMessage, res: import('http').ServerResponse, next: () => void) => {
            if (req.url === '/docs' || req.url?.startsWith('/docs?')) {
              const q = req.url.includes('?') ? req.url.slice(req.url.indexOf('?')) : ''
              res.writeHead(301, { Location: `/docs/${q}` })
              res.end()
              return
            }
            if (!req.url?.startsWith('/docs/')) return next()
            const originalUrl = req.url
            req.url = req.url.slice(5) || '/' // /docs/foo -> /foo
            serveDocs(req, res, () => {
              req.url = originalUrl
              next()
            })
          },
        })
      },
    },
  ],
  resolve: {
    alias: {
      "@": path.resolve(__dirname, "./src"),
    },
  },
  optimizeDeps: {
    include: [
      'recharts',
      'react-grid-layout',
      'react-markdown',
      'react-syntax-highlighter',
      'rrweb',
      'rrweb-player',
      'date-fns',
      'lucide-react',
    ],
  },
  server: {
    host: true,
    allowedHosts: true,
    port: 3000,
    proxy: {
      '/v1': {
        target: 'http://localhost:8080',
        changeOrigin: true,
        secure: false,
        cookieDomainRewrite: 'localhost',
        configure: (proxy) => {
          proxy.on('proxyRes', (proxyRes, _req, res) => {
            // Ensure cookies are passed through from backend to frontend
            const cookies = proxyRes.headers['set-cookie']
            if (cookies) {
              res.setHeader('set-cookie', cookies)
            }
          })
        }
      } as ProxyOptions,
      '/auth': {
        target: 'http://localhost:8080',
        changeOrigin: true,
        secure: false,
        cookieDomainRewrite: 'localhost',
        configure: (proxy) => {
          proxy.on('proxyRes', (proxyRes, _req, res) => {
            // Ensure cookies are passed through from backend to frontend
            const cookies = proxyRes.headers['set-cookie']
            if (cookies) {
              res.setHeader('set-cookie', cookies)
            }
          })
        }
      } as ProxyOptions,
      '/features': {
        target: 'http://localhost:8080',
        changeOrigin: true,
        secure: false,
      } as ProxyOptions,
      '/api': {
        target: 'http://localhost:8080',
        changeOrigin: true,
        secure: false,
        cookieDomainRewrite: 'localhost',
        configure: (proxy) => {
          proxy.on('proxyRes', (proxyRes, _req, res) => {
            // Ensure cookies are passed through from backend to frontend
            const cookies = proxyRes.headers['set-cookie']
            if (cookies) {
              res.setHeader('set-cookie', cookies)
            }
          })
        }
      } as ProxyOptions
    }
  }
})
