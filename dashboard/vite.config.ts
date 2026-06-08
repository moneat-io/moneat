import {defineConfig} from 'vite'
import mdx from '@mdx-js/rollup'
import remarkGfm from 'remark-gfm'
import remarkFrontmatter from 'remark-frontmatter'
import remarkMdxFrontmatter from 'remark-mdx-frontmatter'
import remarkDirective from 'remark-directive'
import remarkAdmonitions from './src/docs/remark-admonitions'
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
import type { ProxyOptions } from 'vite'

const __dirname = path.dirname(fileURLToPath(import.meta.url))
const DEV_PROXY_TARGET = 'http://localhost:8080'
const DEV_PROXY_ALLOWED_ORIGIN = 'http://localhost:3000'

function createBackendProxyOptions({rewriteOrigin = true, passCookies = true} = {}): ProxyOptions {
  return {
    target: DEV_PROXY_TARGET,
    changeOrigin: true,
    secure: false,
    cookieDomainRewrite: 'localhost',
    configure: (proxy) => {
      if (rewriteOrigin) {
        proxy.on('proxyReq', (proxyReq) => {
          proxyReq.setHeader('Origin', DEV_PROXY_ALLOWED_ORIGIN)
        })
      }

      if (passCookies) {
        proxy.on('proxyRes', (proxyRes, _req, res) => {
          // Ensure cookies are passed through from backend to frontend
          const cookies = proxyRes.headers['set-cookie']
          if (cookies) {
            res.setHeader('set-cookie', cookies)
          }
        })
      }
    },
  }
}

export default defineConfig({
  plugins: [
    {
      enforce: 'pre',
      ...mdx({
        remarkPlugins: [
          remarkGfm,
          remarkFrontmatter,
          remarkDirective,
          remarkAdmonitions,
          remarkReadingTime,
          [remarkMdxFrontmatter, {name: 'frontmatter'}],
        ],
      }),
    },
    tailwindcss(),
    react(),
    TanStackRouterVite(),
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
      '/v1': createBackendProxyOptions(),
      '/auth': createBackendProxyOptions(),
      '/features': createBackendProxyOptions({passCookies: false}),
      '/api': createBackendProxyOptions(),
    }
  }
})
