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

import {Link} from '@tanstack/react-router'
import {Activity, Check, Play, X, Zap} from 'lucide-react'
import {Button} from '@/components/ui/button'
import {Card, CardContent} from '@/components/ui/card'
import {Table, TableBody, TableCell, TableHead, TableHeader, TableRow,} from '@/components/ui/table'

const stats = [
  { value: '10x', label: 'More errors included' },
  { value: '60%', label: 'Less cost' },
  { value: '0', label: 'Per-seat fees' },
]

const features = [
  {
    icon: Activity,
    title: 'Error monitoring',
    description: 'Sentry-compatible ingestion. Drop-in replacement with richer limits.',
  },
  {
    icon: Zap,
    title: 'Performance & APM',
    description: 'Transaction tracing, spans, and slowdown detection.',
  },
  {
    icon: Play,
    title: 'Session replay',
    description: 'See what users did. Replay errors on mobile and web.',
  },
]

const comparisonRows = [
  { feature: 'Free tier errors', sentry: '5K', moneat: '10K', moneatBetter: true },
  { feature: 'Mid-tier price', sentry: '$26/mo', moneat: '$19/mo', moneatBetter: true },
  { feature: 'Mid-tier errors', sentry: '50K', moneat: '500K', moneatBetter: true },
  { feature: 'Per-seat pricing', sentry: 'Yes', moneat: 'No', moneatBetter: true },
  { feature: 'Sentry SDK compatible', sentry: 'Yes', moneat: 'Yes', moneatBetter: null },
]

export function VariantB() {
  return (
    <>
      <section id="features" className="py-24 px-4 sm:px-6 lg:px-8 bg-gradient-to-b from-background to-muted/30 scroll-mt-24">
        <div className="max-w-6xl mx-auto">
          <div className="text-center mb-16">
            <h1 className="text-4xl font-bold tracking-tight sm:text-5xl mb-6">
              Error monitoring that
              <br />
              <span className="text-primary">doesn't break the bank</span>
            </h1>
            <p className="text-lg text-muted-foreground max-w-2xl mx-auto mb-12">
              Same powerful SDKs. Same reliability. A fraction of the cost.
            </p>
            <div className="grid grid-cols-1 sm:grid-cols-3 gap-8 max-w-3xl mx-auto mb-12">
              {stats.map((stat) => (
                <div key={stat.label} className="text-center">
                  <div className="text-4xl font-bold text-primary">{stat.value}</div>
                  <div className="text-sm text-muted-foreground mt-1">{stat.label}</div>
                </div>
              ))}
            </div>
            <div className="flex flex-col sm:flex-row gap-4 justify-center">
              <Button asChild size="lg">
                <Link to="/signup">Get Started Free</Link>
              </Button>
              <Button asChild variant="outline" size="lg">
                <a href="#pricing">Compare Plans</a>
              </Button>
            </div>
          </div>

          <div className="mb-24">
            <h2 className="text-2xl font-bold text-center mb-8">Moneat vs Sentry</h2>
            <Card>
              <Table>
                <TableHeader>
                  <TableRow>
                    <TableHead>Feature</TableHead>
                    <TableHead className="text-center">Sentry</TableHead>
                    <TableHead className="text-center">Moneat</TableHead>
                  </TableRow>
                </TableHeader>
                <TableBody>
                  {comparisonRows.map((row) => (
                    <TableRow key={row.feature}>
                      <TableCell className="font-medium">{row.feature}</TableCell>
                      <TableCell className="text-center text-muted-foreground">{row.sentry}</TableCell>
                      <TableCell className="text-center">
                        {row.moneatBetter === true ? (
                          <span className="inline-flex items-center gap-1 text-primary font-medium">
                            <Check className="h-4 w-4" />
                            {row.moneat}
                          </span>
                        ) : row.moneatBetter === false ? (
                          <span className="inline-flex items-center gap-1 text-muted-foreground">
                            <X className="h-4 w-4" />
                            {row.moneat}
                          </span>
                        ) : (
                          row.moneat
                        )}
                      </TableCell>
                    </TableRow>
                  ))}
                </TableBody>
              </Table>
            </Card>
          </div>

          <div className="grid sm:grid-cols-3 gap-6">
            {features.map((feature) => (
              <Card key={feature.title} className="border-border/80">
                <CardContent className="pt-6">
                  <div className="rounded-lg bg-primary/10 w-fit p-3 mb-4">
                    <feature.icon className="h-6 w-6 text-primary" />
                  </div>
                  <h3 className="font-semibold text-lg mb-2">{feature.title}</h3>
                  <p className="text-muted-foreground text-sm">{feature.description}</p>
                </CardContent>
              </Card>
            ))}
          </div>
        </div>
      </section>
    </>
  )
}
