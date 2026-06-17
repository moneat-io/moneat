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

import type {LogEntry} from '@/lib/api'
import {Separator} from '@/components/ui/separator'
import {DetailField} from '@/components/logs/LogDetailPanel'
import {AttributeTable} from '@/components/AttributeTable'

const EXCEPTION_TAG_KEYS = new Set([
  'exception.stacktrace',
  'exception.stack_trace',
  'exception.stack',
  'error.stack',
  'error.stack_trace',
  'stacktrace',
  'stack_trace',
  'exception.type',
  'error.type',
  'error.kind',
  'exception.message',
  'error.message',
])

interface LogAttributesTabProps {
  log: LogEntry
}

export function LogAttributesTab({log}: LogAttributesTabProps) {
  const hasContainer = log.containerName || log.containerId || log.containerImage
  const tagsWithoutException = Object.fromEntries(
    Object.entries(log.tags || {}).filter(([key]) => !EXCEPTION_TAG_KEYS.has(key))
  )

  return (
    <div className="space-y-4">
      {/* Infrastructure */}
      <div className="grid gap-3 sm:grid-cols-2">
        <DetailField label="Host" value={log.host} mono copyable />
        <DetailField label="Source" value={log.source} mono />
      </div>

      {hasContainer && (
        <div className="grid gap-3 sm:grid-cols-2">
          <DetailField label="Container" value={log.containerName} mono copyable />
          <DetailField label="Container ID" value={log.containerId} mono copyable />
          {log.containerImage && (
            <div className="sm:col-span-2">
              <DetailField label="Container Image" value={log.containerImage} mono copyable />
            </div>
          )}
        </div>
      )}

      <Separator />

      {/* Tags */}
      <AttributeTable label="Tags" attributes={tagsWithoutException} />

      {/* Resource Attributes */}
      <AttributeTable label="Resource Attributes" attributes={log.resourceAttributes} />

      {/* Log ID */}
      <div className="pt-2">
        <DetailField label="Log ID" value={log.logId} mono copyable />
      </div>
    </div>
  )
}
