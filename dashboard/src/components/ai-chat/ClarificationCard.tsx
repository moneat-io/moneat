// Moneat - Mobile-First Error Monitoring Platform
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

import type {AiClarification} from '@/lib/api'
import {cn} from '@/lib/utils'

interface ClarificationCardProps {
  clarification: AiClarification
  onSelect: (value: string) => void
}

export function ClarificationCard({clarification, onSelect}: ClarificationCardProps) {
  return (
    <div className="ml-9 mt-2 border border-border rounded-lg p-3 bg-card text-sm">
      <p className="mb-2">{clarification.question}</p>
      <div className="flex flex-wrap gap-1.5">
        {clarification.options?.map(option => (
          <button
            key={option.value}
            onClick={() => onSelect(option.value)}
            className={cn(
              "px-2.5 py-1 text-xs rounded-full border border-border hover:bg-primary hover:text-primary-foreground hover:border-primary transition-colors",
              option.value === clarification.default && "border-primary/50 bg-primary/5"
            )}
          >
            {option.label}
          </button>
        ))}
      </div>
    </div>
  )
}
