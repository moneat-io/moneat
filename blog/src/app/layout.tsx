import type { Metadata } from 'next'
import { Inter } from 'next/font/google'
import { BlogHeader } from '@/components/blog-header'
import { BlogFooter } from '@/components/blog-footer'
import './globals.css'

const inter = Inter({ subsets: ['latin'] })

export const metadata: Metadata = {
  metadataBase: new URL('https://moneat.io'),
  title: {
    default: 'Moneat Blog — Observability, Error Monitoring & Engineering',
    template: '%s | Moneat Blog',
  },
  description:
    'Engineering deep-dives, observability best practices, and product updates from the Moneat team.',
  openGraph: {
    type: 'website',
    locale: 'en_US',
    url: 'https://moneat.io/blog',
    siteName: 'Moneat Blog',
    title: 'Moneat Blog',
    description:
      'Engineering deep-dives, observability best practices, and product updates from the Moneat team.',
  },
  twitter: {
    card: 'summary_large_image',
    title: 'Moneat Blog',
    description:
      'Engineering deep-dives, observability best practices, and product updates from the Moneat team.',
  },
  alternates: {
    canonical: 'https://moneat.io/blog',
    types: {
      'application/rss+xml': 'https://moneat.io/blog/feed.xml',
    },
  },
  robots: {
    index: true,
    follow: true,
  },
}

export default function RootLayout({
  children,
}: {
  children: React.ReactNode
}) {
  return (
    <html lang="en" className="dark">
      <body className={`${inter.className} bg-slate-950 text-slate-50 antialiased`}>
        <BlogHeader />
        <main className="min-h-screen">{children}</main>
        <BlogFooter />
      </body>
    </html>
  )
}
