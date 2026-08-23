import type {Config} from '@docusaurus/types';
import type * as Preset from '@docusaurus/preset-classic';
import blueprintPrismTheme from './src/prismTheme';

// This runs in Node.js - Don't use client-side code here (browser APIs, JSX...)

const config: Config = {
  title: 'Resiliencia',
  tagline: 'Resilience patterns for Java 21',
  favicon: 'img/favicon.svg',

  // Future flags, see https://docusaurus.io/docs/api/docusaurus-config#future
  future: {
    v4: true, // Improve compatibility with the upcoming Docusaurus v4
  },

  // Set the production url of your site here
  url: 'https://tec-eli.github.io',
  // Set the /<baseUrl>/ pathname under which your site is served
  // For GitHub pages deployment, it is often '/<projectName>/'
  baseUrl: '/Resiliencia/',

  // GitHub pages deployment config.
  // If you aren't using GitHub pages, you don't need these.
  organizationName: 'tec-eli', // Usually your GitHub org/user name.
  projectName: 'Resiliencia', // Usually your repo name.

  onBrokenLinks: 'throw',

  // Even if you don't use internationalization, you can use this field to set
  // useful metadata like html lang. For example, if your site is Chinese, you
  // may want to replace "en" with "zh-Hans".
  i18n: {
    defaultLocale: 'en',
    locales: ['en'],
  },

  presets: [
    [
      'classic',
      {
        docs: {
          sidebarPath: './sidebars.ts',
          editUrl: 'https://github.com/tec-eli/resiliencia/tree/main/site/',
        },
        blog: false,
        theme: {
          customCss: './src/css/custom.css',
        },
      } satisfies Preset.Options,
    ],
  ],

  themeConfig: {
    image: 'img/hero-banner.png',
    colorMode: {
      defaultMode: 'dark',
      disableSwitch: true,
      respectPrefersColorScheme: false,
    },
    // Rendering (logo, links, GitHub) comes from a fully custom swizzled Navbar/Content component shared
    // with the landing page header — title/items below aren't displayed. Keeping items non-empty still
    // matters though: Docusaurus uses it to decide whether the mobile sidebar toggle can render at all,
    // and an empty array would suppress it on pages whose sidebar registers only after hydration.
    navbar: {
      title: 'Resiliencia',
      items: [
        {
          type: 'docSidebar',
          sidebarId: 'guidesSidebar',
          position: 'left',
          label: 'Guides',
        },
        {
          type: 'docSidebar',
          sidebarId: 'patternsSidebar',
          position: 'left',
          label: 'Patterns',
        },
        {
          href: 'https://github.com/tec-eli/resiliencia',
          label: 'GitHub',
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
            {
              label: 'Guides',
              to: '/docs/intro',
            },
            {
              label: 'Patterns',
              to: '/docs/patterns/circuit-breaker',
            },
          ],
        },
        {
          title: 'More',
          items: [
            {
              label: 'GitHub',
              href: 'https://github.com/tec-eli/resiliencia',
            },
            {
              label: 'Issues',
              href: 'https://github.com/tec-eli/resiliencia/issues',
            },
          ],
        },
      ],
      copyright: `Copyright © ${new Date().getFullYear()} Resiliencia. Built with Docusaurus.`,
    },
    prism: {
      theme: blueprintPrismTheme,
      darkTheme: blueprintPrismTheme,
      additionalLanguages: ['java'],
    },
  } satisfies Preset.ThemeConfig,
};

export default config;
