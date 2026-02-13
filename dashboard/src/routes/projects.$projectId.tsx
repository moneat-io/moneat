import {createFileRoute, Link, Outlet, redirect, useRouter, useRouterState} from '@tanstack/react-router'
import {api} from '@/lib/api'
import {getSetupDocs} from '@/lib/setup-docs'
import {getPlatformInfo} from '@/routes/projects'
import {Card, CardContent, CardHeader, CardTitle} from '@/components/ui/card'
import {Button} from '@/components/ui/button'
import {Badge} from '@/components/ui/badge'
import {Tabs, TabsContent, TabsList, TabsTrigger} from '@/components/ui/tabs'
import {Check, Copy, Plus, Settings, X} from 'lucide-react'
import {useEffect, useState} from 'react'
import {Prism as SyntaxHighlighter} from 'react-syntax-highlighter'
import {oneDark, oneLight} from 'react-syntax-highlighter/dist/esm/styles/prism'
import {useMutation, useQuery, useQueryClient} from '@tanstack/react-query'
import {cn} from '@/lib/utils'

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
  const routerState = useRouterState()
  const showingChildPage = /^\/projects\/[^/]+\/(settings|logs)\/?$/.test(routerState.location.pathname)
  const { project } = Route.useLoaderData()
  const router = useRouter()
  const queryClient = useQueryClient()
  const {data: sdkVersionsResponse} = useQuery({
    queryKey: ['sdk-versions'],
    queryFn: () => api.getSdkVersions(),
    staleTime: 30 * 60 * 1000,
  })
  const {data: currentUser} = useQuery({
    queryKey: ['currentUser'],
    queryFn: () => api.getCurrentUser(),
    staleTime: 30 * 60 * 1000,
  })
  const sdkVersions = sdkVersionsResponse?.versions
  const platformInfo = getPlatformInfo(project.framework)
  const [selectedTarget, setSelectedTarget] = useState<string | null>(null)
  const [dsnCopiedMap, setDsnCopiedMap] = useState<Record<string, boolean>>({})
  const [showAddPlatform, setShowAddPlatform] = useState(false)

  const backendUrl = import.meta.env.VITE_BACKEND_URL || 'https://api.moneat.io'
  const setupOptions = {
    orgSlug: currentUser?.organizationSlug,
    projectSlug: project.slug,
    backendUrl,
  }

  // Initialize selected target
  useEffect(() => {
    if (project.keys.length > 0 && !selectedTarget) {
      setSelectedTarget(project.keys[0].platformTarget ?? 'default')
    }
  }, [project.keys, selectedTarget])

  const addTargetMutation = useMutation({
    mutationFn: (target: string) => api.addProjectTarget(project.id, target),
    onSuccess: () => {
      queryClient.invalidateQueries({ queryKey: ['projects'] })
      router.invalidate()
      setShowAddPlatform(false)
    },
  })

  const handleCopyDSN = async (dsn: string, targetKey: string) => {
    await navigator.clipboard.writeText(dsn)
    setDsnCopiedMap(prev => ({ ...prev, [targetKey]: true }))
    setTimeout(() => {
      setDsnCopiedMap(prev => ({ ...prev, [targetKey]: false }))
    }, 2000)
  }

  const PlatformIcon = platformInfo?.icon
  const accentColor = platformInfo?.color ?? '#6366f1'
  const isMultiPlatform = project.keys.length > 1 || (platformInfo?.targets && platformInfo.targets.length > 0)

  // Get the framework's available targets that haven't been added yet
  const existingTargets = project.keys
    .map(k => k.platformTarget)
    .filter(Boolean) as string[]

  const availableTargets = platformInfo?.targets?.filter(
    t => !existingTargets.includes(t.id)
  ) ?? []

  // For multi-platform, get docs for each target
  // For single-platform, use the framework platform
  const getCurrentDocs = (targetId: string | null) => {
    if (!targetId || targetId === 'default') {
      return getSetupDocs(project.framework, project.dsn, sdkVersions, setupOptions)
    }
    // Try to get target-specific docs (e.g., "kmp-android")
    const targetSpecificDocs = getSetupDocs(`${project.framework}-${targetId}`,
      project.keys.find(k => k.platformTarget === targetId)?.dsn || project.dsn,
      sdkVersions, setupOptions)
    // Fall back to framework docs if target-specific not found
    return targetSpecificDocs || getSetupDocs(project.framework,
      project.keys.find(k => k.platformTarget === targetId)?.dsn || project.dsn,
      sdkVersions, setupOptions)
  }

  const docs = getCurrentDocs(selectedTarget)

  if (showingChildPage) {
    return <Outlet />
  }

  if (!docs) {
    return (
      <div className="p-8">
        <p className="text-muted-foreground">Unable to load setup documentation.</p>
      </div>
    )
  }

  return (
    <div>
      {/* Colored header strip */}
      <div
        className="h-1.5 w-full"
        style={{ background: `linear-gradient(90deg, ${accentColor}, transparent 60%)` }}
      />
      <div className="p-6 max-w-4xl mx-auto">
        <div className="mb-8 flex items-start justify-between gap-4">
          <div>
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
          <Button asChild variant="outline" size="icon" aria-label="Project settings">
            <Link to="/projects/$projectId/settings" params={{ projectId: String(project.id) }}>
              <Settings className="h-4 w-4" />
            </Link>
          </Button>
        </div>

        {/* Target Platforms Card - for multiplatform projects */}
        {isMultiPlatform && (
          <Card className="mb-8 overflow-hidden shadow-sm border" style={{ borderColor: `${accentColor}30` }}>
            <CardHeader className="bg-muted/30 pb-3">
              <div className="flex items-center justify-between">
                <CardTitle className="text-base">Target Platforms</CardTitle>
                {availableTargets.length > 0 && !showAddPlatform && (
                  <Button
                    variant="outline"
                    size="sm"
                    onClick={() => setShowAddPlatform(true)}
                    className="gap-1.5"
                  >
                    <Plus className="h-3.5 w-3.5" />
                    Add Platform
                  </Button>
                )}
              </div>
              <p className="text-sm text-muted-foreground">
                Each target platform gets its own DSN for separate error tracking.
              </p>
            </CardHeader>
            <CardContent className="pt-4">
              {/* Existing targets */}
              <div className="flex flex-wrap gap-2 mb-2">
                {existingTargets.map(target => {
                  const targetPlatformInfo = getPlatformInfo(target)
                  const TargetIcon = targetPlatformInfo?.icon
                  return (
                    <Badge
                      key={target}
                      variant="secondary"
                      className={cn(
                        'flex items-center gap-1.5 px-2.5 py-1 cursor-pointer transition-all',
                        selectedTarget === target
                          ? 'ring-2 ring-primary ring-offset-1 ring-offset-background'
                          : 'hover:bg-secondary/80'
                      )}
                      onClick={() => setSelectedTarget(target)}
                    >
                      {TargetIcon && (
                        <div
                          className="w-4 h-4 rounded flex items-center justify-center"
                          style={{ backgroundColor: targetPlatformInfo?.color }}
                        >
                          <TargetIcon className="w-3 h-3 text-white" />
                        </div>
                      )}
                      <span className="text-xs font-medium">
                        {target.split('-').map(w => w.charAt(0).toUpperCase() + w.slice(1)).join(' ')}
                      </span>
                    </Badge>
                  )
                })}
                {existingTargets.length === 0 && project.keys.length === 1 && (
                  <Badge variant="secondary" className="flex items-center gap-1.5 px-2.5 py-1">
                    <span className="text-xs font-medium">Default</span>
                  </Badge>
                )}
              </div>

              {/* Add platform inline UI */}
              {showAddPlatform && availableTargets.length > 0 && (
                <div className="mt-4 rounded-lg border border-dashed border-primary/30 bg-primary/5 p-4">
                  <div className="flex items-center justify-between mb-3">
                    <span className="text-sm font-medium">Add a target platform</span>
                    <Button
                      variant="ghost"
                      size="icon"
                      className="h-6 w-6"
                      onClick={() => setShowAddPlatform(false)}
                    >
                      <X className="h-3.5 w-3.5" />
                    </Button>
                  </div>
                  <div className="grid grid-cols-2 sm:grid-cols-3 gap-2">
                    {availableTargets.map(target => {
                      const targetPlatformInfo = getPlatformInfo(target.id)
                      const TargetIcon = targetPlatformInfo?.icon
                      return (
                        <button
                          key={target.id}
                          onClick={() => addTargetMutation.mutate(target.id)}
                          disabled={addTargetMutation.isPending}
                          className={cn(
                            'flex items-center gap-2.5 px-3 py-2.5 rounded-lg border-2 transition-all text-sm font-medium',
                            'border-border hover:border-primary hover:bg-primary/5',
                            addTargetMutation.isPending && 'opacity-50 cursor-not-allowed'
                          )}
                        >
                          {TargetIcon && (
                            <div
                              className="w-6 h-6 rounded flex items-center justify-center flex-shrink-0"
                              style={{ backgroundColor: targetPlatformInfo?.color }}
                            >
                              <TargetIcon className="w-4 h-4 text-white" />
                            </div>
                          )}
                          <span>{target.name}</span>
                          <Plus className="h-3.5 w-3.5 ml-auto text-muted-foreground" />
                        </button>
                      )
                    })}
                  </div>
                  {addTargetMutation.isError && (
                    <p className="text-sm text-destructive mt-2">
                      Failed to add platform. It may already exist.
                    </p>
                  )}
                </div>
              )}
            </CardContent>
          </Card>
        )}

        {/* DSN Card */}
        <Card className="mb-8 overflow-hidden border-l-4 shadow-sm" style={{ borderLeftColor: accentColor }}>
          <CardHeader className="bg-muted/40">
            <CardTitle className="text-base flex items-center gap-2">
              <span className="text-emerald-600 dark:text-emerald-400 font-semibold">
                {project.keys.length > 1 ? 'Your DSNs' : 'Your DSN'}
              </span>
            </CardTitle>
            <p className="text-sm text-muted-foreground">
              {project.keys.length > 1
                ? `Use the appropriate DSN for each target platform in your ${docs.sdkName}.`
                : `Use this DSN to configure the ${docs.sdkName} in your application.`
              }
            </p>
          </CardHeader>
          <CardContent className="pt-4">
            {project.keys.length > 1 ? (
              <Tabs value={selectedTarget || 'default'} onValueChange={setSelectedTarget} className="w-full">
                <TabsList className="mb-4 flex-wrap h-auto gap-1">
                  {project.keys.map(key => (
                    <TabsTrigger key={key.platformTarget || 'default'} value={key.platformTarget || 'default'}>
                      {key.platformTarget ? key.platformTarget.split('-').map(w => w.charAt(0).toUpperCase() + w.slice(1)).join(' ') : 'Default'}
                    </TabsTrigger>
                  ))}
                </TabsList>
                {project.keys.map(key => {
                  const targetKey = key.platformTarget || 'default'
                  return (
                    <TabsContent key={targetKey} value={targetKey}>
                      <div className="flex items-center gap-2">
                        <code className="flex-1 bg-muted/80 dark:bg-muted px-3 py-2.5 rounded-lg text-sm break-all font-mono text-foreground border border-border">
                          {key.dsn}
                        </code>
                        <Button
                          variant="outline"
                          size="icon"
                          onClick={() => handleCopyDSN(key.dsn, targetKey)}
                          className="flex-shrink-0"
                        >
                          {dsnCopiedMap[targetKey] ? (
                            <Check className="h-4 w-4 text-emerald-600 dark:text-emerald-400" />
                          ) : (
                            <Copy className="h-4 w-4" />
                          )}
                        </Button>
                      </div>
                    </TabsContent>
                  )
                })}
              </Tabs>
            ) : (
              <div className="flex items-center gap-2">
                <code className="flex-1 bg-muted/80 dark:bg-muted px-3 py-2.5 rounded-lg text-sm break-all font-mono text-foreground border border-border">
                  {project.dsn}
                </code>
                <Button
                  variant="outline"
                  size="icon"
                  onClick={() => handleCopyDSN(project.dsn, 'default')}
                  className="flex-shrink-0"
                >
                  {dsnCopiedMap['default'] ? (
                    <Check className="h-4 w-4 text-emerald-600 dark:text-emerald-400" />
                  ) : (
                    <Copy className="h-4 w-4" />
                  )}
                </Button>
              </div>
            )}
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
