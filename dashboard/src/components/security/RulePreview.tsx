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

import type {DetectionPreviewResponse} from '@/lib/api'

interface RulePreviewProps {
  preview: DetectionPreviewResponse
}

export function RulePreview({preview}: RulePreviewProps) {
  return (
    <div className="space-y-1.5 rounded-md border border-border bg-muted/30 p-2">
      <p className="text-xs font-medium">
        Would produce {preview.match_count} signal{preview.match_count === 1 ? '' : 's'} over the last 24h
      </p>
      {preview.samples.length > 0 && (
        <table className="w-full text-[10px]">
          <thead>
            <tr className="text-left text-muted-foreground">
              <th className="pb-1 pr-2 font-medium">Group</th>
              <th className="pb-1 font-medium text-right">Count</th>
            </tr>
          </thead>
          <tbody>
            {preview.samples.slice(0, 10).map((sample, i) => (
              <tr key={i} className="border-t border-border/50">
                <td className="py-0.5 pr-2 font-mono">
                  {Object.entries(sample.group_values)
                    .map(([k, v]) => `${k}=${v}`)
                    .join(' ') || '(all)'}
                </td>
                <td className="py-0.5 text-right tabular-nums">{sample.count}</td>
              </tr>
            ))}
          </tbody>
        </table>
      )}
    </div>
  )
}
