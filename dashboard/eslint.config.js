import js from '@eslint/js'
import globals from 'globals'
import tsPlugin from '@typescript-eslint/eslint-plugin'
import tsParser from '@typescript-eslint/parser'
import reactHooks from 'eslint-plugin-react-hooks'
import {reactRefresh} from 'eslint-plugin-react-refresh'
import checkFile from 'eslint-plugin-check-file'

export default [
  { ignores: ['dist', 'public/docs/**', 'public/blog/**'] },
  js.configs.recommended,
  // Node.js scripts and config files
  {
    files: ['scripts/**/*.mjs', 'tailwind.config.js', 'tailwind.config.ts', 'postcss.config.*'],
    languageOptions: {
      globals: globals.node,
    },
  },
  {
    files: ['**/*.{ts,tsx}'],
    languageOptions: {
      ecmaVersion: 2020,
      parser: tsParser,
      globals: globals.browser,
    },
    plugins: {
      '@typescript-eslint': tsPlugin,
      'react-hooks': reactHooks,
      'react-refresh': reactRefresh.plugin,
    },
    rules: {
      ...tsPlugin.configs.recommended.rules,
      ...reactHooks.configs.recommended.rules,
      'react-refresh/only-export-components': [
        'warn',
        { allowConstantExport: true },
      ],
      'react-hooks/exhaustive-deps': 'off',
      // TypeScript handles undefined variable checking; disabling avoids false positives
      // for React namespace, browser/Node types, and other TS-resolved globals.
      'no-undef': 'off',
    },
  },
  {
    files: ['src/routes/**/*.tsx'],
    rules: {
      // TanStack file-route modules intentionally export Route and colocate route components/helpers.
      'react-refresh/only-export-components': 'off',
    },
  },
  // Enforce PascalCase for component .tsx files (excluding ui/ which follows shadcn convention)
  {
    files: ['src/components/**/*.tsx'],
    ignores: ['src/components/ui/**', 'src/components/**/__tests__/**'],
    plugins: { 'check-file': checkFile },
    rules: {
      'check-file/filename-naming-convention': [
        'error',
        { '**/*.tsx': 'PASCAL_CASE' },
      ],
    },
  },
  // Enforce camelCase for hook files (useXxx convention)
  {
    files: ['src/hooks/**/*.{ts,tsx}'],
    ignores: ['src/hooks/**/__tests__/**'],
    plugins: { 'check-file': checkFile },
    rules: {
      'check-file/filename-naming-convention': [
        'error',
        { '**/*.{ts,tsx}': 'CAMEL_CASE' },
      ],
    },
  },
  // Enforce PascalCase for context files
  {
    files: ['src/contexts/**/*.{ts,tsx}'],
    ignores: ['src/contexts/**/__tests__/**'],
    plugins: { 'check-file': checkFile },
    rules: {
      'check-file/filename-naming-convention': [
        'error',
        { '**/*.{ts,tsx}': 'PASCAL_CASE' },
      ],
    },
  },
]
