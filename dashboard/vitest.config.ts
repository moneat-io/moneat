import { defineConfig } from 'vitest/config'
import react from '@vitejs/plugin-react'
import path from 'path'

// Coverage threshold helper for staged rollout
function getCoverageThreshold(metric: 'lines' | 'functions' | 'branches' | 'statements'): number {
  const phase = process.env.COVERAGE_GATE_PHASE || 'reporting-only'
  
  const thresholds = {
    'reporting-only': { lines: 0, functions: 0, branches: 0, statements: 0 },
    'soft': { lines: 45, functions: 45, branches: 40, statements: 45 },
    'hard': { lines: 80, functions: 60, branches: 60, statements: 60 },
  }
  
  return thresholds[phase as keyof typeof thresholds]?.[metric] ?? 0
}

export default defineConfig({
  plugins: [react()],
  test: {
    globals: true,
    environment: 'jsdom',
    testTimeout: 30_000,
    setupFiles: ['./src/test/setup.ts'],
    env: {
      VITE_BACKEND_URL: 'http://localhost:8080',
    },
    coverage: {
      provider: 'v8',
      reporter: ['text', 'json', 'json-summary', 'html', 'lcov'],
      exclude: [
        'node_modules/',
        'src/test/',
        '**/*.d.ts',
        '**/*.config.*',
        '**/mockData/**',
        'src/routeTree.gen.ts',
        // App shell/provider wiring is covered through integration paths, not unit coverage.
        'src/routes/__root.tsx',
        'src/contexts/CommandPaletteProvider.tsx',
        // Build-time prerender entry: imports MDX so it only runs through Vite (not vitest),
        // and is validated by the production build itself. Its pure logic lives in src/lib/seo.
        'src/prerender/**',
      ],
      thresholds: {
        // Staged rollout: reporting-only=0%, soft=45%, hard=80% lines / 60% supporting metrics
        // Controlled via COVERAGE_GATE_PHASE env var
        lines: getCoverageThreshold('lines'),
        functions: getCoverageThreshold('functions'),
        branches: getCoverageThreshold('branches'),
        statements: getCoverageThreshold('statements'),
      },
    },
  },
  resolve: {
    alias: {
      '@': path.resolve(__dirname, './src'),
    },
  },
})
