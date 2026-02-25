// Moneat - observability platform
// Copyright (C) 2026 Moneat
//
// This program is free software: you can redistribute it and/or modify
// it under the terms of the GNU Affero General Public License as published by
// the Free Software Foundation, either version 3 of the License, or
// (at your option) any later version.

import {useQuery} from '@tanstack/react-query'
import {api} from '@/lib/api'
import {Card, CardContent, CardHeader, CardTitle} from '@/components/ui/card'
import {Badge} from '@/components/ui/badge'
import {Loader2, ArrowRight} from 'lucide-react'

function formatDuration(ns: number): string {
  if (ns < 1_000_000) return `${(ns / 1000).toFixed(0)}µs`
  if (ns < 1_000_000_000) return `${(ns / 1_000_000).toFixed(1)}ms`
  return `${(ns / 1_000_000_000).toFixed(2)}s`
}

export function ServiceMap() {
  const {data, isLoading} = useQuery({
    queryKey: ['apmServiceMap'],
    queryFn: () => api.getApmServiceMap(),
    enabled: api.isAuthenticated(),
    refetchInterval: 30000,
  })

  const services = data?.services ?? []

  if (isLoading) {
    return (
      <div className="flex items-center justify-center py-12">
        <Loader2 className="h-6 w-6 animate-spin text-muted-foreground" />
      </div>
    )
  }

  if (services.length === 0) {
    return (
      <div className="text-center py-12 text-muted-foreground">
        <p className="font-medium">No services found</p>
        <p className="text-sm mt-1">
          Service map populates automatically as traces are ingested.
        </p>
      </div>
    )
  }

  return (
    <div className="grid grid-cols-1 md:grid-cols-2 lg:grid-cols-3 gap-4">
      {services.map((svc) => (
        <Card key={svc.service}>
          <CardHeader className="pb-2">
            <CardTitle className="text-base flex items-center justify-between">
              <span>{svc.service}</span>
              {svc.errorCount > 0 && (
                <Badge variant="destructive" className="text-xs">
                  {svc.errorCount} errors
                </Badge>
              )}
            </CardTitle>
          </CardHeader>
          <CardContent className="space-y-2">
            <div className="flex justify-between text-sm">
              <span className="text-muted-foreground">Spans</span>
              <span className="font-mono">
                {svc.spanCount.toLocaleString()}
              </span>
            </div>
            <div className="flex justify-between text-sm">
              <span className="text-muted-foreground">Avg Duration</span>
              <span className="font-mono">
                {formatDuration(svc.avgDurationNs)}
              </span>
            </div>
            {svc.callsTo.length > 0 && (
              <div className="pt-1">
                <span className="text-xs text-muted-foreground">
                  Calls to:
                </span>
                <div className="flex flex-wrap gap-1 mt-1">
                  {svc.callsTo.map((target) => (
                    <Badge
                      key={target}
                      variant="secondary"
                      className="text-xs gap-1"
                    >
                      <ArrowRight className="h-3 w-3" />
                      {target}
                    </Badge>
                  ))}
                </div>
              </div>
            )}
          </CardContent>
        </Card>
      ))}
    </div>
  )
}
