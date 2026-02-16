// Moneat - Mobile-First Error Monitoring Platform
// Copyright (C) 2026 Moneat
//
// This program is free software: you can redistribute it and/or modify
// it under the terms of the GNU Affero General Public License as published by
// the Free Software Foundation, either version 3 of the License, or
// (at your option) any later version.
//
// This program is distributed in the hope that it will be useful,
// but WITHOUT ANY WARRANTY; without even the implied warranty of
// MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the
// GNU Affero General Public License for more details.
//
// You should have received a copy of the GNU Affero General Public License
// along with this program. If not, see <https://www.gnu.org/licenses/>.

import {Helmet} from 'react-helmet-async'
import {type ReactNode} from 'react'

interface DocPageProps {
  title: string
  description: string
  children: ReactNode
}

export function DocPage({title, description, children}: DocPageProps) {
  const fullTitle = `${title} - Moneat Docs`

  return (
    <>
      <Helmet>
        <title>{fullTitle}</title>
        <meta name="description" content={description} />
        <meta property="og:title" content={fullTitle} />
        <meta property="og:description" content={description} />
        <meta property="og:type" content="article" />
        <meta property="og:site_name" content="Moneat Documentation" />
        <meta name="twitter:card" content="summary" />
        <meta name="twitter:title" content={fullTitle} />
        <meta name="twitter:description" content={description} />
      </Helmet>
      <article className="max-w-3xl">
        <header className="mb-8">
          <h1 className="text-3xl font-bold tracking-tight">{title}</h1>
          <p className="mt-2 text-lg text-muted-foreground">{description}</p>
        </header>
        <div className="space-y-8">{children}</div>
      </article>
    </>
  )
}

interface DocSectionProps {
  title: string
  id?: string
  children: ReactNode
}

export function DocSection({title, id, children}: DocSectionProps) {
  const sectionId = id || title.toLowerCase().replace(/[^a-z0-9]+/g, '-')
  return (
    <section id={sectionId} className="scroll-mt-8">
      <h2 className="text-xl font-semibold mb-4 pb-2 border-b">{title}</h2>
      <div className="space-y-4">{children}</div>
    </section>
  )
}

export function DocSubSection({title, id, children}: DocSectionProps) {
  const sectionId = id || title.toLowerCase().replace(/[^a-z0-9]+/g, '-')
  return (
    <div id={sectionId} className="scroll-mt-8">
      <h3 className="text-lg font-medium mb-3">{title}</h3>
      <div className="space-y-3">{children}</div>
    </div>
  )
}

export function DocParagraph({children}: {children: ReactNode}) {
  return <p className="text-[15px] leading-relaxed text-muted-foreground">{children}</p>
}
