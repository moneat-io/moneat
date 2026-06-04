// Moneat - observability platform
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

import {useMemo, useState, type FormEvent} from 'react'
import {Check, Loader2} from 'lucide-react'
import {Button} from '@/components/ui/button'
import {Input} from '@/components/ui/input'
import {Label} from '@/components/ui/label'
import {cn} from '@/lib/utils'
import {platforms, type PlatformType} from '@/routes/projects'
import {TelemetrySourcePicker} from '@/components/projects/TelemetrySourcePicker'
import {
  DEFAULT_SELECTED_TELEMETRY_SOURCE_IDS,
  type TelemetrySourceId,
} from '@/lib/telemetry-sources'

type PlatformFilter = 'all' | 'mobile' | 'frontend' | 'backend' | 'desktop-gaming'

export interface ServiceSetupSubmission {
  name: string
  framework: string
  targets?: string[]
  sourceIds: TelemetrySourceId[]
}

interface ServiceSetupFormProps {
  readonly onSubmit: (submission: ServiceSetupSubmission) => void
  readonly isSubmitting?: boolean
  readonly submitLabel?: string
  readonly submittingLabel?: string
  readonly onCancel?: () => void
  readonly cancelLabel?: string
  readonly error?: string
  readonly autoFocus?: boolean
  readonly initialSourceIds?: TelemetrySourceId[]
}

const platformFilterTabs: Array<{id: PlatformFilter; label: string}> = [
  {id: 'all', label: 'All'},
  {id: 'mobile', label: 'Mobile'},
  {id: 'frontend', label: 'Frontend'},
  {id: 'backend', label: 'Backend'},
  {id: 'desktop-gaming', label: 'Desktop & Gaming'},
]

function getDefaultTargets(platformId: string): string[] {
  const platform = platforms.find((candidate) => candidate.id === platformId)
  if (platform?.targets && platform.defaultTargets) {
    return platform.defaultTargets
  }
  return []
}

export function ServiceSetupForm({
  onSubmit,
  isSubmitting = false,
  submitLabel = 'Create Service',
  submittingLabel = 'Creating...',
  onCancel,
  cancelLabel = 'Cancel',
  error,
  autoFocus = false,
  initialSourceIds = DEFAULT_SELECTED_TELEMETRY_SOURCE_IDS,
}: ServiceSetupFormProps) {
  const [serviceName, setServiceName] = useState('')
  const [selectedPlatform, setSelectedPlatform] = useState<string | null>(null)
  const [selectedTargets, setSelectedTargets] = useState<string[]>([])
  const [platformFilter, setPlatformFilter] = useState<PlatformFilter>('all')
  const [selectedSourceIds, setSelectedSourceIds] = useState<TelemetrySourceId[]>(initialSourceIds)

  const selectedPlatformInfo = useMemo(
    () => platforms.find((platform) => platform.id === selectedPlatform),
    [selectedPlatform]
  )

  const filteredPlatforms = useMemo(
    () => platforms.filter((platform: PlatformType) => {
      if (platform.alwaysVisible || platformFilter === 'all') return true
      if (platformFilter === 'desktop-gaming') {
        return platform.category === 'desktop' || platform.category === 'gaming'
      }
      return platform.category === platformFilter
    }),
    [platformFilter]
  )

  const requiresTargets = Boolean(selectedPlatformInfo?.targets)
  const hasValidTargets = !requiresTargets || selectedTargets.length > 0
  const canSubmit =
    serviceName.trim().length > 0 &&
    Boolean(selectedPlatform) &&
    hasValidTargets &&
    selectedSourceIds.length > 0 &&
    !isSubmitting

  const handlePlatformSelect = (platformId: string) => {
    setSelectedPlatform(platformId)
    setSelectedTargets(getDefaultTargets(platformId))
  }

  const toggleTarget = (targetId: string) => {
    setSelectedTargets((currentTargets) =>
      currentTargets.includes(targetId)
        ? currentTargets.filter((currentTarget) => currentTarget !== targetId)
        : [...currentTargets, targetId]
    )
  }

  const handleSubmit = (event: FormEvent) => {
    event.preventDefault()
    if (!canSubmit || !selectedPlatform) return

    onSubmit({
      name: serviceName.trim(),
      framework: selectedPlatform,
      targets: requiresTargets ? selectedTargets : undefined,
      sourceIds: selectedSourceIds,
    })
  }

  return (
    <form onSubmit={handleSubmit} className="flex flex-col gap-5">
      {error ? (
        <div className="rounded-md border border-destructive/30 bg-destructive/10 px-3 py-2 text-sm text-destructive">
          {error}
        </div>
      ) : null}

      <div className="flex flex-col gap-2">
        <Label htmlFor="service-name">Service name</Label>
        <Input
          id="service-name"
          placeholder="Checkout API"
          value={serviceName}
          onChange={(event) => setServiceName(event.target.value)}
          autoFocus={autoFocus}
        />
      </div>

      <div className="flex flex-col gap-3">
        <Label>Application platform</Label>
        <div className="flex flex-wrap gap-2">
          {platformFilterTabs.map((tab) => (
            <Button
              key={tab.id}
              type="button"
              size="sm"
              variant={platformFilter === tab.id ? 'default' : 'outline'}
              onClick={() => setPlatformFilter(tab.id)}
            >
              {tab.label}
            </Button>
          ))}
        </div>
        <div className="max-h-64 overflow-y-auto rounded-lg border p-3 pr-2">
          <div className="grid grid-cols-2 gap-2 sm:grid-cols-3 md:grid-cols-4">
            {filteredPlatforms.map((platform: PlatformType) => {
              const Icon = platform.icon
              const isSelected = selectedPlatform === platform.id
              return (
                <button
                  key={platform.id}
                  type="button"
                  onClick={() => handlePlatformSelect(platform.id)}
                  className={cn(
                    'relative flex flex-col items-center gap-1.5 rounded-lg border-2 p-3 transition-all',
                    isSelected
                      ? 'border-primary bg-primary/5'
                      : 'border-border hover:border-primary/50 hover:bg-accent'
                  )}
                  aria-pressed={isSelected}
                >
                  <div className="rounded-lg p-2" style={{backgroundColor: platform.color}}>
                    <Icon className="h-5 w-5 text-white" />
                  </div>
                  <span className="text-center text-xs font-medium leading-tight">{platform.name}</span>
                </button>
              )
            })}
          </div>
        </div>
      </div>

      {selectedPlatformInfo?.targets ? (
        <div className="flex flex-col gap-3">
          <Label>Target platforms</Label>
          <div className="rounded-lg border p-4">
            <div className="grid grid-cols-2 gap-2 sm:grid-cols-3">
              {selectedPlatformInfo.targets.map((target) => {
                const isSelected = selectedTargets.includes(target.id)
                return (
                  <button
                    key={target.id}
                    type="button"
                    onClick={() => toggleTarget(target.id)}
                    className={cn(
                      'flex items-center gap-2 rounded-lg border-2 px-3 py-2 text-sm font-medium transition-all',
                      isSelected ? 'border-primary bg-primary/5' : 'border-border hover:border-primary/50'
                    )}
                    aria-pressed={isSelected}
                  >
                    <span
                      className={cn(
                        'flex h-4 w-4 items-center justify-center rounded border-2',
                        isSelected ? 'border-primary bg-primary' : 'border-border'
                      )}
                    >
                      {isSelected ? <Check className="h-3 w-3 text-primary-foreground" /> : null}
                    </span>
                    {target.name}
                  </button>
                )
              })}
            </div>
            {selectedTargets.length === 0 ? (
              <p className="mt-2 text-sm text-destructive">Select at least one target platform.</p>
            ) : null}
          </div>
        </div>
      ) : null}

      <TelemetrySourcePicker value={selectedSourceIds} onChange={setSelectedSourceIds} />

      <div className="flex flex-wrap gap-2 pt-1">
        <Button type="submit" disabled={!canSubmit}>
          {isSubmitting ? (
            <>
              <Loader2 className="mr-2 h-4 w-4 animate-spin" />
              {submittingLabel}
            </>
          ) : (
            submitLabel
          )}
        </Button>
        {onCancel ? (
          <Button type="button" variant="outline" onClick={onCancel} disabled={isSubmitting}>
            {cancelLabel}
          </Button>
        ) : null}
      </div>
    </form>
  )
}
