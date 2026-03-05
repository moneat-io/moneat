const SITE_URL = 'https://moneat.io'

const FOOTER_LINKS = [
  { label: 'Features', href: `${SITE_URL}/#features` },
  { label: 'Pricing', href: `${SITE_URL}/#pricing` },
  { label: 'Docs', href: '/docs' },
  { label: 'Blog', href: '/blog' },
  { label: 'Log in', href: `${SITE_URL}/login` },
  { label: 'Sign up', href: `${SITE_URL}/signup` },
  { label: 'Terms', href: `${SITE_URL}/legal/terms` },
  { label: 'Privacy', href: `${SITE_URL}/legal/privacy` },
]

export function BlogFooter() {
  return (
    <footer className="border-t border-slate-800 bg-slate-950 py-16 px-4 sm:px-6 lg:px-8">
      <div className="max-w-6xl mx-auto">
        <div className="flex flex-col sm:flex-row items-start sm:items-center justify-between gap-8">
          <div className="flex flex-col gap-3">
            <div className="flex items-center gap-3">
              <svg className="h-7 w-7" viewBox="0 0 32 32" fill="none" xmlns="http://www.w3.org/2000/svg">
                <rect width="32" height="32" rx="8" fill="#0ea5e9" />
                <path d="M8 22V10l4 8 4-8v12M20 10v12h4" stroke="white" strokeWidth="2.5" strokeLinecap="round" strokeLinejoin="round" />
              </svg>
              <span className="text-lg font-semibold text-white">moneat</span>
            </div>
            <p className="text-sm text-slate-400 max-w-xs">
              Errors, logs, infrastructure, APM, and on-call — one platform, simple pricing.
            </p>
          </div>
          <nav className="flex flex-wrap items-center gap-4 sm:gap-8" aria-label="Footer navigation">
            {FOOTER_LINKS.map((link) => (
              <a
                key={link.label}
                href={link.href}
                className="text-sm text-slate-400 hover:text-sky-400 transition-colors"
              >
                {link.label}
              </a>
            ))}
          </nav>
        </div>
        <div className="mt-10 pt-8 border-t border-slate-800 flex flex-col items-center gap-4 text-center">
          <p className="text-xs text-slate-500">
            Operated by Adrian Elder &middot; 1235 East Blvd, Ste E PMB 2045, Charlotte, NC 28203, USA &middot;{' '}
            <a href="mailto:support@moneat.io" className="hover:text-sky-400 transition-colors">support@moneat.io</a>
          </p>
          <p className="text-xs text-slate-500">
            &copy; {new Date().getFullYear()} Moneat. All rights reserved.
          </p>
          <p className="text-xs text-slate-500">
            Compatible with Sentry&reg; SDKs &amp; Datadog&reg; Agent.
            Sentry is a registered trademark of Functional Software, Inc.
            Datadog is a registered trademark of Datadog, Inc.
            Moneat is not affiliated with or endorsed by either company.
          </p>
        </div>
      </div>
    </footer>
  )
}
