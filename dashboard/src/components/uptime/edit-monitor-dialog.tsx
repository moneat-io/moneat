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
import {Input} from '@/components/ui/input'
import {Label} from '@/components/ui/label'
import {Select, SelectContent, SelectItem, SelectTrigger, SelectValue} from '@/components/ui/select'
import {useState} from 'react'
import {useToast} from '@/hooks/use-toast'

interface EditMonitorDialogProps {
  open: boolean
  onOpenChange: (open: boolean) => void
  monitor: UptimeMonitor
}

export default function EditMonitorDialog({open, onOpenChange, monitor}: EditMonitorDialogProps) {
  const {toast} = useToast()
  const queryClient = useQueryClient()
  const serverFormData: Partial<UpdateUptimeMonitorRequest> = monitor ? {
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
  const [localFormData, setFormData] = useState<Partial<UpdateUptimeMonitorRequest> | undefined>(undefined)
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

        <div className="space-y-4 py-4">
          <div>
            <Label htmlFor="name">Monitor Name</Label>
            <Input
              id="name"
              value={formData.name || ''}
              onChange={(e) => setFormData({...formData, name: e.target.value})}
              placeholder="My Website"
            />
          </div>

          {/* HTTP/Keyword/WebSocket monitors */}
          {['http', 'keyword', 'websocket'].includes(monitor.type) && (
            <div>
              <Label htmlFor="url">URL</Label>
              <Input
                id="url"
                value={formData.url || ''}
                onChange={(e) => setFormData({...formData, url: e.target.value})}
                placeholder="https://example.com"
              />
            </div>
          )}

          {/* TCP/Ping/DNS/SSL monitors */}
          {['tcp', 'ping', 'dns', 'ssl'].includes(monitor.type) && (
            <>
              <div>
                <Label htmlFor="hostname">Hostname</Label>
                <Input
                  id="hostname"
                  value={formData.hostname || ''}
                  onChange={(e) => setFormData({...formData, hostname: e.target.value})}
                  placeholder="example.com"
                />
              </div>
              {(monitor.type === 'tcp' || monitor.type === 'ssl') && (
                <div>
                  <Label htmlFor="port">Port</Label>
                  <Input
                    id="port"
                    type="number"
                    value={formData.port || ''}
                    onChange={(e) => setFormData({...formData, port: parseInt(e.target.value)})}
                    placeholder={monitor.type === 'ssl' ? '443' : '80'}
                  />
                </div>
              )}
            </>
          )}

          {/* HTTP method */}
          {monitor.type === 'http' && (
            <div>
              <Label htmlFor="method">HTTP Method</Label>
              <Select
                value={formData.method || 'GET'}
                onValueChange={(value) => setFormData({...formData, method: value})}
              >
                <SelectTrigger>
                  <SelectValue />
                </SelectTrigger>
                <SelectContent>
                  <SelectItem value="GET">GET</SelectItem>
                  <SelectItem value="POST">POST</SelectItem>
                  <SelectItem value="PUT">PUT</SelectItem>
                  <SelectItem value="DELETE">DELETE</SelectItem>
                  <SelectItem value="HEAD">HEAD</SelectItem>
                </SelectContent>
              </Select>
            </div>
          )}

          {/* Keyword */}
          {monitor.type === 'keyword' && (
            <div>
              <Label htmlFor="keyword">Keyword to search for</Label>
              <Input
                id="keyword"
                value={formData.keyword || ''}
                onChange={(e) => setFormData({...formData, keyword: e.target.value})}
                placeholder="Success"
              />
            </div>
          )}

          {/* Database */}
          {monitor.type === 'database' && (
            <div>
              <Label htmlFor="dbConnectionString">Connection String</Label>
              <Input
                id="dbConnectionString"
                value={formData.dbConnectionString || ''}
                onChange={(e) => setFormData({...formData, dbConnectionString: e.target.value})}
                placeholder="jdbc:postgresql://localhost:5432/mydb"
              />
            </div>
          )}

          {/* Docker */}
          {monitor.type === 'docker' && (
            <>
              <div>
                <Label htmlFor="dockerContainerName">Container Name</Label>
                <Input
                  id="dockerContainerName"
                  value={formData.dockerContainerName || ''}
                  onChange={(e) => setFormData({...formData, dockerContainerName: e.target.value})}
                  placeholder="my-container"
                />
              </div>
              <div>
                <Label htmlFor="dockerHost">Docker Host</Label>
                <Input
                  id="dockerHost"
                  value={formData.dockerHost || ''}
                  onChange={(e) => setFormData({...formData, dockerHost: e.target.value})}
                  placeholder="http://localhost:2375"
                />
              </div>
            </>
          )}

          <div>
            <Label htmlFor="interval">Check Interval (seconds)</Label>
            <Input
              id="interval"
              type="number"
              value={formData.intervalSeconds || 60}
              onChange={(e) => setFormData({...formData, intervalSeconds: parseInt(e.target.value)})}
            />
          </div>

          <div>
            <Label htmlFor="timeout">Timeout (seconds)</Label>
            <Input
              id="timeout"
              type="number"
              value={formData.timeoutSeconds || 30}
              onChange={(e) => setFormData({...formData, timeoutSeconds: parseInt(e.target.value)})}
            />
          </div>

          <div>
            <Label htmlFor="retries">Retries</Label>
            <Input
              id="retries"
              type="number"
              value={formData.retries || 0}
              onChange={(e) => setFormData({...formData, retries: parseInt(e.target.value)})}
            />
          </div>
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
