// Moneat - observability platform
// Copyright (C) 2026 Moneat
//
// This program is free software: you can redistribute it and/or modify
// it under the terms of the GNU Affero General Public License as published by
// the Free Software Foundation, either version 3 of the License, or
// (at your option) any later version.

import type React from 'react'

export function ProfileStatCard({
  label,
  value,
  icon,
}: {
  label: string
  value: string
  icon: React.ReactNode
}) {
  return (
    <div className="rounded-lg border bg-card px-2.5 py-2 flex flex-col gap-0.5">
      <div className="flex items-center gap-1 text-muted-foreground">
        {icon}
        <span className="text-[11px] font-medium">{label}</span>
      </div>
      <span className="text-lg font-bold tabular-nums tracking-tight">{value}</span>
    </div>
  )
}
