// Moneat - observability platform
// Copyright (C) 2026 Moneat
//
// This program is free software: you can redistribute it and/or modify
// it under the terms of the GNU Affero General Public License as published by
// the Free Software Foundation, either version 3 of the License, or
// (at your option) any later version.

import {createFileRoute, Link} from '@tanstack/react-router'
import {useQuery} from '@tanstack/react-query'
import {api} from '@/lib/api'
import {DdFlamegraph} from '@/components/datadog/DdFlamegraph'
import {Card, CardContent, CardHeader, CardTitle} from '@/components/ui/card'
import {Badge} from '@/components/ui/badge'
import {Button} from '@/components/ui/button'
import {ArrowLeft, Download, Loader2} from 'lucide-react'

export const Route = createFileRoute('/dd-profiles/$profileId')({
  component: DdProfileDetailPage,
})

function formatBytes(bytes: number): string {
  if (bytes < 1024) return `${bytes} B`
  if (bytes < 1024 * 1024) return `${(bytes / 1024).toFixed(1)} KB`
  return `${(bytes / (1024 * 1024)).toFixed(1)} MB`
}

function formatDuration(ns: number): string {
  if (ns < 1_000_000_000) return `${(ns / 1_000_000).toFixed(0)}ms`
  return `${(ns / 1_000_000_000).toFixed(1)}s`
}

function DdProfileDetailPage() {
  const {profileId} = Route.useParams()

  // Fetch the profiles list to get metadata for this profile
  const {data: profilesData, isLoading} = useQuery({
    queryKey: ['ddProfiles'],
    queryFn: () => api.getDdProfiles({limit: 200}),
    enabled: api.isAuthenticated(),
  })

  const profile = profilesData?.profiles?.find(
    (p) => p.profileId === profileId,
  )

  if (isLoading) {
    return (
      <div className="p-6 flex items-center justify-center py-24">
        <Loader2 className="h-8 w-8 animate-spin text-muted-foreground" />
      </div>
    )
  }

  return (
    <div className="p-6 space-y-6">
      {/* Header */}
      <div className="flex items-center justify-between">
        <div className="flex items-center gap-4">
          <Button variant="ghost" size="sm" asChild>
            <Link to="/dd-profiles">
              <ArrowLeft className="h-4 w-4" />
            </Link>
          </Button>
          <div>
            <h1 className="text-xl font-bold">
              {profile?.service || 'Profile'} — {profile?.profileType || ''}
            </h1>
            <p className="text-sm text-muted-foreground font-mono">
              {profileId}
            </p>
          </div>
        </div>
        <Button variant="outline" size="sm" asChild>
          <a href={api.getDdProfileDownloadUrl(profileId)} download>
            <Download className="h-4 w-4 mr-2" />
            Download pprof
          </a>
        </Button>
      </div>

      {/* Metadata */}
      {profile && (
        <div className="grid grid-cols-2 md:grid-cols-4 gap-4">
          <Card>
            <CardHeader className="pb-1 pt-3">
              <CardTitle className="text-xs text-muted-foreground font-normal">
                Service
              </CardTitle>
            </CardHeader>
            <CardContent className="pt-0">
              <span className="font-medium">{profile.service || '—'}</span>
            </CardContent>
          </Card>
          <Card>
            <CardHeader className="pb-1 pt-3">
              <CardTitle className="text-xs text-muted-foreground font-normal">
                Type
              </CardTitle>
            </CardHeader>
            <CardContent className="pt-0">
              <Badge variant="secondary">{profile.profileType}</Badge>
            </CardContent>
          </Card>
          <Card>
            <CardHeader className="pb-1 pt-3">
              <CardTitle className="text-xs text-muted-foreground font-normal">
                Duration
              </CardTitle>
            </CardHeader>
            <CardContent className="pt-0">
              <span className="font-mono">
                {formatDuration(profile.durationNs)}
              </span>
            </CardContent>
          </Card>
          <Card>
            <CardHeader className="pb-1 pt-3">
              <CardTitle className="text-xs text-muted-foreground font-normal">
                Size
              </CardTitle>
            </CardHeader>
            <CardContent className="pt-0">
              <span className="font-mono">
                {formatBytes(profile.sizeBytes)}
              </span>
            </CardContent>
          </Card>
        </div>
      )}

      {/* Flamegraph placeholder */}
      <div>
        <h2 className="text-lg font-semibold mb-3">Flamegraph</h2>
        <DdFlamegraph
          emptyMessage="Flamegraph rendering requires pprof parsing. Download the profile to view in speedscope or pprof."
        />
      </div>

      {/* Additional metadata */}
      {profile && Object.keys(profile.tags).length > 0 && (
        <Card>
          <CardHeader>
            <CardTitle className="text-base">Tags</CardTitle>
          </CardHeader>
          <CardContent>
            <div className="flex flex-wrap gap-2">
              {Object.entries(profile.tags).map(([k, v]) => (
                <Badge key={k} variant="outline" className="text-xs">
                  {k}: {v}
                </Badge>
              ))}
            </div>
          </CardContent>
        </Card>
      )}
    </div>
  )
}
