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
