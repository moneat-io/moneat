import {defineConfig} from 'vite'
import react from '@vitejs/plugin-react'
import {TanStackRouterVite} from '@tanstack/router-vite-plugin'
import path from 'path'
import {fileURLToPath} from 'url'
import sirv from 'sirv'
import type { ProxyOptions } from 'vite'

const __dirname = path.dirname(fileURLToPath(import.meta.url))
const docsDir = path.join(__dirname, 'public', 'docs')

export default defineConfig({
  plugins: [
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
