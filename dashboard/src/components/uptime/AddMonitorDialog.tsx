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

import {useMutation, useQueryClient} from '@tanstack/react-query'
import {api, type CreateUptimeMonitorRequest} from '@/lib/api'
import {Button} from '@/components/ui/button'
import {
  Dialog,
  DialogContent,
  DialogDescription,
  DialogFooter,
  DialogHeader,
  DialogTitle,
} from '@/components/ui/dialog'
import {
  Activity,
  ArrowUp,
  Container,
  Database,
  Globe,
  Lock,
  Search,
  Server,
  Shield,
  Zap,
} from 'lucide-react'
import {useState} from 'react'
import {useToast} from '@/hooks/useToast'
import {MonitorFormFields, type MonitorFormData} from './MonitorFormFields'

interface AddMonitorDialogProps {
  readonly open: boolean
  readonly onOpenChange: (open: boolean) => void
}

const MONITOR_TYPES = [
  {
    value: 'http',
    label: 'HTTP(S)',
    description: 'Monitor HTTP/HTTPS endpoints',
    icon: Globe,
    color: 'text-chart-1',
    bgColor: 'bg-chart-1/10',
  },
  {
    value: 'keyword',
    label: 'Keyword',
    description: 'Check for keyword in response',
    icon: Search,
    color: 'text-chart-2',
    bgColor: 'bg-chart-2/10',
  },
  {
    value: 'tcp',
    label: 'TCP Port',
    description: 'Monitor TCP port availability',
    icon: Server,
    color: 'text-chart-3',
    bgColor: 'bg-chart-3/10',
  },
  {
    value: 'ping',
    label: 'Ping',
    description: 'ICMP ping check',
    icon: Activity,
    color: 'text-chart-4',
    bgColor: 'bg-chart-4/10',
  },
  {
    value: 'dns',
    label: 'DNS',
    description: 'DNS record validation',
    icon: Shield,
    color: 'text-chart-5',
    bgColor: 'bg-chart-5/10',
  },
  {
    value: 'ssl',
    label: 'SSL Certificate',
    description: 'SSL certificate expiry check',
    icon: Lock,
    color: 'text-chart-6',
    bgColor: 'bg-chart-6/10',
  },
  {
    value: 'websocket',
    label: 'WebSocket',
    description: 'WebSocket connection check',
    icon: Zap,
    color: 'text-chart-7',
    bgColor: 'bg-chart-7/10',
  },
  {
    value: 'database',
    label: 'Database',
    description: 'Database connection check',
    icon: Database,
    color: 'text-chart-8',
    bgColor: 'bg-chart-8/10',
  },
  {
    value: 'docker',
    label: 'Docker',
    description: 'Docker container health',
    icon: Container,
    color: 'text-chart-9',
    bgColor: 'bg-chart-9/10',
  },
  {
    value: 'push',
    label: 'Push',
    description: 'Passive heartbeat endpoint',
    icon: ArrowUp,
    color: 'text-chart-10',
    bgColor: 'bg-chart-10/10',
  },
]

export default function AddMonitorDialog({open, onOpenChange}: AddMonitorDialogProps) {
  const {toast} = useToast()
  const queryClient = useQueryClient()
  const [step, setStep] = useState<1 | 2>(1)
  const [formData, setFormData] = useState<MonitorFormData>({
    type: 'http',
    method: 'GET',
    intervalSeconds: 60,
    timeoutSeconds: 30,
    retries: 0,
  })

  const createMutation = useMutation({
    mutationFn: (data: CreateUptimeMonitorRequest) => api.createUptimeMonitor(data),
    onSuccess: () => {
      queryClient.invalidateQueries({queryKey: ['uptime-monitors']})
      toast({title: 'Monitor created successfully'})
      handleClose()
    },
    onError: () => {
      toast({
        title: 'Failed to create monitor',
        description: 'Please check your settings and try again.',
        variant: 'destructive',
      })
    },
  })

  const handleClose = () => {
    setStep(1)
    setFormData({
      type: 'http',
      method: 'GET',
      intervalSeconds: 60,
      timeoutSeconds: 30,
      retries: 0,
    })
    onOpenChange(false)
  }

  const handleCreate = () => {
    if (!formData.name) {
      toast({title: 'Monitor name is required', variant: 'destructive'})
      return
    }

    const type = formData.type ?? 'http'
    const port = typeof formData.port === 'number' ? formData.port : undefined

    const base: CreateUptimeMonitorRequest = {
      name: formData.name,
      type,
      intervalSeconds: formData.intervalSeconds,
      timeoutSeconds: formData.timeoutSeconds,
      retries: formData.retries,
      incidentSeverity: formData.incidentSeverity,
    }

    let typeFields: Partial<CreateUptimeMonitorRequest> = {}
    if (['http', 'keyword', 'websocket'].includes(type)) {
      typeFields = {url: formData.url, method: formData.method}
      if (type === 'keyword') typeFields.keyword = formData.keyword
    } else if (['tcp', 'ping', 'dns', 'ssl'].includes(type)) {
      typeFields = {hostname: formData.hostname}
      if (type === 'tcp' || type === 'ssl') typeFields.port = port
    } else if (type === 'database') {
      typeFields = {dbConnectionString: formData.dbConnectionString}
    } else if (type === 'docker') {
      typeFields = {dockerContainerName: formData.dockerContainerName, dockerHost: formData.dockerHost}
    }

    createMutation.mutate({...base, ...typeFields})
  }

  return (
    <Dialog open={open} onOpenChange={(isOpen) => { if (!isOpen) handleClose() }}>
      <DialogContent className="max-w-2xl max-h-[90vh] overflow-y-auto">
        <DialogHeader>
          <DialogTitle>Add Uptime Monitor</DialogTitle>
          <DialogDescription>
            {step === 1 ? 'Select monitor type' : 'Configure monitor settings'}
          </DialogDescription>
        </DialogHeader>

        {step === 1 ? (
          <div className="grid grid-cols-1 md:grid-cols-2 gap-4 py-4">
            {MONITOR_TYPES.map((type) => (
              <button
                key={type.value}
                onClick={() => {
                  setFormData({
                    type: type.value,
                    name: formData.name,
                    intervalSeconds: formData.intervalSeconds,
                    timeoutSeconds: formData.timeoutSeconds,
                    retries: formData.retries,
                    incidentSeverity: formData.incidentSeverity,
                    method: ['http', 'keyword'].includes(type.value) ? (formData.method ?? 'GET') : undefined,
                  })
                  setStep(2)
                }}
                className="flex items-start p-4 border rounded-xl hover:bg-accent hover:border-primary/50 transition-all text-left group"
              >
                <div className={`p-2 rounded-lg mr-4 ${type.bgColor} ${type.color} group-hover:scale-110 transition-transform`}>
                  <type.icon className="h-6 w-6" />
                </div>
                <div>
                  <div className="font-semibold mb-1">{type.label}</div>
                  <div className="text-sm text-muted-foreground">{type.description}</div>
                </div>
              </button>
            ))}
          </div>
        ) : (
          <div className="py-4">
            <MonitorFormFields
              formData={formData}
              monitorType={formData.type || 'http'}
              onChange={setFormData}
              showAlertSeverity
            />
          </div>
        )}

        <DialogFooter>
          {step === 2 && (
            <Button variant="outline" onClick={() => setStep(1)}>
              Back
            </Button>
          )}
          <Button variant="outline" onClick={handleClose}>
            Cancel
          </Button>
          {step === 2 && (
            <Button onClick={handleCreate} disabled={createMutation.isPending}>
              {createMutation.isPending ? 'Creating...' : 'Create Monitor'}
            </Button>
          )}
        </DialogFooter>
      </DialogContent>
    </Dialog>
  )
}
