import {createFileRoute, redirect} from '@tanstack/react-router'
import {api} from '@/lib/api'
import {getSetupDocs} from '@/lib/setup-docs'
import {getPlatformInfo} from '@/routes/projects'
import {Card, CardContent, CardHeader, CardTitle} from '@/components/ui/card'
import {Button} from '@/components/ui/button'
import {Badge} from '@/components/ui/badge'
import {Check, Copy} from 'lucide-react'
import {useEffect, useState} from 'react'
import {Prism as SyntaxHighlighter} from 'react-syntax-highlighter'
import {oneDark, oneLight} from 'react-syntax-highlighter/dist/esm/styles/prism'

export const Route = createFileRoute('/projects/$projectId')({
  beforeLoad: async () => {
    if (!api.isAuthenticated()) {
      throw redirect({ to: '/login' })
    }
  },
  loader: async ({ params }) => {
    const project = await api.getProject(Number(params.projectId))
    return { project }
  },
  component: SetupPage,
})

// Prism language alias (e.g. xml -> markup)
const LANGUAGE_ALIASES: Record<string, string> = {
  xml: 'markup',
  text: 'plaintext',
}

function CodeBlock({
  code,
  language,
  onCopy,
}: {
  code: string
  language?: string
  onCopy?: () => void
}) {
  const [copied, setCopied] = useState(false)
  const [isDark, setIsDark] = useState(true)

  useEffect(() => {
    const root = document.documentElement
    setIsDark(root.classList.contains('dark'))
    const obs = new MutationObserver(() => setIsDark(root.classList.contains('dark')))
    obs.observe(root, { attributes: true, attributeFilter: ['class'] })
    return () => obs.disconnect()
  }, [])

  const handleCopy = async () => {
    await navigator.clipboard.writeText(code)
    setCopied(true)
    setTimeout(() => setCopied(false), 2000)
    onCopy?.()
  }

  const prismLang = language ? (LANGUAGE_ALIASES[language] ?? language) : 'plaintext'
  const style = isDark ? oneDark : oneLight

  return (
    <div className="relative group rounded-xl overflow-hidden border border-border shadow-sm">
      <div className="absolute top-2 right-2 z-10">
        <Button
          variant="secondary"
          size="icon"
          className="h-8 w-8 shadow-md opacity-90 hover:opacity-100"
          onClick={handleCopy}
        >
          {copied ? (
            <Check className="h-4 w-4 text-emerald-600 dark:text-emerald-400" />
          ) : (
            <Copy className="h-4 w-4" />
          )}
        </Button>
      </div>
      <SyntaxHighlighter
        language={prismLang}
        style={style}
        customStyle={{
          margin: 0,
          padding: '1rem 1rem 1rem 1.25rem',
          paddingRight: '2.75rem',
          fontSize: '0.8125rem',
          lineHeight: 1.6,
          minHeight: '2.5rem',
          background: undefined,
        }}
        codeTagProps={{ style: { fontFamily: 'ui-monospace, SFMono-Regular, "SF Mono", Menlo, Consolas, monospace' } }}
        showLineNumbers={code.split('\n').length > 3}
        lineNumberStyle={{ minWidth: '2em', paddingRight: '1em', color: 'inherit', opacity: 0.5 }}
        wrapLongLines
      >
        {code}
      </SyntaxHighlighter>
    </div>
  )
}

function SetupPage() {
  const { project } = Route.useLoaderData()
  const platformInfo = getPlatformInfo(project.platform)
  const docs = getSetupDocs(project.platform, project.dsn)
  const [dsnCopied, setDsnCopied] = useState(false)

  const handleCopyDSN = async () => {
    await navigator.clipboard.writeText(project.dsn)
    setDsnCopied(true)
    setTimeout(() => setDsnCopied(false), 2000)
  }

  if (!docs) {
    return (
      <div className="min-h-screen bg-background p-8">
        <p className="text-muted-foreground">Unable to load setup documentation.</p>
      </div>
    )
  }

  const PlatformIcon = platformInfo?.icon

  const accentColor = platformInfo?.color ?? '#6366f1'

  return (
    <div className="min-h-screen bg-background">
      {/* Colored header strip */}
      <div
        className="h-1.5 w-full"
        style={{ background: `linear-gradient(90deg, ${accentColor}, transparent 60%)` }}
      />
      <div className="p-6 max-w-4xl mx-auto">
        <div className="mb-8">
          <div className="flex items-center gap-2 mb-2">
            {platformInfo && PlatformIcon && (
              <Badge
                className="flex items-center gap-1.5 px-2.5 py-1 text-white border-0 shadow-sm"
                style={{ backgroundColor: platformInfo.color }}
              >
                <div className="w-4 h-4 flex items-center justify-center">
                  <PlatformIcon className="w-full h-full" />
                </div>
                <span className="text-sm font-medium">{platformInfo.name}</span>
              </Badge>
            )}
            <span className="text-sm text-muted-foreground">{docs.sdkName}</span>
          </div>
          <h1 className="text-3xl font-bold bg-gradient-to-r from-foreground to-foreground/80 bg-clip-text text-transparent">
            {project.name}
          </h1>
          <p className="text-muted-foreground mt-1">Setup Guide</p>
        </div>

        <Card className="mb-8 overflow-hidden border-l-4 shadow-sm" style={{ borderLeftColor: accentColor }}>
          <CardHeader className="bg-muted/40">
            <CardTitle className="text-base flex items-center gap-2">
              <span className="text-emerald-600 dark:text-emerald-400 font-semibold">Your DSN</span>
            </CardTitle>
            <p className="text-sm text-muted-foreground">
              Use this DSN to configure the {docs.sdkName} in your application.
            </p>
          </CardHeader>
          <CardContent className="pt-4">
            <div className="flex items-center gap-2">
              <code className="flex-1 bg-muted/80 dark:bg-muted px-3 py-2.5 rounded-lg text-sm break-all font-mono text-foreground border border-border">
                {project.dsn}
              </code>
              <Button
                variant="outline"
                size="icon"
                onClick={handleCopyDSN}
                className="flex-shrink-0"
              >
                {dsnCopied ? (
                  <Check className="h-4 w-4 text-emerald-600 dark:text-emerald-400" />
                ) : (
                  <Copy className="h-4 w-4" />
                )}
              </Button>
            </div>
          </CardContent>
        </Card>

        <div className="space-y-6">
          {docs.steps.map((step, index) => (
            <Card key={index} className="overflow-hidden border border-border shadow-sm hover:shadow-md transition-shadow">
              <CardHeader className="pb-3">
                <CardTitle className="text-base flex items-center gap-3">
                  <span
                    className="flex items-center justify-center w-9 h-9 rounded-xl text-white text-sm font-bold shadow-sm"
                    style={{ backgroundColor: accentColor }}
                  >
                    {index + 1}
                  </span>
                  <span className="text-foreground">{step.title}</span>
                </CardTitle>
                <p className="text-sm text-muted-foreground pl-12">{step.description}</p>
              </CardHeader>
              {step.code && (
                <CardContent className="pt-0 pl-4 pr-4 pb-4">
                  <CodeBlock code={step.code} language={step.language} />
                </CardContent>
              )}
            </Card>
          ))}
        </div>

        <p className="mt-8 text-sm text-muted-foreground rounded-lg bg-muted/50 dark:bg-muted/30 px-4 py-3 border border-border/50">
          After completing these steps, trigger a test event in your app. Once Moneat receives
          it, you&apos;ll be redirected to your project dashboard.
        </p>
      </div>
    </div>
  )
}
