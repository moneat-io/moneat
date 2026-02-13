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
