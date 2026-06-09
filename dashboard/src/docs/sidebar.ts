export interface SidebarCategory {
  label: string
  collapsed?: boolean
  link?: string
  items: SidebarItem[]
}

export type SidebarItem = string | SidebarCategory

export const docsSidebar: SidebarCategory[] = [
  {
    label: 'Overview',
    collapsed: false,
    items: ['intro', 'getting-started'],
  },
  {
    label: 'Core Features',
    collapsed: false,
    items: [
      'error-monitoring',
      'issue-tracking',
      'session-replay',
      'performance-monitoring',
      'logging',
      'releases',
      'ai-observability',
      'custom-dashboards',
      'feature-flags',
      'user-feedback',
      'infrastructure-monitoring',
      {
        label: 'Product Analytics',
        link: 'product-analytics',
        items: [
          'product-analytics/setup',
          'product-analytics/dashboard',
          'product-analytics/custom-events',
          'product-analytics/funnels',
          'product-analytics/filtering',
          'product-analytics/api-reference',
          'product-analytics/privacy',
        ],
      },
    ],
  },
  {
    label: 'Reliability',
    collapsed: false,
    items: ['on-call', 'workflows', 'uptime-monitoring', 'status-pages'],
  },
  {
    label: 'Configuration',
    collapsed: false,
    items: ['integrations', 'sso-authentication', 'api-tokens'],
  },
  {
    label: 'Source Setup',
    collapsed: false,
    items: [
      'opentelemetry',
      'sdk-setup',
      {
        label: 'Datadog Agent Compatibility',
        link: 'datadog-agent',
        items: [
          'datadog-agent/agent-setup',
          'datadog-agent/continuous-profiling',
          'datadog-agent/log-collection',
          'datadog-agent/dashboard-import',
          'datadog-agent/kubernetes',
          'datadog-agent/database-monitoring',
          'datadog-agent/debugger',
          'datadog-agent/network-devices',
          'datadog-agent/sbom',
          'datadog-agent/synthetics',
        ],
      },
    ],
  },
  {
    label: 'MCP Server',
    collapsed: false,
    items: [
      'mcp-server/overview',
      'mcp-server/setup',
      'mcp-server/tools-reference',
      'mcp-server/examples',
    ],
  },
  {
    label: 'Migration',
    collapsed: false,
    items: ['migrate-from-highlight'],
  },
  {
    label: 'Self-Hosting',
    collapsed: false,
    items: ['self-hosting'],
  },
  {
    label: 'Account',
    collapsed: false,
    items: ['billing'],
  },
]
