// Moneat - observability platform
// Copyright (C) 2026 Moneat
//
// This program is free software: you can redistribute it and/or modify
// it under the terms of the GNU Affero General Public License as published by
// the Free Software Foundation, either version 3 of the License, or
// (at your option) any later version.

import {X} from 'lucide-react'
import type {ReactNode} from 'react'
import {Button} from '@/components/ui/button'

interface MapDetailPanelProps {
  title: string
  onClose: () => void
  children: ReactNode
}

export function MapDetailPanel({title, onClose, children}: MapDetailPanelProps) {
  return (
    <div className="absolute right-3 top-3 w-60 overflow-hidden rounded-lg border bg-card animate-in fade-in slide-in-from-right-2 duration-200">
      <div className="flex items-center justify-between border-b bg-muted/30 px-3 py-2">
        <h3 className="truncate text-sm font-semibold">{title}</h3>
        <Button
          type="button"
          variant="ghost"
          size="icon"
          onClick={onClose}
          className="h-6 w-6 text-muted-foreground hover:text-foreground"
          aria-label="Close map detail panel"
        >
          <X className="h-3.5 w-3.5" />
        </Button>
      </div>
      <div className="space-y-2 px-3 py-2.5 text-sm">
        {children}
      </div>
    </div>
  )
}
