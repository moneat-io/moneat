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

import {type ReactNode} from 'react'

interface Step {
  title: string
  content: ReactNode
}

interface StepListProps {
  steps: Step[]
}

export function StepList({steps}: StepListProps) {
  return (
    <ol className="space-y-6">
      {steps.map((step, index) => (
        <li key={index} className="flex gap-4">
          <div className="flex items-center justify-center h-7 w-7 rounded-full bg-primary text-primary-foreground text-sm font-semibold shrink-0 mt-0.5">
            {index + 1}
          </div>
          <div className="flex-1 space-y-2">
            <h4 className="font-medium">{step.title}</h4>
            <div className="text-sm text-muted-foreground leading-relaxed">{step.content}</div>
          </div>
        </li>
      ))}
    </ol>
  )
}
