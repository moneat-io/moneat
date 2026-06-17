import js from '@eslint/js'
import globals from 'globals'
import tsPlugin from '@typescript-eslint/eslint-plugin'
import tsParser from '@typescript-eslint/parser'
import reactHooks from 'eslint-plugin-react-hooks'
import {reactRefresh} from 'eslint-plugin-react-refresh'
import jsxA11y from 'eslint-plugin-jsx-a11y'
import checkFile from 'eslint-plugin-check-file'
import queryPlugin from '@tanstack/eslint-plugin-query'
import testingLibrary from 'eslint-plugin-testing-library'
import jestDom from 'eslint-plugin-jest-dom'

export default [
  { ignores: ['dist', 'public/docs/**', 'public/blog/**'] },
  js.configs.recommended,
  ...queryPlugin.configs['flat/recommended'].map((config) => ({
    ...config,
    files: ['**/*.{ts,tsx}'],
  })),
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
      'jsx-a11y': jsxA11y,
    },
    rules: {
      ...tsPlugin.configs.recommended.rules,
      ...reactHooks.configs.recommended.rules,
      'react-refresh/only-export-components': [
        'warn',
        { allowConstantExport: true },
      ],
      'react-hooks/exhaustive-deps': 'error',
      'jsx-a11y/aria-props': 'error',
      'jsx-a11y/aria-proptypes': 'error',
      'jsx-a11y/aria-role': 'error',
      'jsx-a11y/aria-unsupported-elements': 'error',
      'jsx-a11y/role-has-required-aria-props': 'error',
      'jsx-a11y/role-supports-aria-props': 'error',
      'no-restricted-syntax': [
        'error',
        {
          selector:
            "JSXOpeningElement[name.type='JSXIdentifier'][name.name=/^[a-z]/] > JSXAttribute[name.name='role'][value.value='group']",
          message:
            'Use a native grouping element such as fieldset, details, optgroup, or address instead of role="group".',
        },
      ],
      // TypeScript handles undefined variable checking; disabling avoids false positives
      // for React namespace, browser/Node types, and other TS-resolved globals.
      'no-undef': 'off',
    },
  },
  {
    files: [
      'src/**/*.{test,spec}.{ts,tsx}',
      'src/**/__tests__/**/*.{ts,tsx}',
    ],
    plugins: {
      ...testingLibrary.configs['flat/react'].plugins,
      ...jestDom.configs['flat/recommended'].plugins,
    },
    rules: {
      ...testingLibrary.configs['flat/react'].rules,
      // Legacy dashboard tests assert SVG geometry, chart layout, and generated
      // head tags through DOM nodes. Keep high-signal Testing Library checks
      // enabled now and migrate these style rules in a dedicated test cleanup.
      'testing-library/no-container': 'off',
      'testing-library/no-node-access': 'off',
      'testing-library/no-render-in-lifecycle': 'off',
      'testing-library/no-wait-for-multiple-assertions': 'off',
      'testing-library/prefer-find-by': 'off',
      'testing-library/prefer-presence-queries': 'off',
      'testing-library/prefer-screen-queries': 'off',
      'testing-library/render-result-naming-convention': 'off',
      'testing-library/no-manual-cleanup': 'off',
      // eslint-plugin-jest-dom 5.5.0 rules are all autofixable and currently
      // crash under ESLint 10 when they report. Keep the plugin installed and
      // registered, but wait to enforce rules until an ESLint 10-compatible
      // release is available.
      ...Object.fromEntries(Object.keys(jestDom.rules).map((ruleName) => [`jest-dom/${ruleName}`, 'off'])),
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
