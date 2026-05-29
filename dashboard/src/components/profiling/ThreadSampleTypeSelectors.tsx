// Moneat - observability platform
// Copyright (C) 2026 Moneat
//
// This program is free software: you can redistribute it and/or modify
// it under the terms of the GNU Affero General Public License as published by
// the Free Software Foundation, either version 3 of the License, or
// (at your option) any later version.

import type {SampleTypeInfo, ThreadInfo} from '@/lib/api/types/profiles'

interface Props {
  sampleTypes: SampleTypeInfo[]
  threads: ThreadInfo[]
  selectedSampleType?: string
  selectedThread?: string | null
  unit?: string
  onSampleTypeChange: (key: string) => void
  onThreadChange: (id: string | null) => void
}

const SELECT_CLASS =
  'h-7 w-full text-[11px] rounded-md border bg-background px-1.5'
const LABEL_CLASS =
  'block text-[10px] font-medium uppercase tracking-wide text-muted-foreground'

export function ThreadSampleTypeSelectors({
  sampleTypes,
  threads,
  selectedSampleType,
  selectedThread,
  unit,
  onSampleTypeChange,
  onThreadChange,
}: Props) {
  const showTypes = sampleTypes.length > 1
  const showThreads = threads.length > 0
  if (!showTypes && !showThreads) return null

  return (
    <>
      {showTypes && (
        <div className="space-y-1">
          <span className={LABEL_CLASS}>Type{unit ? ` (${unit})` : ''}</span>
          <select
            value={selectedSampleType ?? sampleTypes[0]?.key}
            onChange={(e) => onSampleTypeChange(e.target.value)}
            className={SELECT_CLASS}
          >
            {sampleTypes.map((t) => (
              <option key={t.key} value={t.key}>
                {t.label}
              </option>
            ))}
          </select>
        </div>
      )}
      {showThreads && (
        <div className="space-y-1">
          <span className={LABEL_CLASS}>Thread</span>
          <select
            value={selectedThread ?? ''}
            onChange={(e) => onThreadChange(e.target.value || null)}
            className={SELECT_CLASS}
          >
            <option value="">All threads</option>
            {threads.map((t) => (
              <option key={t.id} value={t.id}>
                {t.label} ({t.samples.toLocaleString()})
              </option>
            ))}
          </select>
        </div>
      )}
    </>
  )
}
