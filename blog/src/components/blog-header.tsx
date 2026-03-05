const SITE_URL = 'https://moneat.io'

const NAV_LINKS = [
  { label: 'Features', href: `${SITE_URL}/#features` },
  { label: 'Pricing', href: `${SITE_URL}/#pricing` },
  { label: 'Docs', href: '/docs' },
  { label: 'Blog', href: '/blog' },
]

export function BlogHeader() {
  return (
    <header className="sticky top-0 z-50 w-full border-b border-slate-800 bg-slate-950/80 backdrop-blur-lg">
      <div className="flex h-16 items-center px-4 sm:px-6 lg:px-8 max-w-7xl mx-auto justify-between">
        <a href={SITE_URL} className="flex items-center gap-2" aria-label="Moneat Home">
          <svg className="h-7 w-7" viewBox="0 0 32 32" fill="none" xmlns="http://www.w3.org/2000/svg">
            <rect width="32" height="32" rx="8" fill="#0ea5e9" />
            <path d="M8 22V10l4 8 4-8v12M20 10v12h4" stroke="white" strokeWidth="2.5" strokeLinecap="round" strokeLinejoin="round" />
          </svg>
          <span className="text-lg font-semibold text-white">moneat</span>
        </a>

        <nav className="hidden md:flex items-center gap-8" aria-label="Main navigation">
          {NAV_LINKS.map((link) => (
            <a
              key={link.label}
              href={link.href}
              className="text-sm font-medium text-slate-400 hover:text-white transition-colors"
            >
              {link.label}
            </a>
          ))}
        </nav>

        <div className="hidden md:flex items-center gap-3">
          <a href={`${SITE_URL}/login`}>
            <button className="px-4 py-2 text-sm font-medium text-slate-300 hover:text-white transition-colors rounded-md">
              Log in
            </button>
          </a>
          <a href={`${SITE_URL}/signup`}>
            <button className="px-4 py-2 text-sm font-medium text-white bg-sky-500 hover:bg-sky-600 rounded-md shadow-md shadow-sky-500/25 transition-colors">
              Sign up free
            </button>
          </a>
        </div>
      </div>
    </header>
  )
}
