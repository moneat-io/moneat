import {Link} from '@tanstack/react-router'
import {Activity, GitBranch, MessageSquare, Play, Terminal, Zap} from 'lucide-react'
import {Button} from '@/components/ui/button'
import {Card, CardContent} from '@/components/ui/card'
import {Badge} from '@/components/ui/badge'
import {Prism as SyntaxHighlighter} from 'react-syntax-highlighter'
import {oneDark} from 'react-syntax-highlighter/dist/esm/styles/prism'

const sdkCode = `// Install: npm install @sentry/react
import * as Sentry from "@sentry/react";

Sentry.init({
  dsn: "https://xxx@your-moneat.io/1",
  // Same API as Sentry — switch in minutes
  tracesSampleRate: 1.0,
  replaysSessionSampleRate: 0.1,
});`

const features = [
  {
    icon: Activity,
    title: 'Error tracking',
    description:
      'Drop-in Sentry SDK replacement. Same `Sentry.init()`, same breadcrumbs, same error grouping. Just point your DSN at Moneat and you are done. Smart fingerprinting groups similar errors so you can focus on what matters.',
  },
  {
    icon: Zap,
    title: 'Performance monitoring',
    description:
      'Transaction tracing with spans. See slow API calls, database queries, and frontend renders. APM metrics and waterfall views help you pinpoint bottlenecks before users complain.',
  },
  {
    icon: Play,
    title: 'Session replay',
    description:
      'Watch exactly what users did before an error. Full session recordings for web and mobile. Scroll, tap, and navigate — all replayed so you can reproduce issues without guessing.',
  },
  {
    icon: GitBranch,
    title: 'Release tracking',
    description:
      'Link errors to deploys. Crash-free rates per version. See which release introduced a regression and roll back with confidence. Version-based analytics built in.',
  },
  {
    icon: MessageSquare,
    title: 'User feedback',
    description:
      'Let users report issues in context. Feedback is automatically linked to the error, stack trace, and replay. No more "can you send a screenshot?" back-and-forth.',
  },
]

export function VariantC() {
  return (
    <>
      <section className="py-16 px-4 sm:px-6 lg:px-8 bg-background">
        <div className="max-w-6xl mx-auto">
          <div className="grid lg:grid-cols-2 gap-12 items-center">
            <div>
              <Badge variant="secondary" className="mb-4">
                <Terminal className="h-3 w-3 mr-1" />
                Sentry-compatible
              </Badge>
              <h1 className="text-4xl font-bold tracking-tight sm:text-5xl mb-6">
                Ship with confidence.
                <br />
                <span className="text-primary">Switch in minutes.</span>
              </h1>
              <p className="text-lg text-muted-foreground mb-8">
                Use the same Sentry SDKs you already know. Change the DSN. Done. No new APIs, no vendor lock-in.
              </p>
              <div className="flex flex-col sm:flex-row gap-4">
                <Button asChild size="lg">
                  <Link to="/signup">Get Started Free</Link>
                </Button>
                <Button asChild variant="outline" size="lg">
                  <a href="#pricing">See Pricing</a>
                </Button>
              </div>
            </div>
            <div className="relative">
              <div className="rounded-xl overflow-hidden border border-border shadow-2xl bg-slate-900">
                <div className="flex items-center gap-2 px-4 py-3 bg-slate-800 border-b border-slate-700">
                  <div className="flex gap-1.5">
                    <div className="w-3 h-3 rounded-full bg-red-500/80" />
                    <div className="w-3 h-3 rounded-full bg-yellow-500/80" />
                    <div className="w-3 h-3 rounded-full bg-green-500/80" />
                  </div>
                  <span className="text-xs text-slate-400 ml-2">main.tsx</span>
                </div>
                <SyntaxHighlighter
                  language="typescript"
                  style={oneDark}
                  customStyle={{
                    margin: 0,
                    padding: '1rem 1.25rem',
                    fontSize: '0.875rem',
                    background: 'transparent',
                  }}
                  showLineNumbers
                  PreTag="div"
                >
                  {sdkCode}
                </SyntaxHighlighter>
              </div>
              <div className="absolute -bottom-4 -right-4 w-48 h-32 rounded-lg border border-border bg-card shadow-lg opacity-80 hidden lg:block">
                <div className="p-3 space-y-2">
                  <div className="h-2 w-full rounded bg-muted" />
                  <div className="h-2 w-3/4 rounded bg-muted" />
                  <div className="flex gap-2 mt-2">
                    <div className="h-8 flex-1 rounded bg-primary/20" />
                    <div className="h-8 flex-1 rounded bg-primary/20" />
                  </div>
                </div>
              </div>
            </div>
          </div>
        </div>
      </section>

      <section id="features" className="py-16 px-4 sm:px-6 lg:px-8 bg-muted/30 scroll-mt-24">
        <div className="max-w-4xl mx-auto">
          <div className="mb-12">
            <h2 className="text-2xl font-bold mb-2">Built for developers</h2>
            <p className="text-muted-foreground">
              Everything you need to debug production. No fluff.
            </p>
          </div>
          <div className="space-y-8">
            {features.map((feature) => (
              <Card key={feature.title} className="border-border/80 overflow-hidden">
                <CardContent className="p-0">
                  <div className="flex flex-col sm:flex-row">
                    <div className="flex sm:w-48 shrink-0 items-center gap-3 p-6 bg-primary/5">
                      <div className="rounded-lg bg-primary/10 p-3">
                        <feature.icon className="h-6 w-6 text-primary" />
                      </div>
                      <h3 className="font-semibold">{feature.title}</h3>
                    </div>
                    <div className="flex-1 p-6 sm:pl-0 sm:pr-6 sm:py-6">
                      <p className="text-muted-foreground text-sm leading-relaxed">
                        {feature.description}
                      </p>
                    </div>
                  </div>
                </CardContent>
              </Card>
            ))}
          </div>
        </div>
      </section>
    </>
  )
}
