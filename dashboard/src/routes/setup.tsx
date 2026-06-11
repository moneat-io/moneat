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

import {createFileRoute, redirect, useNavigate, useSearch} from '@tanstack/react-router'
import {Boxes, CloudCog, ServerCog, SlidersHorizontal, type LucideIcon} from 'lucide-react'
import {api} from '@/lib/api'
import {cn} from '@/lib/utils'
import {PageHeader} from '@/components/ui/page-header'
import {ServicesAndSdksSetup} from '@/components/setup/ServicesAndSdksSetup'
import {InfrastructureAgentSetup} from '@/components/setup/InfrastructureAgentSetup'
import {CloudAccountSetup} from '@/components/setup/CloudAccountSetup'

type SetupTab = 'services' | 'infrastructure' | 'cloud'

function parseSetupTab(value: unknown): SetupTab {
  return value === 'infrastructure' || value === 'cloud' ? value : 'services'
}

interface SetupSearch {
  readonly tab: SetupTab
  readonly service?: string
}

export const Route = createFileRoute('/setup')({
  validateSearch: (search: Record<string, unknown>): SetupSearch => ({
    tab: parseSetupTab(search.tab),
    service: typeof search.service === 'string' ? search.service : undefined,
  }),
  beforeLoad: async ({location}) => {
    if (!api.isAuthenticated()) {
      throw redirect({to: '/login', search: {redirect: location.href}})
    }
  },
  component: SetupPage,
})

interface ConfigMode {
  readonly id: SetupTab
  readonly label: string
  readonly icon: LucideIcon
  readonly description: string
}

const MODES: readonly ConfigMode[] = [
  {
    id: 'services',
    label: 'Services & SDKs',
    icon: Boxes,
    description: 'Instrument applications with a Sentry-compatible SDK or OpenTelemetry.',
  },
  {
    id: 'infrastructure',
    label: 'Infrastructure agent',
    icon: ServerCog,
    description: 'Deploy the agent across hosts, containers, and Kubernetes — pick what to collect and copy the config.',
  },
  {
    id: 'cloud',
    label: 'Cloud',
    icon: CloudCog,
    description: 'Connect an AWS, Google Cloud, or Azure account to pull metrics, inventory, and cost agentlessly.',
  },
]

function renderSetupTab(tab: SetupTab, serviceId?: string) {
  if (tab === 'infrastructure') return <InfrastructureAgentSetup />
  if (tab === 'cloud') return <CloudAccountSetup />
  return <ServicesAndSdksSetup selectedServiceId={serviceId} />
}

function SetupPage() {
  const {tab, service} = useSearch({from: '/setup'})
  const navigate = useNavigate({from: '/setup'})
  const activeMode = MODES.find((mode) => mode.id === tab) ?? MODES[0]

  return (
    <div className="mx-auto max-w-6xl space-y-4 p-4 sm:p-6">
      <PageHeader
        icon={SlidersHorizontal}
        title="Setup"
        description="Connect telemetry — instrument services, deploy the infrastructure agent, or link a cloud account."
      />

      <div className="space-y-2">
        <div className="flex flex-wrap items-center gap-1 rounded-lg border bg-card p-1">
          {MODES.map((mode) => {
            const Icon = mode.icon
            const active = mode.id === tab
            return (
              <button
                key={mode.id}
                type="button"
                onClick={() => navigate({search: {tab: mode.id}})}
                aria-pressed={active}
                className={cn(
                  'flex items-center gap-1.5 rounded-md px-3 py-1.5 text-xs font-medium transition-colors',
                  active
                    ? 'bg-secondary text-secondary-foreground'
                    : 'text-muted-foreground hover:bg-muted/50 hover:text-foreground'
                )}
              >
                <Icon className="h-3.5 w-3.5" />
                {mode.label}
              </button>
            )
          })}
        </div>
        <p className="text-xs text-muted-foreground">{activeMode.description}</p>
      </div>

      {renderSetupTab(tab, service)}
    </div>
  )
}
