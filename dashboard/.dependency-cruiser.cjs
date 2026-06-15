/** @type {import('dependency-cruiser').IConfiguration} */
module.exports = {
  forbidden: [
    {
      name: 'no-circular',
      severity: 'error',
      comment: 'Circular imports make route and component changes hard to reason about.',
      from: {
        pathNot:
          '^src/(lib/api|components/StatusPagePreview|hooks/useTimezone|contexts/CommandPaletteContext|lib/ai-chat-history|components/logs/LogDetailPanel|components/logs/tabs/Log(Overview|Attributes)Tab)',
      },
      to: {
        circular: true,
      },
    },
    {
      name: 'no-routes-back-imports',
      severity: 'error',
      comment: 'Routes are composition entrypoints; shared code should not import route modules.',
      from: {
        path:
          '^src/(?!routes/)(?!routeTree[.]gen[.]ts$)(?!.*__tests__/)(?!components/projects/(ServiceSetupForm|ServiceSettingsCard)[.]tsx$)',
      },
      to: {
        path: '^src/routes/',
      },
    },
  ],
  options: {
    doNotFollow: {
      path: 'node_modules',
    },
    enhancedResolveOptions: {
      exportsFields: ['exports'],
      conditionNames: ['import', 'module', 'browser', 'default'],
    },
    tsPreCompilationDeps: true,
    tsConfig: {
      fileName: 'tsconfig.json',
    },
  },
}
