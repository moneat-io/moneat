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

import {Input} from '@/components/ui/input'
import {Label} from '@/components/ui/label'
import {Select, SelectContent, SelectItem, SelectTrigger, SelectValue} from '@/components/ui/select'

function parseBoundedInteger(value: string, min: number, max: number): number | undefined {
  if (value === '') return undefined
  const parsed = Number.parseInt(value, 10)
  if (Number.isNaN(parsed) || parsed < min || parsed > max) return undefined
  return parsed
}

export interface MonitorFormData {
  name?: string
  type?: string
  url?: string
  hostname?: string
  port?: number | string
  method?: string
  keyword?: string
  dbConnectionString?: string
  dockerContainerName?: string
  dockerHost?: string
  intervalSeconds?: number
  timeoutSeconds?: number
  retries?: number
  incidentSeverity?: string
}

interface MonitorFormFieldsProps {
  readonly formData: MonitorFormData
  readonly monitorType: string
  readonly onChange: (patch: MonitorFormData) => void
  readonly showRetries?: boolean
  readonly showAlertSeverity?: boolean
}

export function MonitorFormFields({
  formData,
  monitorType,
  onChange,
  showRetries = false,
  showAlertSeverity = false,
}: MonitorFormFieldsProps) {
  return (
    <div className="space-y-4">
      <div>
        <Label htmlFor="name">Monitor Name</Label>
        <Input
          id="name"
          value={formData.name || ''}
          onChange={(e) => onChange({...formData, name: e.target.value})}
          placeholder="My Website"
        />
      </div>

      {/* HTTP/Keyword/WebSocket monitors */}
      {['http', 'keyword', 'websocket'].includes(monitorType) && (
        <div>
          <Label htmlFor="url">URL</Label>
          <Input
            id="url"
            value={formData.url || ''}
            onChange={(e) => onChange({...formData, url: e.target.value})}
            placeholder="https://example.com"
          />
        </div>
      )}

      {/* TCP/Ping/DNS/SSL monitors */}
      {['tcp', 'ping', 'dns', 'ssl'].includes(monitorType) && (
        <>
          <div>
            <Label htmlFor="hostname">Hostname</Label>
            <Input
              id="hostname"
              value={formData.hostname || ''}
              onChange={(e) => onChange({...formData, hostname: e.target.value})}
              placeholder="example.com"
            />
          </div>
          {(monitorType === 'tcp' || monitorType === 'ssl') && (
            <div>
              <Label htmlFor="port">Port</Label>
              <Input
                id="port"
                type="number"
                value={formData.port ?? ''}
                onChange={(e) => onChange({...formData, port: parseBoundedInteger(e.target.value, 1, 65535)})}
                placeholder={monitorType === 'ssl' ? '443' : '80'}
              />
            </div>
          )}
        </>
      )}

      {/* HTTP method */}
      {monitorType === 'http' && (
        <div>
          <Label htmlFor="method">HTTP Method</Label>
          <Select
            value={formData.method || 'GET'}
            onValueChange={(value) => onChange({...formData, method: value})}
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
      {monitorType === 'keyword' && (
        <div>
          <Label htmlFor="keyword">Keyword to search for</Label>
          <Input
            id="keyword"
            value={formData.keyword || ''}
            onChange={(e) => onChange({...formData, keyword: e.target.value})}
            placeholder="Success"
          />
        </div>
      )}

      {/* Database */}
      {monitorType === 'database' && (
        <div>
          <Label htmlFor="dbConnectionString">Connection String</Label>
          <Input
            id="dbConnectionString"
            value={formData.dbConnectionString || ''}
            onChange={(e) => onChange({...formData, dbConnectionString: e.target.value})}
            placeholder="jdbc:postgresql://localhost:5432/mydb"
          />
        </div>
      )}

      {/* Docker */}
      {monitorType === 'docker' && (
        <>
          <div>
            <Label htmlFor="dockerContainerName">Container Name</Label>
            <Input
              id="dockerContainerName"
              value={formData.dockerContainerName || ''}
              onChange={(e) => onChange({...formData, dockerContainerName: e.target.value})}
              placeholder="my-container"
            />
          </div>
          <div>
            <Label htmlFor="dockerHost">Docker Host</Label>
            <Input
              id="dockerHost"
              value={formData.dockerHost || ''}
              onChange={(e) => onChange({...formData, dockerHost: e.target.value})}
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
          value={formData.intervalSeconds ?? ''}
          onChange={(e) => onChange({...formData, intervalSeconds: parseBoundedInteger(e.target.value, 1, 86400)})}
        />
      </div>

      <div>
        <Label htmlFor="timeout">Timeout (seconds)</Label>
        <Input
          id="timeout"
          type="number"
          value={formData.timeoutSeconds ?? ''}
          onChange={(e) => onChange({...formData, timeoutSeconds: parseBoundedInteger(e.target.value, 1, 300)})}
        />
      </div>

      {showRetries && (
        <div>
          <Label htmlFor="retries">Retries</Label>
          <Input
            id="retries"
            type="number"
            value={formData.retries ?? ''}
            onChange={(e) => onChange({...formData, retries: parseBoundedInteger(e.target.value, 0, 10)})}
          />
        </div>
      )}

      {showAlertSeverity && (
        <div>
          <Label htmlFor="incidentSeverity">Alert Severity</Label>
          <Select
            value={formData.incidentSeverity || ''}
            onValueChange={(value) =>
              onChange({...formData, incidentSeverity: value === 'none' ? undefined : value})
            }
          >
            <SelectTrigger>
              <SelectValue placeholder="Use routing rule default" />
            </SelectTrigger>
            <SelectContent>
              <SelectItem value="none">Use routing rule default</SelectItem>
              <SelectItem value="CRITICAL">[P0] Critical</SelectItem>
              <SelectItem value="HIGH">[P1] High</SelectItem>
              <SelectItem value="MEDIUM">[P2] Medium</SelectItem>
              <SelectItem value="LOW">[P3] Low</SelectItem>
            </SelectContent>
          </Select>
          <p className="text-xs text-muted-foreground mt-1">
            Override the default incident severity when this monitor triggers an alert. P0–P2 page on-call 24/7. P3 notifies during business hours only.
          </p>
        </div>
      )}
    </div>
  )
}
