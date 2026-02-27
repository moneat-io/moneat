import {themes as prismThemes} from 'prism-react-renderer';
import type {Config} from '@docusaurus/types';
import type * as Preset from '@docusaurus/preset-classic';

const config: Config = {
  title: 'Moneat Documentation',
  tagline: 'Sentry-compatible observability platform for error monitoring, incident management, uptime tracking, and structured logging.',
  favicon: 'img/favicon.svg',

  future: {
    v4: true,
  },

  url: 'https://moneat.io',
  baseUrl: '/docs/',

  // Inject Moneat analytics script tag only when env vars are configured.
  // Self-hosters can omit these to disable analytics on the docs site.
  ...(process.env.MONEAT_ANALYTICS_KEY && process.env.MONEAT_ANALYTICS_HOST
    ? {
        headTags: [
          {
            tagName: 'script',
            attributes: {
              defer: 'true',
              'data-domain': process.env.MONEAT_ANALYTICS_DOMAIN || 'moneat.io',
              'data-key': process.env.MONEAT_ANALYTICS_KEY,
              src: `${process.env.MONEAT_ANALYTICS_HOST}/js/m.js`,
            },
          },
        ],
      }
    : {}),

  organizationName: 'moneat',
  projectName: 'moneat',

  onBrokenLinks: 'throw',

  i18n: {
    defaultLocale: 'en',
    locales: ['en'],
  },

  plugins: [
    [
      '@cmfcmf/docusaurus-search-local',
      {
        indexDocs: true,
        indexBlog: false,
        indexPages: false,
      },
    ],
  ],

  presets: [
    [
      'classic',
      {
        docs: {
          routeBasePath: '/',
          sidebarPath: './sidebars.ts',
        },
        blog: false,
        theme: {
          customCss: './src/css/custom.css',
        },
      } satisfies Preset.Options,
    ],
  ],

  themeConfig: {
    colorMode: {
      defaultMode: 'light',
      respectPrefersColorScheme: true,
    },
    navbar: {
      title: 'Moneat',
      logo: {
        alt: 'Moneat',
        src: 'img/moneat-icon.svg',
      },
      items: [
        {
          type: 'docSidebar',
          sidebarId: 'docsSidebar',
          position: 'left',
          label: 'Docs',
        },
        {
          href: 'https://moneat.io/login',
          label: 'Sign In',
          position: 'right',
        },
        {
          href: 'https://moneat.io/signup',
          label: 'Get Started',
          position: 'right',
          className: 'navbar__cta',
        },
        {
          href: 'https://moneat.io',
          label: 'moneat.io',
          position: 'right',
        },
      ],
    },
    footer: {
      style: 'dark',
      links: [
        {
          title: 'Docs',
          items: [
            {label: 'Introduction', to: '/'},
            {label: 'Getting Started', to: '/getting-started'},
          ],
        },
        {
          title: 'Resources',
          items: [
            {label: 'moneat.io', href: 'https://moneat.io'},
            {label: 'Sign In', href: 'https://moneat.io/login'},
            {label: 'Get Started', href: 'https://moneat.io/signup'},
          ],
        },
      ],
      copyright: `Copyright © ${new Date().getFullYear()} Moneat. Sentry® and Datadog® are trademarks of their respective owners. Moneat is not affiliated with Sentry or Datadog.`,
    },
    prism: {
      theme: prismThemes.github,
      darkTheme: prismThemes.dracula,
    },
  } satisfies Preset.ThemeConfig,
};

export default config;
