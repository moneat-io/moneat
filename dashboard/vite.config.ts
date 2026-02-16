import {defineConfig} from 'vite'
import react from '@vitejs/plugin-react'
import {TanStackRouterVite} from '@tanstack/router-vite-plugin'
import path from 'path'
import type { ProxyOptions } from 'vite'

export default defineConfig({
  plugins: [react(), TanStackRouterVite()],
  resolve: {
    alias: {
      "@": path.resolve(__dirname, "./src"),
    },
  },
  server: {
    host: true,
    allowedHosts: ['moneat-frontend.bandapella.com'],
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
