// Moneat - observability platform
// Copyright (C) 2026 Moneat
//
// This program is free software: you can redistribute it and/or modify
// it under the terms of the GNU Affero General Public License as published by
// the Free Software Foundation, either version 3 of the License, or
// (at your option) any later version.

import {ExternalLink, Layers} from 'lucide-react'

export function ProfilingEmptyState() {
  return (
    <div className="rounded-xl border border-dashed py-10 px-4 max-w-lg mx-auto bg-card">
      <div className="flex flex-col items-center text-center">
        <div className="h-12 w-12 rounded-full bg-gradient-to-br from-violet-500/20 to-orange-500/20 border border-violet-500/20 flex items-center justify-center mb-3">
          <Layers className="h-5 w-5 text-violet-500 dark:text-violet-400" />
        </div>
        <p className="font-semibold text-sm text-foreground">No profiles yet</p>
        <p className="text-xs text-muted-foreground mt-1 max-w-sm">
          Set up continuous profiling in your application or compatible agent to
          start collecting flamegraph data.
        </p>
        <a
          href="https://moneat.io/docs/sdk-setup"
          target="_blank"
          rel="noopener noreferrer"
          className="inline-flex items-center gap-1 mt-3 text-xs font-medium text-primary hover:underline"
        >
          Read the setup guide
          <ExternalLink className="h-3.5 w-3.5" />
        </a>
      </div>
    </div>
  )
}
