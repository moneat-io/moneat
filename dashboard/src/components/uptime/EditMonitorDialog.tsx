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
import {api, type UpdateUptimeMonitorRequest, type UptimeMonitor} from '@/lib/api'
import {Button} from '@/components/ui/button'
import {
  Dialog,
  DialogContent,
  DialogDescription,
  DialogFooter,
  DialogHeader,
  DialogTitle,
} from '@/components/ui/dialog'
import {useState} from 'react'
import {useToast} from '@/hooks/useToast'
import {MonitorFormFields, type MonitorFormData} from './MonitorFormFields'

interface EditMonitorDialogProps {
  readonly open: boolean
  readonly onOpenChange: (open: boolean) => void
  readonly monitor: UptimeMonitor
}

export default function EditMonitorDialog({open, onOpenChange, monitor}: EditMonitorDialogProps) {
  const {toast} = useToast()
  const queryClient = useQueryClient()
  const serverFormData: MonitorFormData = monitor ? {
    name: monitor.name,
    url: monitor.url,
    hostname: monitor.hostname,
    port: monitor.port,
    method: monitor.method,
    keyword: monitor.keyword,
    dbConnectionString: monitor.dbConnectionString,
    dockerContainerName: monitor.dockerContainerName,
    dockerHost: monitor.dockerHost,
    intervalSeconds: monitor.intervalSeconds,
    timeoutSeconds: monitor.timeoutSeconds,
    retries: monitor.retries,
  } : {}
  const [localFormData, setLocalFormData] = useState<MonitorFormData | undefined>(undefined)
  const formData = (open && localFormData) ? localFormData : serverFormData

  const updateMutation = useMutation({
    mutationFn: (data: UpdateUptimeMonitorRequest) => api.updateUptimeMonitor(monitor.id, data),
    onSuccess: () => {
      queryClient.invalidateQueries({queryKey: ['uptime-monitors']})
      queryClient.invalidateQueries({queryKey: ['uptime-monitor', monitor.id]})
      toast({title: 'Monitor updated successfully'})
      onOpenChange(false)
    },
    onError: (error: Error) => {
      toast({
        title: 'Failed to update monitor',
        description: error.message,
        variant: 'destructive',
      })
    },
  })

  const handleSave = () => {
    if (!formData.name) {
      toast({title: 'Monitor name is required', variant: 'destructive'})
      return
    }

    updateMutation.mutate(formData as UpdateUptimeMonitorRequest)
  }

  return (
    <Dialog open={open} onOpenChange={onOpenChange}>
      <DialogContent className="max-w-2xl max-h-[90vh] overflow-y-auto">
        <DialogHeader>
          <DialogTitle>Edit Monitor</DialogTitle>
          <DialogDescription>
            Update monitor configuration for {monitor.name}
          </DialogDescription>
        </DialogHeader>

        <div className="py-4">
          <MonitorFormFields
            formData={formData}
            monitorType={monitor.type}
            onChange={setLocalFormData}
            showRetries
          />
        </div>

        <DialogFooter>
          <Button variant="outline" onClick={() => onOpenChange(false)}>
            Cancel
          </Button>
          <Button onClick={handleSave} disabled={updateMutation.isPending}>
            {updateMutation.isPending ? 'Saving...' : 'Save Changes'}
          </Button>
        </DialogFooter>
      </DialogContent>
    </Dialog>
  )
}
