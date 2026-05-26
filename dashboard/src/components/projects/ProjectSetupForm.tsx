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

import {useMemo, useState, type ComponentType, type FormEvent} from 'react'
import {
  Check,
  DatabaseZap,
  Loader2,
  RadioTower,
  ServerCog,
} from 'lucide-react'
import {Button} from '@/components/ui/button'
import {Input} from '@/components/ui/input'
import {Label} from '@/components/ui/label'
import {cn} from '@/lib/utils'
import {platforms, type PlatformType} from '@/routes/projects'
import {
  DEFAULT_SELECTED_TELEMETRY_SOURCE_IDS,
  TELEMETRY_SOURCES,
  toggleTelemetrySourceId,
  type TelemetrySourceId,
} from '@/lib/telemetry-sources'

type PlatformFilter = 'all' | 'mobile' | 'frontend' | 'backend' | 'desktop-gaming'

export interface ProjectSetupSubmission {
  name: string
  framework: string
  targets?: string[]
  sourceIds: TelemetrySourceId[]
}

interface ProjectSetupFormProps {
  readonly onSubmit: (submission: ProjectSetupSubmission) => void
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

const sourceIcons: Record<TelemetrySourceId, ComponentType<{className?: string}>> = {
  'opentelemetry': RadioTower,
  'sentry-sdk': DatabaseZap,
  'datadog-agent': ServerCog,
}

function getDefaultTargets(platformId: string): string[] {
  const platform = platforms.find((candidate) => candidate.id === platformId)
  if (platform?.targets && platform.defaultTargets) {
    return platform.defaultTargets
  }
  return []
}

export function ProjectSetupForm({
  onSubmit,
  isSubmitting = false,
  submitLabel = 'Create Project',
  submittingLabel = 'Creating...',
  onCancel,
  cancelLabel = 'Cancel',
  error,
  autoFocus = false,
  initialSourceIds = DEFAULT_SELECTED_TELEMETRY_SOURCE_IDS,
}: ProjectSetupFormProps) {
  const [projectName, setProjectName] = useState('')
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
    projectName.trim().length > 0 &&
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

  const handleSourceToggle = (sourceId: TelemetrySourceId) => {
    setSelectedSourceIds((currentSourceIds) => toggleTelemetrySourceId(currentSourceIds, sourceId))
  }

  const handleSubmit = (event: FormEvent) => {
    event.preventDefault()
    if (!canSubmit || !selectedPlatform) return

    onSubmit({
      name: projectName.trim(),
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
        <Label htmlFor="project-name">Project Name</Label>
        <Input
          id="project-name"
          placeholder="Checkout API"
          value={projectName}
          onChange={(event) => setProjectName(event.target.value)}
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
                      ? 'border-primary bg-primary/5 shadow-md'
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

      <div className="flex flex-col gap-3">
        <div>
          <Label>Telemetry sources</Label>
          <p className="mt-1 text-sm text-muted-foreground">
            Pick the source setup Moneat should walk you through after the project is created.
          </p>
        </div>
        <div className="grid gap-2 sm:grid-cols-2">
          {TELEMETRY_SOURCES.map((source) => {
            const Icon = sourceIcons[source.id]
            const isSelected = selectedSourceIds.includes(source.id)
            return (
              <button
                key={source.id}
                type="button"
                onClick={() => handleSourceToggle(source.id)}
                className={cn(
                  'flex min-h-28 items-start gap-3 rounded-lg border-2 p-3 text-left transition-all',
                  isSelected ? 'border-primary bg-primary/5' : 'border-border hover:border-primary/50'
                )}
                aria-pressed={isSelected}
              >
                <span className="flex h-9 w-9 shrink-0 items-center justify-center rounded-md bg-muted">
                  <Icon className="h-4 w-4" />
                </span>
                <span className="min-w-0 flex-1">
                  <span className="flex items-center gap-2 text-sm font-medium">
                    {source.shortLabel}
                    {isSelected ? <Check className="h-3.5 w-3.5 text-primary" /> : null}
                  </span>
                  <span className="mt-1 block text-xs leading-5 text-muted-foreground">{source.description}</span>
                </span>
              </button>
            )
          })}
        </div>
      </div>

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
