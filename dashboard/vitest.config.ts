import { defineConfig } from 'vitest/config'
import react from '@vitejs/plugin-react'
import path from 'path'

// Coverage threshold helper for staged rollout
function getCoverageThreshold(metric: 'lines' | 'functions' | 'branches' | 'statements'): number {
  const phase = process.env.COVERAGE_GATE_PHASE || 'reporting-only'
  
  const thresholds = {
    'reporting-only': { lines: 0, functions: 0, branches: 0, statements: 0 },
    'soft': { lines: 45, functions: 45, branches: 40, statements: 45 },
    'hard': { lines: 55, functions: 55, branches: 50, statements: 55 },
  }
  
  return thresholds[phase as keyof typeof thresholds]?.[metric] ?? 0
}

export default defineConfig({
  plugins: [react()],
  test: {
    globals: true,
    environment: 'jsdom',
    setupFiles: ['./src/test/setup.ts'],
    env: {
      VITE_BACKEND_URL: 'http://localhost:8080',
    },
    coverage: {
      provider: 'v8',
      reporter: ['text', 'json', 'html', 'lcov'],
      exclude: [
        'node_modules/',
        'src/test/',
        '**/*.d.ts',
        '**/*.config.*',
        '**/mockData/**',
        'src/routeTree.gen.ts',
      ],
      thresholds: {
        // Staged rollout: Week 3=0%, Week 4=45%, Week 6=55%
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
