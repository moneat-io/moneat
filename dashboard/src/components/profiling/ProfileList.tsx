// Moneat - observability platform
// Copyright (C) 2026 Moneat
//
// This program is free software: you can redistribute it and/or modify
// it under the terms of the GNU Affero General Public License as published by
// the Free Software Foundation, either version 3 of the License, or
// (at your option) any later version.

import {useQuery} from '@tanstack/react-query'
import {api, type ProfileResponse} from '@/lib/api'
import {Table, TableBody, TableCell, TableHead, TableHeader, TableRow} from '@/components/ui/table'
import {Badge} from '@/components/ui/badge'
import {Button} from '@/components/ui/button'
import {Input} from '@/components/ui/input'
import {Loader2, Download, Search} from 'lucide-react'
import {useState} from 'react'
import {Link, useNavigate} from '@tanstack/react-router'

function formatBytes(bytes: number): string {
  if (bytes < 1024) return `${bytes} B`
  if (bytes < 1024 * 1024) return `${(bytes / 1024).toFixed(1)} KB`
  return `${(bytes / (1024 * 1024)).toFixed(1)} MB`
}

function formatDuration(ns: number): string {
  if (ns < 1_000_000_000) return `${(ns / 1_000_000).toFixed(0)}ms`
  return `${(ns / 1_000_000_000).toFixed(1)}s`
}

export function ProfileList() {
  const [serviceFilter, setServiceFilter] = useState('')
  const navigate = useNavigate()

  const {data, isLoading} = useQuery({
    queryKey: ['profiles', serviceFilter],
    queryFn: () =>
      api.getProfiles({
        service: serviceFilter || undefined,
        limit: 50,
      }),
    enabled: api.isAuthenticated(),
  })

  const profiles = data?.profiles ?? []

  return (
    <div className="space-y-4">
      <div className="flex items-center gap-2">
        <div className="relative flex-1 max-w-sm">
          <Search className="absolute left-2.5 top-2.5 h-4 w-4 text-muted-foreground" />
          <Input
            placeholder="Filter by service..."
            value={serviceFilter}
            onChange={(e) => setServiceFilter(e.target.value)}
            className="pl-9"
          />
        </div>
        {data?.totalCount != null && (
          <span className="text-sm text-muted-foreground">
            {data.totalCount.toLocaleString()} profiles
          </span>
        )}
      </div>

      {isLoading ? (
        <div className="flex items-center justify-center py-12">
          <Loader2 className="h-6 w-6 animate-spin text-muted-foreground" />
        </div>
      ) : profiles.length === 0 ? (
        <div className="text-center py-12 text-muted-foreground">
          <p className="font-medium">No profiles found</p>
          <p className="text-sm mt-1">
            Enable continuous profiling in your agent to collect profiles.
          </p>
        </div>
      ) : (
        <Table>
          <TableHeader>
            <TableRow>
              <TableHead>Service</TableHead>
              <TableHead>Type</TableHead>
              <TableHead>Host</TableHead>
              <TableHead>Duration</TableHead>
              <TableHead>Size</TableHead>
              <TableHead>Time</TableHead>
              <TableHead className="w-[80px]" />
            </TableRow>
          </TableHeader>
          <TableBody>
            {profiles.map((profile: ProfileResponse) => (
              <TableRow
                key={profile.profileId}
                className="cursor-pointer hover:bg-muted/50"
                onClick={() =>
                  navigate({
                    to: '/profiles/$profileId',
                    params: {profileId: profile.profileId},
                  })
                }
              >
                <TableCell>
                  <Link
                    to="/profiles/$profileId"
                    params={{profileId: profile.profileId}}
                    className="font-medium text-primary hover:underline"
                  >
                    {profile.service || '(unknown)'}
                  </Link>
                </TableCell>
                <TableCell>
                  <Badge variant="secondary">{profile.profileType}</Badge>
                </TableCell>
                <TableCell className="text-sm text-muted-foreground">
                  {profile.host || '—'}
                </TableCell>
                <TableCell className="text-sm font-mono">
                  {formatDuration(profile.durationNs)}
                </TableCell>
                <TableCell className="text-sm font-mono">
                  {formatBytes(profile.sizeBytes)}
                </TableCell>
                <TableCell className="text-sm text-muted-foreground">
                  {new Date(profile.startTime).toLocaleString()}
                </TableCell>
                <TableCell>
                  <Button
                    variant="ghost"
                    size="sm"
                    asChild
                  >
                    <a
                      href={api.getProfileDownloadUrl(profile.profileId)}
                      download
                    >
                      <Download className="h-4 w-4" />
                    </a>
                  </Button>
                </TableCell>
              </TableRow>
            ))}
          </TableBody>
        </Table>
      )}
    </div>
  )
}
