import { defineConfig } from 'vite'
import react from '@vitejs/plugin-react'
import { TanStackRouterVite } from '@tanstack/router-vite-plugin'
import path from 'path'

export default defineConfig({
  plugins: [react(), TanStackRouterVite()],
  resolve: {
    alias: {
      "@": path.resolve(__dirname, "./src"),
    },
  },
  server: {
    port: 3000,
    proxy: {
      '/api': process.env.VITE_BACKEND_URL || 'http://localhost:8080',
      '/auth': process.env.VITE_BACKEND_URL || 'http://localhost:8080'
    }
  }
})
