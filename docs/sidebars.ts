import type {SidebarsConfig} from '@docusaurus/plugin-content-docs';

const sidebars: SidebarsConfig = {
  docsSidebar: [
    {
      type: 'category',
      label: 'Overview',
      collapsed: false,
      items: [
        'intro',
        'getting-started',
      ],
    },
    {
      type: 'category',
      label: 'Core Features',
      collapsed: false,
      items: [
        'error-monitoring',
        'issue-tracking',
        'logging',
        'releases',
        'ai-observability',
      ],
    },
    {
      type: 'category',
      label: 'Reliability',
      collapsed: false,
      items: [
        'on-call',
        'uptime-monitoring',
        'status-pages',
      ],
    },
    {
      type: 'category',
      label: 'Configuration',
      collapsed: false,
      items: [
        'sdk-setup',
        'integrations',
        'sso-authentication',
        'api-tokens',
      ],
    },
    {
      type: 'category',
      label: 'Account',
      collapsed: false,
      items: [
        'billing',
      ],
    },
  ],
};

export default sidebars;
